package ai.jarvis.core

import ai.jarvis.audio.AudioCapture
import ai.jarvis.audio.VoiceEngine
import ai.jarvis.audio.VoiceInput
import ai.jarvis.data.AnimationMode
import ai.jarvis.data.JarvisSettings
import ai.jarvis.data.SecureStore
import ai.jarvis.data.SettingsStore
import ai.jarvis.data.VOICE_PRESETS
import ai.jarvis.data.VoiceInputMode
import ai.jarvis.data.VoicePreset
import ai.jarvis.data.presetFor
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

    val settings: StateFlow<JarvisSettings> = settingsStore.settings
    private val _ui = MutableStateFlow(JarvisUiState(needsSetup = !secure.hasApiKey))
    val ui: StateFlow<JarvisUiState> = _ui.asStateFlow()

    @Volatile
    private var handle: Long = 0L
    private var engineStarted = false
    private var capture: AudioCapture? = null
    private var serviceRunning = false
    private var undoAction: UndoAction? = null
    private var undoJob: Job? = null

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
                    _ui.update { it.copy(partial = "", phase = Phase.THINKING) }
                    appendLine(Speaker.USER, text)
                    val current = handle
                    if (current == 0L) {
                        reportError("The assistant engine is not ready.")
                        return
                    }
                    val code = JarvisNative.nativeSubmitText(current, text)
                    if (code != 0) reportError(codeMessage(code))
                }

                override fun onLevel(level: Float) {
                    _ui.update { it.copy(micRms = level) }
                }

                override fun onError(
                    message: String,
                    recoverable: Boolean,
                ) {
                    if (recoverable) {
                        _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
                    } else {
                        reportError(message)
                    }
                }

                override fun onEnded() {
                    if (_ui.value.phase == Phase.LISTENING) {
                        _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
                    }
                }
            },
        )

    init {
        applyVoiceSettings(settingsStore.current)
        ensureEngine()
    }

    // -----------------------------------------------------------------------
    // Engine lifecycle
    // -----------------------------------------------------------------------

    @Synchronized
    private fun ensureEngine(): Boolean {
        if (handle != 0L) return true
        if (!secure.hasApiKey) {
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
        _ui.update { it.copy(needsSetup = !secure.hasApiKey) }
        if (secure.hasApiKey) ensureEngine()
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
                        put("apiKey", secure.apiKey)
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
                        event
                            .optJSONObject("error")
                            ?.optString("detail")
                            ?.takeIf { it.isNotBlank() }
                            ?: "The assistant ran into a problem."
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
        if (!ensureEngine()) return
        appendLine(Speaker.USER, trimmed)
        _ui.update { it.copy(streaming = "", partial = "", phase = Phase.THINKING, error = null) }
        val current = handle
        if (current == 0L) {
            reportError("The assistant engine is not ready.")
            return
        }
        val code = JarvisNative.nativeSubmitText(current, trimmed)
        if (code != 0) reportError(codeMessage(code))
    }

    fun startListening(): Boolean {
        if (!ensureEngine()) return false
        if (!Permissions.granted(app, Permissions.MIC)) {
            _ui.update { it.copy(micPermissionNeeded = true) }
            return false
        }
        _ui.update { it.copy(micPermissionNeeded = false, error = null, partial = "") }
        JarvisService.start(app)
        haptics.tick()
        _ui.update { it.copy(phase = Phase.LISTENING) }
        return when (settingsStore.current.voiceInput) {
            VoiceInputMode.SYSTEM -> voiceInput.start()
            VoiceInputMode.ON_DEVICE -> startNativeCapture()
        }
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

    fun stopListening() {
        voiceInput.stop()
        capture?.stop()
        capture = null
        if (_ui.value.phase == Phase.LISTENING) {
            _ui.update { it.copy(phase = Phase.IDLE, micRms = 0f) }
        }
    }

    fun cancel() {
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

    fun onMicPermissionResult(granted: Boolean) {
        _ui.update { it.copy(micPermissionNeeded = false) }
        if (granted) {
            startListening()
        } else {
            reportError("Without the microphone, JARVIS works in text mode only.")
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
        val trusted = name in settingsStore.current.trustedTools
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
            JarvisNative.CODE_NOT_CONFIGURED -> "Add a Qwen API key in Settings."
            JarvisNative.CODE_PERMISSION_DENIED -> "Android denied a permission JARVIS needs."
            JarvisNative.CODE_NETWORK -> "Qwen could not be reached. Check the connection and endpoint."
            JarvisNative.CODE_MODEL -> "The model or its local files are unusable."
            JarvisNative.CODE_AUDIO -> "The audio device failed."
            JarvisNative.CODE_CANCELLED -> "Cancelled."
            else -> "The assistant failed with code $code."
        }

    private fun capabilityLabel(name: String): String =
        when (name) {
            "call_contact", "answer_call", "end_call" -> "Calling…"
            "send_sms", "send_whatsapp" -> "Messaging…"
            "search_contacts", "get_contact_number" -> "Looking up…"
            "search_web", "open_url", "navigate_to", "find_nearby" -> "Searching…"
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

    fun saveApiKey(key: String) {
        secure.apiKey = key.trim()
        settingsStore.setApiKeyPresent(secure.hasApiKey)
        rebuildEngine()
    }

    fun saveEndpoint(
        baseUrl: String,
        model: String,
        fallback: String,
    ) {
        settingsStore.setEndpoint(baseUrl, model, fallback)
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

    fun setWakeSensitivity(value: Float) = settingsStore.setWakeSensitivity(value)

    fun setWebhook(url: String) = settingsStore.setWebhook(url)

    fun setTrusted(
        toolName: String,
        trusted: Boolean,
    ) = settingsStore.setTrusted(toolName, trusted)

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
        )
    }

    private companion object {
        const val TAG = "JarvisController"
        const val UNDO_WINDOW_MS = 5_000L
    }
}
