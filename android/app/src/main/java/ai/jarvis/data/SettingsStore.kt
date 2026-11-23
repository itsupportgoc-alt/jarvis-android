package ai.jarvis.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AnimationMode { ORB, FACE }

enum class VoiceInputMode {
    /** Android SpeechRecognizer: works out of the box, on-device where available. */
    SYSTEM,

    /** Rust VAD + Whisper build. Requires `--features whisper` and a ggml model. */
    ON_DEVICE,
}

/**
 * A known OpenAI-compatible host. A preset only fills the endpoint fields; the
 * API key is always entered by the user and kept in the Android Keystore.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val fallback: String,
)

val PROVIDER_PRESETS =
    listOf(
        ProviderPreset(
            id = "blackpig",
            label = "BlackPig relay",
            baseUrl = "https://api.vv666.ai/v1",
            model = "gpt-6.1-sol",
            fallback = "gpt-5.6-sol",
        ),
        ProviderPreset(
            id = "dashscope",
            label = "DashScope (Qwen)",
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            model = "qwen3-30b-a3b",
            fallback = "qwen2.5-14b-instruct",
        ),
    )

data class JarvisSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val fallbackModel: String = DEFAULT_FALLBACK_MODEL,
    val voicePresetId: String = "jarvis",
    val rate: Float = 0.95f,
    val pitch: Float = 0.85f,
    val volume: Float = 0.9f,
    val animationMode: AnimationMode = AnimationMode.ORB,
    val voiceInput: VoiceInputMode = VoiceInputMode.SYSTEM,
    val trustedTools: Set<String> = emptySet(),
    val wakeSensitivity: Float = 0.6f,
    val webhookUrl: String = "",
    val hasApiKey: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.vv666.ai/v1"
        const val DEFAULT_MODEL = "gpt-6.1-sol"
        const val DEFAULT_FALLBACK_MODEL = "gpt-5.6-sol"
    }
}

class SettingsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<JarvisSettings> = _settings.asStateFlow()

    val current: JarvisSettings get() = _settings.value

    private fun read() =
        JarvisSettings(
            baseUrl =
                prefs.getString("base_url", JarvisSettings.DEFAULT_BASE_URL)
                    ?: JarvisSettings.DEFAULT_BASE_URL,
            model = prefs.getString("model", JarvisSettings.DEFAULT_MODEL) ?: JarvisSettings.DEFAULT_MODEL,
            fallbackModel =
                prefs.getString("fallback_model", JarvisSettings.DEFAULT_FALLBACK_MODEL)
                    ?: JarvisSettings.DEFAULT_FALLBACK_MODEL,
            voicePresetId = prefs.getString("voice_preset", "jarvis") ?: "jarvis",
            rate = prefs.getFloat("rate", 0.95f),
            pitch = prefs.getFloat("pitch", 0.85f),
            volume = prefs.getFloat("volume", 0.9f),
            animationMode =
                runCatching {
                    AnimationMode.valueOf(prefs.getString("animation_mode", "ORB") ?: "ORB")
                }.getOrDefault(AnimationMode.ORB),
            voiceInput =
                runCatching {
                    VoiceInputMode.valueOf(prefs.getString("voice_input", "SYSTEM") ?: "SYSTEM")
                }.getOrDefault(VoiceInputMode.SYSTEM),
            trustedTools = prefs.getStringSet("trusted_tools", emptySet()) ?: emptySet(),
            wakeSensitivity = prefs.getFloat("wake_sensitivity", 0.6f),
            webhookUrl = prefs.getString("webhook_url", "") ?: "",
            hasApiKey = prefs.getBoolean("has_api_key", false),
        )

    private fun update(transform: (JarvisSettings) -> JarvisSettings) {
        val next = transform(_settings.value)
        prefs
            .edit()
            .putString("base_url", next.baseUrl)
            .putString("model", next.model)
            .putString("fallback_model", next.fallbackModel)
            .putString("voice_preset", next.voicePresetId)
            .putFloat("rate", next.rate)
            .putFloat("pitch", next.pitch)
            .putFloat("volume", next.volume)
            .putString("animation_mode", next.animationMode.name)
            .putString("voice_input", next.voiceInput.name)
            .putStringSet("trusted_tools", next.trustedTools)
            .putFloat("wake_sensitivity", next.wakeSensitivity)
            .putString("webhook_url", next.webhookUrl)
            .putBoolean("has_api_key", next.hasApiKey)
            .apply()
        _settings.value = next
    }

    fun setApiKeyPresent(present: Boolean) = update { it.copy(hasApiKey = present) }

    fun setEndpoint(
        baseUrl: String,
        model: String,
        fallback: String,
    ) = update { it.copy(baseUrl = baseUrl.trim(), model = model.trim(), fallbackModel = fallback.trim()) }

    fun setVoice(
        presetId: String,
        rate: Float,
        pitch: Float,
        volume: Float,
    ) = update { it.copy(voicePresetId = presetId, rate = rate, pitch = pitch, volume = volume) }

    fun setAnimationMode(mode: AnimationMode) = update { it.copy(animationMode = mode) }

    fun setVoiceInput(mode: VoiceInputMode) = update { it.copy(voiceInput = mode) }

    fun setWakeSensitivity(value: Float) = update { it.copy(wakeSensitivity = value) }

    fun setWebhook(url: String) = update { it.copy(webhookUrl = url.trim()) }

    fun setTrusted(
        tool: String,
        trusted: Boolean,
    ) = update {
        it.copy(trustedTools = if (trusted) it.trustedTools + tool else it.trustedTools - tool)
    }
}
