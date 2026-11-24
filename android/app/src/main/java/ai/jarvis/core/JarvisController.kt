package ai.jarvis.core

import ai.jarvis.BuildConfig
import ai.jarvis.audio.AudioCapture
import ai.jarvis.audio.VoiceEngine
import ai.jarvis.audio.VoiceInput
import ai.jarvis.data.AnimationMode
import ai.jarvis.data.ConversationMemory
import ai.jarvis.data.GithubPusher
import ai.jarvis.data.JarvisSettings
import ai.jarvis.data.SecureStore
import ai.jarvis.data.SettingsStore
import ai.jarvis.data.VOICE_PRESETS
import ai.jarvis.data.VoiceInputMode
import ai.jarvis.data.VoicePreset
import ai.jarvis.data.presetFor
import ai.jarvis.data.providerIdFor
import ai.jarvis.permissions.Permissions
import ai.jarvis.service.JarvisService
import ai.jarvis.tools.CapabilityStatus
import ai.jarvis.tools.Confirmation
import ai.jarvis.tools.Tool
import ai.jarvis.tools.ToolRegistry
import ai.jarvis.tools.UndoAction
import ai.jarvis.util.Haptics
import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

enum class Speaker { USER, ASSISTANT, SYSTEM }

data class TranscriptLine(
    val id: Long,
    val speaker: Speaker,
    val text: String,
)

data class TraceItem(
    val tool: String,
    val arguments: String,
    val result: String,
    val ok: Boolean,
    val latencyMs: Double,
    val at: Long = System.currentTimeMillis(),
)

data class LatencyItem(
    val stage: String,
    val ms: Double,
)

data class PendingTool(
    val id: String,
    val name: String,
    val args: JSONObject,
    val sentence: String,
)

data class JarvisUiState(
    val phase: Phase = Phase.IDLE,
    val micRms: Float = 0f,
    val ttsRms: Float = 0f,
    /** Current TTS word and its progress, used for visemes. */
    val speakingWord: String = "",
    val speakingPhase: Float = 0f,
    val lines: List<TranscriptLine> = emptyList(),
    val partial: String = "",
    val streaming: String = "",
    val mood: String = "neutral",
    val currentTool: String? = null,
    val pendingTool: PendingTool? = null,
    val traces: List<TraceItem> = emptyList(),
    val latency: List<LatencyItem> = emptyList(),
    val error: String? = null,
    val needsSetup: Boolean = false,
    val muted: Boolean = false,
    val micPermissionNeeded: Boolean = false,
    /** Denied with "don't ask again": only app Settings can grant it now. */
    val micBlocked: Boolean = false,
    val undoLabel: String? = null,
    val lastReply: String = "",
)

/**
 * Single owner of the assistant session.
 *
 * All UI state is a `StateFlow`; Compose never polls native code. Amplitude
 * values are forwarded from real measurements (Rust mic RMS, TTS word timings,
 * platform recognition RMS) and are never generated for effect.
 */
class JarvisController(
    private val app: Application,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore = SettingsStore(app)
    private val secure = SecureStore(app)
    private val registry = ToolRegistry(app)
    private val haptics = Haptics(app)
    private val lineIds = AtomicLong(1)
    private val conversationMemory: ConversationMemory = ConversationMemory()

    init {
        // Conversation memory is stored online, in the GitHub repo the user
        // configured; nothing is written to local storage.
        conversationMemory.setAutoPush(settingsStore.current.autoSync)
        conversationMemory.configure(
            settingsStore.current.githubToken,
            settingsStore.current.githubOwner,
            settingsStore.current.githubRepo,
        )
    }

    val settings: StateFlow<JarvisSettings> = settingsStore.settings

    /** Which stored key the current endpoint uses. */
    private val activeProviderId: String get() = providerIdFor(settingsStore.current.baseUrl)

    private val _ui =
        MutableStateFlow(
            JarvisUiState(needsSetup = !secure.hasApiKey(providerIdFor(settingsStore.current.baseUrl))),
        )
    val ui: StateFlow<JarvisUiState> = _ui.asStateFlow()

    @Volatile
    private var handle: Long = 0L
    private var engineStarted = false
    private var capture: AudioCapture? = null
    private var serviceRunning = false

    /** True while the user wants the microphone open; drives session restarts. */
    @Volatile
    private var listenRequested = false
    private var listenRestarts = 0
    private var restartJob: Job? = null
    private var undoAction: UndoAction? = null
    private var undoJob: Job? = null

    /** Start of this app session, used by the self-aware preamble. */
    private val sessionStartedAt = System.currentTimeMillis()

    private val tts =
        VoiceEngine(
            app,
            scope,
            object : VoiceEngine.Listener {
                override fun onAmplitude(value: Float) {
                    _ui.update { it.copy(ttsRms = value) }
                }

                override fun onWord(
                    word: String,
                    phase: Float,
                ) {
                    _ui.update { it.copy(speakingWord = word, speakingPhase = phase) }
                }

                override fun onAllDone() {
                    val current = handle
                    if (current != 0L) JarvisNative.nativeTtsDone(current)
                    _ui.update { it.copy(ttsRms = 0f) }
                }

                override fun onError(message: String) {
                    reportError(message)
                }
            },
        )

    private val voiceInput =
        VoiceInput(
            app,
            object : VoiceInput.Listener {
                override fun onPartial(text: String) {
                    _ui.update { it.copy(partial = text, phase = Phase.LISTENING) }
                }

                override fun onFinal(text: String) {
                    listenRequested = false
                    _ui.update { it.copy(partial = "", phase = Phase.THINKING) }
                    appendLine(Speaker.USER, text)
                    // Recognition runs locally, so the transcript above is real
                    // even before a key exists; only the answer needs the key.
                    if (!ensureEngine()) {
                        reportError(
                            "I heard you, but no API key is set. Add your Freebuff key in Settings for an answer.",
                        )
                        return
                    }
                    val current = handle
                    if (current == 0L) {
                        reportError("The assistant engine is not ready.")
                        return
                    }
                    // Built before the new turn is stored, so the model sees the
                    // history once instead of the input twice.
                    val prompt = promptFor(text)
                    conversationMemory.addUserMessage(text)
                    val code = JarvisNative.nativeSubmitText(current, prompt)
                    if (code != 0) reportError(codeMessage(code))
                }

                override fun onLevel(level: Float) {
                    _ui.update { it.copy(micRms = level) }
                }

                override fun onError(
                    message: String,
                    recoverable: Boolean,
                ) {
                    if (recoverable && listenRequested) {
                        // A short silence ends the platform session. The user
                        // asked to listen, so open another session rather than
                        // dropping to standby or showing a fault banner.
                        continueListening()
                    } else if (recoverable) {
                        _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
                    } else {
                        reportError(message)
                    }
                }

                override fun onEnded() {
                    if (listenRequested) {
                        continueListening()
                    } else if (_ui.value.phase == Phase.LISTENING) {
                        _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
                    }
                }
            },
        )

    init {
        seedBundledKey()
        applyVoiceSettings(settingsStore.current)
        ensureEngine()
    }

    /**
     * A build made with `-PfreebuffApiKey`, `FREEBUFF_API_KEY` or
     * `freebuff.api.key` in `android/local.properties` arrives already
     * connected: the key is sealed into the Keystore on first launch and is
     * not read from `BuildConfig` again. Builds without one start on the same
     * Freebuff preset and simply ask for a key.
     */
    private fun seedBundledKey() {
        val bundled = BuildConfig.FREEBUFF_API_KEY
        if (bundled.isBlank() || secure.hasApiKey(FREEBUFF_PROVIDER_ID)) return
        val stored = secure.saveApiKey(FREEBUFF_PROVIDER_ID, ProviderProbe.sanitizeKey(bundled))
        if (stored.isFailure) {
            Log.w(TAG, "bundled Freebuff key could not be stored", stored.exceptionOrNull())
            return
        }
        if (providerIdFor(settingsStore.current.baseUrl) == FREEBUFF_PROVIDER_ID) {
            settingsStore.setApiKeyPresent(true)
        }
    }

    // -----------------------------------------------------------------------
    // Engine lifecycle
    // -----------------------------------------------------------------------

    @Synchronized
    private fun ensureEngine(): Boolean {
        if (handle != 0L) return true
        if (!secure.hasApiKey(activeProviderId)) {
            _ui.update { it.copy(needsSetup = true) }
            return false
        }
        return try {
            val created =
                JarvisNative.nativeCreate(
                    buildConfigJson(),
                    JarvisNative.HostCallback { json ->
                        scope.launch { handleNativeEvent(json) }
                    },
                )
            if (created == 0L) {
                reportError("The assistant engine could not be created.")
                return false
            }
            val startCode = JarvisNative.nativeStart(created)
            if (startCode != 0) {
                JarvisNative.nativeDestroy(created)
                reportError(codeMessage(startCode))
                return false
            }
            handle = created
            engineStarted = true
            _ui.update { it.copy(needsSetup = false) }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "engine create failed", error)
            reportError(error.message ?: "The assistant engine failed to start.")
            false
        }
    }

    @Synchronized
    private fun rebuildEngine() {
        val current = handle
        handle = 0L
        engineStarted = false
        capture?.stop()
        capture = null
        if (current != 0L) runCatching { JarvisNative.nativeDestroy(current) }
        _ui.update { it.copy(needsSetup = !secure.hasApiKey(activeProviderId)) }
        if (secure.hasApiKey(activeProviderId)) ensureEngine()
    }

    fun shutdown() {
        voiceInput.shutdown()
        tts.shutdown()
        capture?.stop()
        capture = null
        val current = handle
        handle = 0L
        engineStarted = false
        if (current != 0L) runCatching { JarvisNative.nativeDestroy(current) }
        scope.cancel()
    }

    private fun buildConfigJson(): String {
        val s = settingsStore.current
        val preset = presetFor(s.voicePresetId)
        val persona =
            when (preset.id) {
                "jarvis" -> "Speak with a dry, understated British register."
                "calm" -> "Speak slowly and evenly, with no filler."
                "warm" -> "Sound warm and human, still brief."
                else -> ""
            }
        return JSONObject()
            .apply {
                put(
                    "qwen",
                    JSONObject().apply {
                        put("baseUrl", s.baseUrl)
                        put("apiKey", secure.apiKey(activeProviderId))
                        put("model", s.model)
                        put("fallbackModel", s.fallbackModel)
                        put("temperature", 0.4)
                        put("maxTokens", 512)
                        put("personaExtra", persona)
                    },
                )
                put("tools", registry.schemasJson())
                put(
                    "vad",
                    JSONObject().apply {
                        put("threshold", 0.012)
                        put("noiseMultiplier", 3.0)
                        put("trailingSilenceMs", 700)
                        put("maxUtteranceMs", 15000)
                        put("startFrames", 2)
                    },
                )
                put("maxHistoryMessages", 24)
                put("speakUrls", false)
                put("storageKeyB64", Base64.encodeToString(secure.dataKey(), Base64.NO_WRAP))
                put("storagePath", File(app.filesDir, "jarvis/transcript.log").absolutePath)
            }.toString()
    }

    // -----------------------------------------------------------------------
    // Native events
    // -----------------------------------------------------------------------

    private fun handleNativeEvent(raw: String) {
        runCatching {
            // Silence used to look like a dead assistant: a turn that produced no
            // text and no error left no trace anywhere. Every event is now
            // visible in debug builds.
            if (BuildConfig.DEBUG) Log.d(TAG, "engine event: ${raw.take(300)}")
            val event = JSONObject(raw)
            when (event.optString("type")) {
                "state" -> {
                    val phase =
                        when (event.optString("state")) {
                            "listening" -> Phase.LISTENING
                            "thinking" -> Phase.THINKING
                            "speaking" -> Phase.SPEAKING
                            "error" -> Phase.ERROR
                            else -> Phase.IDLE
                        }
                    _ui.update {
                        it.copy(
                            phase = phase,
                            micRms = if (phase == Phase.IDLE) 0f else it.micRms,
                            partial = if (phase == Phase.LISTENING) it.partial else "",
                        )
                    }
                }

                "micAmplitude" -> {
                    _ui.update { it.copy(micRms = event.optDouble("rms", 0.0).toFloat()) }
                }

                "bargeIn" -> {
                    tts.stop()
                    _ui.update { it.copy(ttsRms = 0f) }
                }

                "speechStart" -> {
                    _ui.update { it.copy(phase = Phase.LISTENING) }
                    haptics.tick()
                }

                "partialTranscript" -> {
                    _ui.update { it.copy(partial = event.optString("text")) }
                }

                "transcript" -> {
                    val text = event.optString("text")
                    if (text.isNotBlank()) {
                        appendLine(Speaker.USER, text)
                        _ui.update { it.copy(phase = Phase.THINKING, partial = "", streaming = "") }
                    }
                }

                "assistantDelta" -> {
                    val delta = event.optString("text")
                    _ui.update { it.copy(streaming = it.streaming + delta) }
                }

                "assistantDone" -> {
                    val text = event.optString("text").trim()
                    val mood = event.optString("mood", "neutral")
                    if (text.isEmpty()) {
                        Log.w(TAG, "the model finished with no answer for the last prompt")
                    }
                    _ui.update {
                        it.copy(
                            streaming = "",
                            mood = mood,
                            lastReply = text,
                            lines =
                                if (text.isBlank()) {
                                    it.lines
                                } else {
                                    it.lines + TranscriptLine(lineIds.getAndIncrement(), Speaker.ASSISTANT, text)
                                },
                        )
                    }
                    // Save to conversation memory; it persists itself online
                    // whenever GitHub sync is configured.
                    if (text.isNotBlank()) {
                        conversationMemory.addAssistantMessage(text)
                    }
                }

                "speak" -> {
                    if (!_ui.value.muted) {
                        tts.speak(event.optString("text"), event.optBoolean("question"))
                    } else {
                        val current = handle
                        if (current != 0L) JarvisNative.nativeTtsDone(current)
                    }
                }

                "toolCall" -> {
                    val args = event.optJSONObject("arguments") ?: JSONObject()
                    onToolCall(
                        event.optString("id"),
                        event.optString("name"),
                        args,
                    )
                }

                "mood" -> _ui.update { it.copy(mood = event.optString("mood", "neutral")) }

                "latency" -> {
                    val item =
                        LatencyItem(
                            event.optString("stage"),
                            event.optDouble("ms", 0.0),
                        )
                    _ui.update { it.copy(latency = (it.latency + item).takeLast(16)) }
                }

                "toolTrace" -> {
                    val trace =
                        TraceItem(
                            tool = event.optString("tool"),
                            arguments = event.optJSONObject("arguments")?.toString() ?: "{}",
                            result = event.optString("result"),
                            ok = event.optBoolean("ok"),
                            latencyMs = event.optDouble("latencyMs", 0.0),
                        )
                    _ui.update { it.copy(traces = (listOf(trace) + it.traces).take(80)) }
                }

                "error" -> {
                    val detail =
                        event.optJSONObject("error")?.optString("detail")
                            ?.takeIf { it.isNotBlank() }
                            ?: event.optString("kind")
                                ?.takeIf { it.isNotBlank() }
                                ?.replaceFirstChar { it.uppercase() }
                                ?.replace('_', ' ')
                                ?: "The assistant ran into a problem."
                    Log.w(TAG, "engine error: $detail")
                    reportError(detail, phase = Phase.ERROR)
                }

                "engine" -> Unit
                else -> Log.d(TAG, "unhandled event: ${event.optString("type")}")
            }
        }.onFailure { Log.w(TAG, "bad engine event: $raw", it) }
    }

    // -----------------------------------------------------------------------
    // Conversation
    // -----------------------------------------------------------------------

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "command received: \"$trimmed\"")
        if (!ensureEngine()) return
        appendLine(Speaker.USER, trimmed)
        // Built before the new turn is stored, so the model sees the history
        // once instead of the input twice.
        val prompt = promptFor(trimmed)
        conversationMemory.addUserMessage(trimmed)
        _ui.update { it.copy(streaming = "", partial = "", phase = Phase.THINKING, error = null) }
        val current = handle
        if (current == 0L) {
            reportError("The assistant engine is not ready.")
            return
        }
        val code = JarvisNative.nativeSubmitText(current, prompt)
        if (code != 0) reportError(codeMessage(code))
    }

    /**
     * The microphone is checked first and is deliberately **not** gated on an
     * API key: the platform recogniser runs on-device, so the user can speak and
     * read the transcript before any key exists. Only the reply needs a key, and
     * `VoiceInput.onFinal` reports that. Gating here made MIC a silent no-op in
     * a fresh install, which looks exactly like a dead microphone.
     */
    fun startListening(): Boolean {
        if (!Permissions.granted(app, Permissions.MIC)) {
            _ui.update { it.copy(micPermissionNeeded = true) }
            return false
        }
        listenRequested = true
        listenRestarts = 0
        // The Rust VAD/Whisper path feeds PCM into the engine, so that mode
        // genuinely cannot run without one.
        if (settingsStore.current.voiceInput == VoiceInputMode.ON_DEVICE && !ensureEngine()) {
            _ui.update { it.copy(phase = Phase.ERROR) }
            return false
        }
        _ui.update { it.copy(micPermissionNeeded = false, error = null, partial = "") }
        // A blocked foreground-service start must not kill the mic: the system
        // recogniser holds the microphone in its own process.
        runCatching { JarvisService.start(app) }
            .onFailure { Log.w(TAG, "foreground service start failed", it) }
        haptics.tick()
        _ui.update { it.copy(phase = Phase.LISTENING) }
        val started =
            when (settingsStore.current.voiceInput) {
                VoiceInputMode.SYSTEM -> voiceInput.start()
                VoiceInputMode.ON_DEVICE -> startNativeCapture()
            }
        if (!started) {
            _ui.update { it.copy(phase = Phase.ERROR, micRms = 0f) }
        }
        return started
    }

    private fun startNativeCapture(): Boolean {
        capture?.stop()
        val newCapture =
            AudioCapture(
                onSamples = { frame ->
                    val current = handle
                    if (current != 0L) JarvisNative.nativePushPcm(current, frame)
                },
                onError = { message -> scope.launch { reportError(message) } },
            )
        if (!newCapture.start()) return false
        capture = newCapture
        return true
    }

    /**
     * Reopens the recogniser after a session ended on its own.
     *
     * The platform finishes a recognition session at the first silence, which
     * used to drop the UI back to STANDBY almost immediately. Restarting keeps
     * the microphone open until the user speaks or taps STOP.
     */
    private fun continueListening() {
        if (!listenRequested) return
        if (listenRestarts >= MAX_LISTEN_RESTARTS) {
            listenRequested = false
            reportError(
                "I stopped listening after a while without hearing you. Tap MIC to try again.",
                phase = Phase.IDLE,
            )
            return
        }
        if (restartJob?.isActive == true) return
        listenRestarts += 1
        _ui.update { it.copy(phase = Phase.LISTENING, micRms = 0f, error = null) }
        // Back off progressively: the platform needs a moment to release the
        // microphone, and restarting too eagerly only produces BUSY errors.
        val waitMs =
            (LISTEN_RESTART_BASE_MS + listenRestarts * LISTEN_RESTART_STEP_MS)
                .coerceAtMost(LISTEN_RESTART_MAX_MS)
        restartJob =
            scope.launch {
                delay(waitMs)
                if (listenRequested && _ui.value.phase == Phase.LISTENING && !voiceInput.isActive) {
                    voiceInput.start()
                }
            }
    }

    fun stopListening() {
        listenRequested = false
        voiceInput.stop()
        capture?.stop()
        capture = null
        if (_ui.value.phase == Phase.LISTENING) {
            _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
        }
    }

    fun cancel() {
        listenRequested = false
        val current = handle
        if (current != 0L) JarvisNative.nativeCancel(current)
        tts.stop()
        voiceInput.cancel()
        capture?.stop()
        capture = null
        haptics.stop()
        _ui.update {
            it.copy(
                phase = Phase.IDLE,
                partial = "",
                streaming = "",
                ttsRms = 0f,
                micRms = 0f,
                currentTool = null,
                pendingTool = null,
            )
        }
    }

    fun onMicPressed() {
        if (_ui.value.phase == Phase.LISTENING) {
            stopListening()
        } else {
            startListening()
        }
    }

    /**
     * `canAskAgain` is false once Android will no longer show the system prompt
     * (second denial or "don't ask again"); the UI then offers app Settings,
     * because relaunching the permission request would silently do nothing.
     */
    fun onMicPermissionResult(
        granted: Boolean,
        canAskAgain: Boolean = true,
    ) {
        _ui.update { it.copy(micPermissionNeeded = false, micBlocked = !granted && !canAskAgain) }
        if (granted) {
            startListening()
        } else if (canAskAgain) {
            reportError("Without the microphone, JARVIS works in text mode only.")
        } else {
            reportError("Microphone access is blocked. Allow it in Settings → Permissions → Microphone.")
        }
    }

    fun dismissMicRequest() {
        _ui.update { it.copy(micPermissionNeeded = false) }
    }

    fun toggleMute() {
        val muted = !_ui.value.muted
        tts.setMuted(muted)
        _ui.update { it.copy(muted = muted, ttsRms = 0f) }
        if (muted) {
            val current = handle
            if (current != 0L) JarvisNative.nativeTtsDone(current)
        }
    }

    // -----------------------------------------------------------------------
    // Tools
    // -----------------------------------------------------------------------

    private fun onToolCall(
        id: String,
        name: String,
        args: JSONObject,
    ) {
        val tool = registry.find(name)
        if (tool == null) {
            val current = handle
            if (current != 0L) {
                JarvisNative.nativeSubmitToolResult(
                    current,
                    id,
                    """{"ok":false,"result":"Unknown tool"}""",
                )
            }
            return
        }
        // Auto-send: messaging tools skip confirmation when autoSendMessages is enabled
        val autoSend = settingsStore.current.autoSendMessages &&
            name in setOf("send_sms", "send_whatsapp", "send_message")
        val trusted = name in settingsStore.current.trustedTools || autoSend
        if (tool.spec.confirmation == Confirmation.REQUIRED && !trusted) {
            haptics.tick()
            _ui.update {
                it.copy(
                    pendingTool =
                        PendingTool(
                            id = id,
                            name = name,
                            args = args,
                            sentence = registry.describe(name, args),
                        ),
                )
            }
        } else {
            executeTool(PendingTool(id, name, args, registry.describe(name, args)))
        }
    }

    fun confirmPendingTool() {
        val pending = _ui.value.pendingTool ?: return
        haptics.confirm()
        executeTool(pending)
    }

    fun denyPendingTool() {
        val pending = _ui.value.pendingTool ?: return
        _ui.update { it.copy(pendingTool = null) }
        val current = handle
        if (current != 0L) {
            JarvisNative.nativeSubmitToolResult(
                current,
                pending.id,
                """{"ok":false,"result":"The user declined."}""",
            )
        }
    }

    private fun executeTool(request: PendingTool) {
        _ui.update {
            it.copy(
                pendingTool = null,
                currentTool = capabilityLabel(request.name),
                phase = if (it.phase == Phase.IDLE) Phase.THINKING else it.phase,
            )
        }
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val result =
                withContext(Dispatchers.IO) {
                    registry.execute(request.name, request.args)
                }
            val latency = (SystemClock.elapsedRealtime() - started).toDouble()
            val current = handle
            if (current != 0L) {
                JarvisNative.nativeRecordToolTrace(
                    current,
                    request.name,
                    request.args.toString(),
                    result.speech,
                    result.ok,
                    latency.toFloat(),
                )
                JarvisNative.nativeSubmitToolResult(current, request.id, result.toModelJson())
            }
            val trace =
                TraceItem(
                    tool = request.name,
                    arguments = request.args.toString(),
                    result = result.speech,
                    ok = result.ok,
                    latencyMs = latency,
                )
            _ui.update {
                it.copy(
                    currentTool = null,
                    traces = (listOf(trace) + it.traces).take(80),
                    error = if (result.ok) it.error else result.speech,
                )
            }
            result.undo?.let(::showUndo)
        }
    }

    private fun showUndo(action: UndoAction) {
        undoJob?.cancel()
        undoAction = action
        _ui.update { it.copy(undoLabel = action.label) }
        undoJob =
            scope.launch {
                delay(UNDO_WINDOW_MS)
                if (isActive) {
                    undoAction = null
                    _ui.update { it.copy(undoLabel = null) }
                }
            }
    }

    fun performUndo() {
        val action = undoAction ?: return
        undoJob?.cancel()
        undoAction = null
        _ui.update { it.copy(undoLabel = null) }
        runCatching { action.undo() }
            .onFailure { reportError("Undo failed: ${it.message}") }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun reportError(
        message: String,
        phase: Phase = Phase.ERROR,
    ) {
        _ui.update { it.copy(error = message, phase = phase) }
    }

    private fun appendLine(
        speaker: Speaker,
        text: String,
    ) {
        _ui.update {
            it.copy(lines = (it.lines + TranscriptLine(lineIds.getAndIncrement(), speaker, text)).takeLast(200))
        }
    }

    private fun codeMessage(code: Int): String =
        when (code) {
            JarvisNative.CODE_NOT_CONFIGURED -> "Add an API key in Settings."
            JarvisNative.CODE_PERMISSION_DENIED -> "Android denied a permission JARVIS needs."
            JarvisNative.CODE_NETWORK ->
                "The reasoning endpoint could not be reached. Check the connection, base URL and key."
            JarvisNative.CODE_MODEL -> "The model or its local files are unusable."
            JarvisNative.CODE_AUDIO -> "The audio device failed."
            JarvisNative.CODE_CANCELLED -> "Cancelled."
            else -> "The assistant failed with code $code."
        }

    private fun capabilityLabel(name: String): String =
        when (name) {
            "call_contact", "answer_call", "end_call" -> "Calling…"
            "send_sms", "send_whatsapp", "send_message" -> "Messaging…"
            "search_contacts", "get_contact_number" -> "Looking up…"
            "search_web", "open_url", "navigate_to", "find_nearby" -> "Searching…"
            "web_search", "read_page" -> "Looking it up online…"
            "set_alarm", "set_timer", "create_event", "read_today_events" -> "Scheduling…"
            "set_brightness", "set_volume", "set_dnd", "toggle_flashlight" -> "Adjusting…"
            "open_app", "close_app" -> "Switching apps…"
            "play_music", "pause_music", "next_track" -> "Media…"
            "read_screen_text", "tap_element", "scroll" -> "Reading screen…"
            "control_device" -> "Smart home…"
            "write_note" -> "Saving…"
            else -> "Working…"
        }

    // -----------------------------------------------------------------------
    // Settings and capability plumbing
    // -----------------------------------------------------------------------

    fun tools(): List<Tool> = registry.all.sortedBy { it.spec.name }

    fun capabilityStatus(tool: Tool): CapabilityStatus = Permissions.status(app, tool)

    fun restrictedTools(): List<Tool> = registry.restrictedTools()

    fun voicePresets(): List<VoicePreset> = VOICE_PRESETS

    fun saveApiKey(raw: String): Boolean {
        val key = ProviderProbe.sanitizeKey(raw)
        if (key.isEmpty()) return false
        val failure =
            secure.saveApiKey(activeProviderId, key).exceptionOrNull()
        if (failure != null) {
            Log.e(TAG, "key store rejected the key", failure)
            reportError("The key could not be stored: ${failure.message ?: "keystore error"}")
            return false
        }
        settingsStore.setApiKeyPresent(secure.hasApiKey(activeProviderId))
        // Creating the native engine parses config and touches the filesystem;
        // do it off the main thread so the sheet answers immediately.
        scope.launch(Dispatchers.IO) {
            runCatching { rebuildEngine() }.onFailure { error ->
                Log.e(TAG, "engine rebuild failed", error)
                reportError(error.message ?: "The engine could not start with that key.")
            }
        }
        return true
    }

    /**
     * Proves the endpoint accepts a key and returns the model IDs it allows.
     * `key` is the field being typed; when blank the stored key is used.
     */
    fun testProvider(
        baseUrl: String,
        key: String,
        model: String,
        onResult: (ProviderProbe.Result) -> Unit,
    ) {
        val effective =
            ProviderProbe
                .sanitizeKey(key)
                .ifEmpty { secure.apiKey(providerIdFor(baseUrl.ifBlank { settingsStore.current.baseUrl })) }
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    ProviderProbe.listModels(
                        baseUrl.ifBlank { settingsStore.current.baseUrl },
                        effective,
                        model.ifBlank { settingsStore.current.model },
                    )
                }
            onResult(result)
        }
    }

    fun saveEndpoint(
        baseUrl: String,
        model: String,
        fallback: String,
    ) {
        settingsStore.setEndpoint(baseUrl, model, fallback)
        settingsStore.setApiKeyPresent(secure.hasApiKey(activeProviderId))
        rebuildEngine()
    }

    fun saveVoice(
        presetId: String,
        rate: Float,
        pitch: Float,
        volume: Float,
    ) {
        settingsStore.setVoice(presetId, rate, pitch, volume)
        applyVoiceSettings(settingsStore.current)
    }

    fun speakTestLine() {
        val preset = presetFor(settingsStore.current.voicePresetId)
        applyVoiceSettings(settingsStore.current)
        tts.speak(
            "Good evening, sir. All systems are nominal and the reactor is stable.",
            question = false,
        )
        Log.d(TAG, "test line via ${preset.label}")
    }

    fun setAnimationMode(mode: AnimationMode) = settingsStore.setAnimationMode(mode)

    fun setVoiceInput(mode: VoiceInputMode) = settingsStore.setVoiceInput(mode)

    /**
     * Wake-word mode keeps the microphone open in the foreground service and
     * reacts to "Jarvis" whether or not the activity is on screen.
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled && !Permissions.granted(app, Permissions.MIC)) {
            _ui.update { it.copy(micPermissionNeeded = true) }
            return
        }
        settingsStore.setWakeWordEnabled(enabled)
        if (enabled) {
            runCatching { JarvisService.start(app) }
                .onFailure { reportError("The wake word service could not start: ${it.message}") }
        } else {
            runCatching { JarvisService.stop(app) }
        }
    }

    fun setWakeSensitivity(value: Float) = settingsStore.setWakeSensitivity(value)

    fun setWebhook(url: String) = settingsStore.setWebhook(url)

    fun setTrusted(
        toolName: String,
        trusted: Boolean,
    ) = settingsStore.setTrusted(toolName, trusted)

    /** Turn the self-aware persona on or off; it is applied on the next turn. */
    fun setSelfAware(enabled: Boolean) = settingsStore.setSelfAware(enabled)

    /**
     * The full prompt for one turn: who the assistant is (self-model and
     * continuity), what was already said, then the new input. All of it is
     * bounded, so a long history cannot crowd out the question.
     */
    private fun promptFor(input: String): String {
        val parts = ArrayList<String>(3)
        if (settingsStore.current.selfAware) {
            parts.add(
                Consciousness.preamble(
                    now = System.currentTimeMillis(),
                    sessionStartedAt = sessionStartedAt,
                    rememberedMessages = conversationMemory.size,
                    mood = _ui.value.mood,
                    cloudBacked = conversationMemory.isCloudBacked,
                ),
            )
        }
        parts.add(INTERNET_HINT)
        val remembered = conversationMemory.getConversationContext(5)
        if (remembered.isNotBlank()) parts.add(remembered.trimEnd())
        parts.add("Current input: $input")
        return parts.joinToString("\n\n")
    }

    fun setAutoSendMessages(enabled: Boolean) {
        settingsStore.setAutoSendMessages(enabled)
        // When enabling auto-send, mark all messaging tools as trusted
        if (enabled) {
            setTrusted("send_sms", true)
            setTrusted("send_whatsapp", true)
            setTrusted("send_message", true)
        }
    }

    // GitHub sync
    fun setGitHubConfig(token: String, owner: String, repo: String) {
        settingsStore.setGitHubConfig(token, owner, repo)
        conversationMemory.configure(token, owner, repo)
    }

    fun setAutoSync(enabled: Boolean) {
        settingsStore.setAutoSync(enabled)
        conversationMemory.setAutoPush(enabled)
    }

    fun testGitHubConnection() {
        val token = settingsStore.current.githubToken
        if (token.isBlank() || settingsStore.current.githubOwner.isBlank() || settingsStore.current.githubRepo.isBlank()) {
            reportError("GitHub not configured. Set token, owner, and repo in Settings.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val pusher = GithubPusher(token, settingsStore.current.githubOwner, settingsStore.current.githubRepo)
            val result = pusher.verifyToken()
            withContext(Dispatchers.Main) {
                if (result.success) {
                    reportError("GitHub connected: ${result.message}")
                    clearError()
                } else {
                    reportError("GitHub: ${result.message}")
                }
            }
        }
    }

    fun pushToGitHub() {
        val token = settingsStore.current.githubToken
        val owner = settingsStore.current.githubOwner
        val repo = settingsStore.current.githubRepo
        if (token.isBlank() || owner.isBlank() || repo.isBlank()) {
            reportError("GitHub not configured. Set token, owner, and repo in Settings.")
            return
        }
        conversationMemory.configure(token, owner, repo)
        scope.launch(Dispatchers.IO) {
            val result = conversationMemory.syncNow()
            withContext(Dispatchers.Main) {
                if (result.success) {
                    reportError("Conversation saved to GitHub: ${result.url ?: ConversationMemory.REMOTE_PATH}")
                    clearError()
                } else {
                    reportError("GitHub push failed: ${result.message}")
                }
            }
        }
    }


    fun openSpecialAccess(permission: String) =
        when (permission) {
            Manifest.permission.WRITE_SETTINGS -> Permissions.openWriteSettings(app)
            Manifest.permission.ACCESS_NOTIFICATION_POLICY -> Permissions.openDndAccess(app)
            else -> Log.w(TAG, "no special-access screen for $permission")
        }

    fun openNotificationAccess() = Permissions.openNotificationAccess(app)

    fun openAccessibilitySettings() = Permissions.openAccessibility(app)

    fun onServiceRunning(running: Boolean) {
        serviceRunning = running
    }

    val isServiceRunning: Boolean get() = serviceRunning

    private fun applyVoiceSettings(settings: JarvisSettings) {
        tts.configure(
            preset = presetFor(settings.voicePresetId),
            rate = settings.rate,
            pitch = settings.pitch,
            volume = settings.volume,
            maleVoice = settings.maleVoice,
        )
    }

    fun setMaleVoice(enabled: Boolean) {
        settingsStore.setMaleVoice(enabled)
        applyVoiceSettings(settingsStore.current)
    }

    private companion object {
        const val TAG = "JarvisController"
        const val UNDO_WINDOW_MS = 5_000L
        const val LISTEN_RESTART_BASE_MS = 250L
        const val LISTEN_RESTART_STEP_MS = 100L
        const val LISTEN_RESTART_MAX_MS = 600L
        const val MAX_LISTEN_RESTARTS = 120
        const val FREEBUFF_PROVIDER_ID = "freebuff"

        /**
         * The system prompt lives in the prebuilt Rust library, so the internet
         * policy is stated in the turn instead: the model must look facts up
         * itself rather than sending the user to a browser.
         */
        const val INTERNET_HINT =
            "You have live internet access through the web_search and read_page tools. " +
                "For any question about facts, people, places, news or anything you are unsure of, " +
                "call web_search first and answer from the results. Never tell the user to search " +
                "or open a browser themselves."
    }
}
