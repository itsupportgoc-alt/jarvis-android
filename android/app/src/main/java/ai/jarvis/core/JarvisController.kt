package ai.jarvis.core

import ai.jarvis.BuildConfig
import ai.jarvis.audio.AudioCapture
import ai.jarvis.audio.VoiceEngine
import ai.jarvis.audio.SpeechLanguage
import ai.jarvis.audio.VoiceInput
import ai.jarvis.audio.WakeWord
import ai.jarvis.awareness.AwarenessRepository
import ai.jarvis.awareness.BackendKind
import ai.jarvis.awareness.EngineFacts
import ai.jarvis.awareness.IdentitySnapshot
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
import ai.jarvis.data.providerPresetFor
import ai.jarvis.memory.MemoryManager
import ai.jarvis.memory.MemoryState
import ai.jarvis.permissions.Permissions
import ai.jarvis.service.JarvisService
import ai.jarvis.tools.CapabilityStatus
import ai.jarvis.tools.Confirmation
import ai.jarvis.tools.Tool
import ai.jarvis.tools.ToolRegistry
import ai.jarvis.tools.UndoAction
import ai.jarvis.util.Haptics
import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
    private val awareness = AwarenessRepository(app, registry)
    private val haptics = Haptics(app)
    private val lineIds = AtomicLong(1)
    private val conversationMemory: ConversationMemory = ConversationMemory()

    init {
        // Encrypted local memory is the primary store (Rust). ConversationMemory
        // remains an optional, off-by-default GitHub export.
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
            JarvisUiState(needsSetup = !hasReasoningBackend()),
        )
    val ui: StateFlow<JarvisUiState> = _ui.asStateFlow()

    @Volatile
    private var handle: Long = 0L
    private var engineStarted = false

    private val memoryManager = MemoryManager { handle }

    /** Encrypted local memory, owned by the Rust engine. */
    val memory: StateFlow<MemoryState> = memoryManager.state
    private var capture: AudioCapture? = null
    private var serviceRunning = false

    /** True while the user wants the microphone open; drives session restarts. */
    @Volatile
    private var listenRequested = false

    /**
     * True while a spoken conversation is open: after every reply the
     * microphone reopens, so the next sentence needs no tap and no wake word.
     * Only a turn that arrived by voice arms this.
     */
    @Volatile
    private var conversationOpen = false

    /** A spoken turn is in flight; the mic reopens when the engine is idle. */
    @Volatile
    private var followUpPending = false
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
                    // A spoken "stop" while the microphone is open ends the turn
                    // instead of being sent to the model as a request.
                    if (WakeWord.isStop(text)) {
                        cancel()
                        return
                    }
                    if (WakeWord.isWakeOnly(text)) {
                        rePromptForCommand()
                        return
                    }
                    conversationOpen = true
                    followUpPending = true
                    listenRequested = false
                    // A real utterance resets the silence backoff.
                    listenRestarts = 0
                    _ui.update { it.copy(partial = "", phase = Phase.THINKING) }
                    appendLine(Speaker.USER, text)
                    // Recognition runs locally, so the transcript above is real
                    // even before a key exists. The keyless engine records the
                    // turn and returns a typed "not configured" error itself.
                    conversationMemory.addUserMessage(text)
                    scope.launch {
                        if (!withContext(Dispatchers.IO) { ensureEngine() }) {
                            reportError("The assistant engine is not ready.")
                            return@launch
                        }
                        val current = handle
                        if (current == 0L) {
                            reportError("The assistant engine is not ready.")
                            return@launch
                        }
                        val code = JarvisNative.nativeSubmitTurn(current, text, preambleFor())
                        if (code != 0) reportError(codeMessage(code))
                    }
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
        // A local GGUF load reads weights and can take seconds; never do that on
        // the main thread. Remote engine creation is quick but uses the same path.
        scope.launch {
            withContext(Dispatchers.IO) { ensureEngine() }
            refreshAwareness()
            memoryManager.refresh()
        }
    }

    /**
     * Verified self-model: versions, backend, capabilities and limits. The
     * prompt, the Settings identity block and, later, the awareness surface
     * read this same snapshot, so nothing can describe JARVIS two ways.
     */
    val identity: StateFlow<IdentitySnapshot> = awareness.identity

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
        // The engine no longer requires a key: local memory and device tools
        // must work before one exists. Without a key or an imported local model
        // it selects no reasoning backend and reports that on any turn.
        _ui.update { it.copy(needsSetup = !hasReasoningBackend()) }
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
            _ui.update { it.copy(needsSetup = !hasReasoningBackend()) }
            refreshAwareness()
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
        _ui.update { it.copy(needsSetup = !hasReasoningBackend()) }
        ensureEngine()
        refreshAwareness()
        memoryManager.refresh()
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
        memoryManager.shutdown()
        refreshAwareness()
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
                    "backend",
                    when {
                        localBackendActive() -> "local"
                        secure.hasApiKey(activeProviderId) -> "remote"
                        else -> "none"
                    },
                )
                put("modelPath", s.localModelPath)
                put("contextTokens", 2_048)
                put("threads", 3)
                put("localMaxTokens", 256)
                put("maxRamMb", 2_048)
                put("memoryPath", File(app.filesDir, "jarvis/memory.enc").absolutePath)
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
                    if (phase == Phase.IDLE) reopenForFollowUp()
                    // Engine state is part of the self-model; refresh on transitions only.
                    refreshAwareness()
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
                        // Silence looks like a dead assistant: say the turn failed.
                        reportError("I didn't get an answer that time. Ask me again.", phase = Phase.IDLE)
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
                    // Conversation memory remains the optional GitHub export;
                    // local encrypted memory is refreshed from the engine.
                    if (text.isNotBlank()) {
                        conversationMemory.addAssistantMessage(text)
                    }
                    memoryManager.refresh()
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
        appendLine(Speaker.USER, trimmed)
        conversationMemory.addUserMessage(trimmed)
        _ui.update { it.copy(streaming = "", partial = "", phase = Phase.THINKING, error = null) }
        scope.launch {
            // A local GGUF load can take seconds, so never wait for it on main.
            if (!withContext(Dispatchers.IO) { ensureEngine() }) {
                reportError("The assistant engine is not ready.")
                return@launch
            }
            val current = handle
            if (current == 0L) {
                reportError("The assistant engine is not ready.")
                return@launch
            }
            val code = JarvisNative.nativeSubmitTurn(current, trimmed, preambleFor())
            if (code != 0) reportError(codeMessage(code))
        }
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
        if (restartJob?.isActive == true) return
        listenRestarts += 1
        _ui.update { it.copy(phase = Phase.LISTENING, micRms = 0f, error = null) }
        // The microphone stays open until STOP is tapped: there is no restart
        // limit any more, only a progressively longer pause between silent
        // sessions, so a long silence cannot spin the recogniser. The platform
        // also needs a moment to release the microphone, and restarting too
        // eagerly only produces BUSY errors.
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

    /**
     * The name on its own is how a listener starts over after a silence, not a
     * request. Answering it made JARVIS invent a reply to "Jarvis", so keep the
     * session open and wait for the actual command instead.
     *
     * `VoiceInput` calls `onEnded` immediately after `onFinal`, and that path
     * reopens the recogniser while `listenRequested` is still true.
     */
    private fun rePromptForCommand() {
        if (BuildConfig.DEBUG) Log.i(TAG, "heard only the wake word; waiting for a command")
        listenRequested = true
        listenRestarts = 0
        haptics.tick()
        _ui.update { it.copy(phase = Phase.LISTENING, partial = "", error = null) }
        if (!voiceInput.isActive) continueListening()
    }

    /**
     * Keeps a spoken conversation going. Once the reply has finished playing
     * the engine reports IDLE, and the microphone reopens there, so the user
     * can simply keep talking. The wake listener stands down at the same time
     * because the phase is no longer IDLE.
     *
     * A typed message never arms this, and neither does the wake word on its
     * own, so a request that was not spoken still ends in standby.
     */
    private fun reopenForFollowUp() {
        if (!conversationOpen || !followUpPending) return
        followUpPending = false
        if (_ui.value.phase != Phase.IDLE) return
        if (!Permissions.granted(app, Permissions.MIC)) {
            conversationOpen = false
            return
        }
        Log.i(TAG, "reply finished; reopening the microphone for a follow-up")
        startListening()
    }

    fun stopListening() {
        conversationOpen = false
        followUpPending = false
        listenRequested = false
        voiceInput.stop()
        capture?.stop()
        capture = null
        if (_ui.value.phase == Phase.LISTENING) {
            _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
        }
    }

    fun cancel() {
        conversationOpen = false
        followUpPending = false
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
        refreshAwareness()
        if (granted) {
            startListening()
        } else if (canAskAgain) {
            reportError("Without the microphone, SEVEN works in text mode only.")
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
            memoryManager.refresh()
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
            JarvisNative.CODE_PERMISSION_DENIED -> "Android denied a permission SEVEN needs."
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
        refreshAwareness()
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
        scope.launch {
            withContext(Dispatchers.IO) { rebuildEngine() }
        }
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

    /** Switching preset applies that preset's own tuning immediately. */
    fun selectVoicePreset(presetId: String) {
        settingsStore.selectVoicePreset(presetId)
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

    fun setVoiceInput(mode: VoiceInputMode) {
        settingsStore.setVoiceInput(mode)
        refreshAwareness()
    }

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
        refreshAwareness()
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

    private fun hasReasoningBackend(): Boolean =
        secure.hasApiKey(activeProviderId) || localBackendActive()

    private fun localBackendActive(): Boolean =
        BuildConfig.LOCAL_LLM &&
            settingsStore.current.localBackend &&
            settingsStore.current.localModelPath.isNotBlank() &&
            File(settingsStore.current.localModelPath).isFile

    /** Select the imported GGUF, or refuse clearly if this build has no backend. */
    fun setLocalBackend(enabled: Boolean) {
        if (enabled && !BuildConfig.LOCAL_LLM) {
            reportError("This build has no llama.cpp backend. Rebuild with -PlocalLlm.")
            return
        }
        if (enabled && settingsStore.current.localModelPath.isBlank()) {
            reportError("Import a .gguf model first.")
            return
        }
        if (enabled && !File(settingsStore.current.localModelPath).isFile) {
            reportError("The imported model file is missing. Import it again.")
            return
        }
        settingsStore.setLocalBackend(enabled)
        scope.launch {
            withContext(Dispatchers.IO) { rebuildEngine() }
        }
    }

    /**
     * Copies a SAF-picked GGUF into app-private storage and applies the same
     * RAM guard as Rust before selecting it. The copy is deliberately explicit:
     * the content URI is not persisted and the engine never reads shared storage.
     */
    fun importLocalModel(uri: Uri) {
        scope.launch {
            var copied: File? = null
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val display = localModelDisplayName(uri)
                        require(display.endsWith(".gguf", ignoreCase = true)) { "choose a .gguf file" }
                        val safeName =
                            display
                                .substringAfterLast('/')
                                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                .ifBlank { "model.gguf" }
                        val directory = File(app.filesDir, "models").apply { mkdirs() }
                        val target = File(directory, safeName)
                        copied = target
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("the selected file could not be opened")
                        require(target.length() > 0L) { "the selected file is empty" }
                        val magic = ByteArray(4)
                        target.inputStream().use { it.read(magic) }
                        require(String(magic, Charsets.US_ASCII) == "GGUF") {
                            "the selected file is not a GGUF model"
                        }

                        val requiredMb = target.length() / (1024L * 1024L) + RAM_RESERVE_MB
                        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val memory = ActivityManager.MemoryInfo()
                        manager.getMemoryInfo(memory)
                        val availableMb = memory.availMem / (1024L * 1024L)
                        require(requiredMb <= MAX_MODEL_RAM_MB) {
                            "insufficientMemory: model needs $requiredMb MB including reserve, ceiling is $MAX_MODEL_RAM_MB MB"
                        }
                        require(requiredMb <= availableMb) {
                            "insufficientMemory: model needs $requiredMb MB including reserve, only $availableMb MB available"
                        }
                        target to safeName
                    }
                }
            result
                .onSuccess { (target, name) ->
                    settingsStore.setLocalModel(target.absolutePath, name)
                    settingsStore.setLocalBackend(true)
                    withContext(Dispatchers.IO) { rebuildEngine() }
                    appendLine(Speaker.SYSTEM, "Imported $name. On-device reasoning is selected.")
                }.onFailure {
                    copied?.delete()
                    reportError("Model import failed: ${it.message}")
                }
        }
    }

    private fun localModelDisplayName(uri: Uri): String {
        app.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    }

    /**
     * The full prompt for one turn: the verified self-model, what was already
     * said, then the new input. All of it is bounded, so a long history cannot
     * crowd out the question. Fresh facts are read here because connectivity and
     * permissions can change between turns.
     */
    /**
     * The verified self-model, sent as a system message on each turn. It is
     * deliberately not part of the user text: that would leak it into local
     * memory and the conversation history.
     */
    private fun preambleFor(): String {
        refreshAwareness()
        return Consciousness.preamble(
            identity = awareness.current,
            uptimeMinutes =
                ((System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(0L) / 60_000L),
            rememberedMessages = conversationMemory.size,
            mood = _ui.value.mood,
            persona = settingsStore.current.selfAware,
        )
    }

    /**
     * Rebuilds the verified self-model. Cheap enough for user-visible
     * transitions and prompt assembly; never called from the audio path.
     */
    private fun refreshAwareness() {
        val settings = settingsStore.current
        awareness.refresh(
            settings = settings,
            hasApiKey = secure.hasApiKey(providerIdFor(settings.baseUrl)),
            engine =
                EngineFacts(
                    created = handle != 0L,
                    running = engineStarted,
                    phase = _ui.value.phase.name.lowercase(),
                    // The engine exists without a key; only a stored key selects
                    // the remote backend. Phase 3 adds the local one.
                    backend =
                        when {
                            handle == 0L -> BackendKind.NONE
                            localBackendActive() -> BackendKind.LOCAL
                            secure.hasApiKey(activeProviderId) -> BackendKind.REMOTE
                            else -> BackendKind.NONE
                        },
                    providerLabel =
                        if (localBackendActive()) {
                            "On-device GGUF"
                        } else {
                            providerPresetFor(settings.baseUrl)?.label ?: "a custom endpoint"
                        },
                    model = if (localBackendActive()) settings.localModelName else settings.model,
                ),
            cloudMemoryConfigured =
                settings.githubToken.isNotBlank() &&
                    settings.githubOwner.isNotBlank() &&
                    settings.githubRepo.isNotBlank(),
        )
    }

    // -----------------------------------------------------------------------
    // Encrypted local memory
    // -----------------------------------------------------------------------

    fun refreshMemory() = memoryManager.refresh()

    fun searchMemory(query: String) = memoryManager.search(query)

    fun addMemory(
        text: String,
        kind: String,
    ) = memoryManager.add(text, kind)

    fun deleteMemory(id: String) = memoryManager.delete(id)

    fun clearMemory() = memoryManager.clear()

    /** Writes the exported records to a file the user explicitly picked. */
    fun exportMemory(uri: Uri) {
        scope.launch {
            val payload = withContext(Dispatchers.IO) { memoryManager.exportJson() }
            val written =
                withContext(Dispatchers.IO) {
                    runCatching {
                        app.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("the chosen file could not be opened")
                    }
                }
            written.onFailure { reportError("Memory export failed: ${it.message}") }
        }
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
        refreshAwareness()
    }

    fun setAutoSync(enabled: Boolean) {
        settingsStore.setAutoSync(enabled)
        conversationMemory.setAutoPush(enabled)
        refreshAwareness()
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
            language = SpeechLanguage.ttsTag(settings.spokenLanguage),
        )
    }

    /** Switches the spoken language and re-applies voice settings immediately. */
    fun setSpokenLanguage(choice: String) {
        settingsStore.setSpokenLanguage(choice)
        applyVoiceSettings(settingsStore.current)
        refreshAwareness()
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
        const val LISTEN_RESTART_MAX_MS = 2_000L
        const val FREEBUFF_PROVIDER_ID = "freebuff"
        const val RAM_RESERVE_MB = 512L
        const val MAX_MODEL_RAM_MB = 2_048L
    }
}
