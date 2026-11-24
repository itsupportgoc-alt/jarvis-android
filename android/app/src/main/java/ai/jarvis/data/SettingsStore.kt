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

/** Whether the host has a standing free allowance or is pay-as-you-go. */
enum class ProviderTier { FREE, PAID }

/**
 * A known OpenAI-compatible host. A preset only fills the endpoint fields; the
 * API key is always entered by the user and kept in the Android Keystore.
 *
 * Every entry speaks `POST {baseUrl}/chat/completions` with a bearer token, so
 * one client works for all of them. Model IDs are only defaults: the settings
 * screen lists the ones a key is actually allowed to use via `GET /models`.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val fallback: String,
    val tier: ProviderTier,
    val consoleUrl: String,
    val note: String,
)

val PROVIDER_PRESETS: List<ProviderPreset> =
    listOf(
        // ------------------------------------------------------------------
        // Free tiers (key required, usage is free or credit-backed)
        // ------------------------------------------------------------------
        ProviderPreset(
            id = "groq",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
            fallback = "llama-3.1-8b-instant",
            tier = ProviderTier.FREE,
            consoleUrl = "https://console.groq.com/keys",
            note = "Free tier with a very low latency, hosted Llama and Qwen.",
        ),
        ProviderPreset(
            id = "cerebras",
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            model = "llama-3.3-70b",
            fallback = "llama3.1-8b",
            tier = ProviderTier.FREE,
            consoleUrl = "https://cloud.cerebras.ai/",
            note = "Free tier; fastest first-token times of the hosted providers.",
        ),
        ProviderPreset(
            id = "gemini",
            label = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
            model = "gemini-2.0-flash",
            fallback = "gemini-1.5-flash-8b",
            tier = ProviderTier.FREE,
            consoleUrl = "https://aistudio.google.com/apikey",
            note = "Google AI Studio free tier through the OpenAI-compatible layer.",
        ),
        ProviderPreset(
            id = "mistral",
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            model = "mistral-small-latest",
            fallback = "open-mistral-nemo",
            tier = ProviderTier.FREE,
            consoleUrl = "https://console.mistral.ai/api-keys",
            note = "Free experimental tier, then pay-as-you-go.",
        ),
        ProviderPreset(
            id = "githubmodels",
            label = "GitHub Models",
            baseUrl = "https://models.github.ai/inference",
            model = "openai/gpt-4o-mini",
            fallback = "openai/gpt-4o",
            tier = ProviderTier.FREE,
            consoleUrl = "https://github.com/settings/tokens",
            note = "Free with a GitHub personal access token (models:read scope).",
        ),
        ProviderPreset(
            id = "nvidia",
            label = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            model = "meta/llama-3.1-70b-instruct",
            fallback = "meta/llama-3.1-8b-instruct",
            tier = ProviderTier.FREE,
            consoleUrl = "https://build.nvidia.com/",
            note = "Free credits; NVIDIA keys start with nvapi-.",
        ),
        ProviderPreset(
            id = "siliconflow",
            label = "SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen2.5-7B-Instruct",
            fallback = "Qwen/Qwen2.5-72B-Instruct",
            tier = ProviderTier.FREE,
            consoleUrl = "https://cloud.siliconflow.cn/account/ak",
            note = "Free Qwen and Llama endpoints with a Chinese account.",
        ),
        ProviderPreset(
            id = "sambanova",
            label = "SambaNova",
            baseUrl = "https://api.sambanova.ai/v1",
            model = "Meta-Llama-3.3-70B-Instruct",
            fallback = "Meta-Llama-3.1-8B-Instruct",
            tier = ProviderTier.FREE,
            consoleUrl = "https://cloud.sambanova.ai/apis",
            note = "Free tier for hosted Llama models.",
        ),
        ProviderPreset(
            id = "huggingface",
            label = "Hugging Face",
            baseUrl = "https://router.huggingface.co/v1",
            model = "meta-llama/Llama-3.1-8B-Instruct",
            fallback = "Qwen/Qwen2.5-7B-Instruct",
            tier = ProviderTier.FREE,
            consoleUrl = "https://huggingface.co/settings/tokens",
            note = "Inference Providers router; monthly free credits.",
        ),
        ProviderPreset(
            id = "zhipu",
            label = "Zhipu GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4-flash",
            fallback = "glm-4-plus",
            tier = ProviderTier.FREE,
            consoleUrl = "https://open.bigmodel.cn/usercenter/apikeys",
            note = "glm-4-flash is free; glm-4-plus is metered.",
        ),
        ProviderPreset(
            id = "dashscope",
            label = "DashScope (Qwen)",
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            model = "qwen3-30b-a3b",
            fallback = "qwen2.5-14b-instruct",
            tier = ProviderTier.FREE,
            consoleUrl = "https://bailian.console.aliyun.com/",
            note = "Alibaba Model Studio; free quota per model, then metered.",
        ),
        // ------------------------------------------------------------------
        // Paid / bring your own key
        // ------------------------------------------------------------------
        ProviderPreset(
            id = "freebuff",
            label = "Freebuff API",
            baseUrl = "https://freebuff.com/api/v1/paid",
            // IDs come from the live catalog at GET /api/v1/paid/models: no
            // vendor prefix, and only deepseek-v4.1-flash advertises streaming
            // plus tool calls, which the Rust client needs.
            model = "deepseek-v4.1-flash",
            fallback = "minimax-m3",
            tier = ProviderTier.PAID,
            consoleUrl = "https://freebuff.com/account/api",
            note = "First-party Freebuff API, preset as the default endpoint.",
        ),
        ProviderPreset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            fallback = "gpt-4o",
            tier = ProviderTier.PAID,
            consoleUrl = "https://platform.openai.com/api-keys",
            note = "First-party OpenAI key.",
        ),
        ProviderPreset(
            id = "anthropic",
            label = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            model = "claude-3-7-sonnet-latest",
            fallback = "claude-3-5-haiku-latest",
            tier = ProviderTier.PAID,
            consoleUrl = "https://console.anthropic.com/settings/keys",
            note = "Claude through Anthropic's OpenAI-compatible endpoint.",
        ),
        ProviderPreset(
            id = "xai",
            label = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            model = "grok-3-mini",
            fallback = "grok-3",
            tier = ProviderTier.PAID,
            consoleUrl = "https://console.x.ai/",
            note = "xAI Grok key.",
        ),
        ProviderPreset(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            fallback = "deepseek-reasoner",
            tier = ProviderTier.PAID,
            consoleUrl = "https://platform.deepseek.com/api_keys",
            note = "Cheap first-party DeepSeek key.",
        ),
        ProviderPreset(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openrouter/auto",
            fallback = "openai/gpt-4o-mini",
            tier = ProviderTier.PAID,
            consoleUrl = "https://openrouter.ai/keys",
            note = "One key for hundreds of models; the :free variants cost nothing.",
        ),
        ProviderPreset(
            id = "together",
            label = "Together",
            baseUrl = "https://api.together.xyz/v1",
            model = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            fallback = "Qwen/Qwen2.5-72B-Instruct-Turbo",
            tier = ProviderTier.PAID,
            consoleUrl = "https://api.together.ai/settings/api-keys",
            note = "Hosted open-weight models.",
        ),
        ProviderPreset(
            id = "fireworks",
            label = "Fireworks",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            model = "accounts/fireworks/models/llama-v3p3-70b-instruct",
            fallback = "accounts/fireworks/models/qwen2p5-72b-instruct",
            tier = ProviderTier.PAID,
            consoleUrl = "https://fireworks.ai/account/api-keys",
            note = "Hosted open-weight models; model IDs are full account paths.",
        ),
        ProviderPreset(
            id = "perplexity",
            label = "Perplexity",
            baseUrl = "https://api.perplexity.ai",
            model = "sonar",
            fallback = "sonar-pro",
            tier = ProviderTier.PAID,
            consoleUrl = "https://www.perplexity.ai/settings/api",
            note = "Search-grounded Sonar models; chat completions only.",
        ),
        ProviderPreset(
            id = "cohere",
            label = "Cohere",
            baseUrl = "https://api.cohere.ai/compatibility/v1",
            model = "command-r-plus-08-2024",
            fallback = "command-r-08-2024",
            tier = ProviderTier.PAID,
            consoleUrl = "https://dashboard.cohere.com/api-keys",
            note = "Command models via Cohere's compatibility layer.",
        ),
        ProviderPreset(
            id = "moonshot",
            label = "Moonshot Kimi",
            baseUrl = "https://api.moonshot.ai/v1",
            model = "moonshot-v1-8k",
            fallback = "moonshot-v1-32k",
            tier = ProviderTier.PAID,
            consoleUrl = "https://platform.moonshot.ai/console/api-keys",
            note = "Kimi models.",
        ),
        ProviderPreset(
            id = "novita",
            label = "Novita AI",
            baseUrl = "https://api.novita.ai/v3/openai",
            model = "meta-llama/llama-3.3-70b-instruct",
            fallback = "deepseek/deepseek-v3",
            tier = ProviderTier.PAID,
            consoleUrl = "https://novita.ai/settings/key-management",
            note = "Open-weight models, some with a free trial quota.",
        ),
        ProviderPreset(
            id = "hyperbolic",
            label = "Hyperbolic",
            baseUrl = "https://api.hyperbolic.xyz/v1",
            model = "meta-llama/Llama-3.3-70B-Instruct",
            fallback = "Qwen/Qwen2.5-72B-Instruct",
            tier = ProviderTier.PAID,
            consoleUrl = "https://app.hyperbolic.xyz/settings",
            note = "Open-weight models.",
        ),
        ProviderPreset(
            id = "deepinfra",
            label = "DeepInfra",
            baseUrl = "https://api.deepinfra.com/v1/openai",
            model = "meta-llama/Llama-3.3-70B-Instruct",
            fallback = "Qwen/Qwen2.5-72B-Instruct",
            tier = ProviderTier.PAID,
            consoleUrl = "https://deepinfra.com/dash/api_keys",
            note = "Open-weight models billed per token.",
        ),
        ProviderPreset(
            id = "nebius",
            label = "Nebius",
            baseUrl = "https://api.studio.nebius.ai/v1",
            model = "meta-llama/Llama-3.3-70B-Instruct",
            fallback = "Qwen/Qwen2.5-72B-Instruct",
            tier = ProviderTier.PAID,
            consoleUrl = "https://studio.nebius.ai/",
            note = "AI Studio keys; some free credits on signup.",
        ),
        ProviderPreset(
            id = "vercel",
            label = "Vercel AI",
            baseUrl = "https://ai-gateway.vercel.sh/v1",
            model = "openai/gpt-4o-mini",
            fallback = "anthropic/claude-3-5-sonnet",
            tier = ProviderTier.PAID,
            consoleUrl = "https://vercel.com/dashboard/ai-gateway",
            note = "Gateway key covering many vendors with one bill.",
        ),
        ProviderPreset(
            id = "blackpig",
            label = "BlackPig relay",
            baseUrl = "https://api.vv666.ai/v1",
            model = "gpt-6.1-sol",
            fallback = "gpt-5.6-sol",
            tier = ProviderTier.PAID,
            consoleUrl = "https://blackpig.vip/api/console",
            note = "Third-party relay; the open API needs a completed recharge.",
        ),
    )

/** The preset whose endpoint the current settings point at, if any. */
fun providerPresetFor(baseUrl: String): ProviderPreset? {
    val normalized = baseUrl.trim().trimEnd('/')
    return PROVIDER_PRESETS.firstOrNull { it.baseUrl.trimEnd('/').equals(normalized, ignoreCase = true) }
}

/** Key used when the endpoint is typed by hand rather than chosen from the list. */
const val CUSTOM_PROVIDER_ID = "custom"

fun providerIdFor(baseUrl: String): String = providerPresetFor(baseUrl)?.id ?: CUSTOM_PROVIDER_ID

data class JarvisSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val fallbackModel: String = DEFAULT_FALLBACK_MODEL,
    val voicePresetId: String = "jarvis",
    /** 1.0 is the engine's normal talking speed. */
    val rate: Float = 1.0f,
    val pitch: Float = 0.85f,
    val volume: Float = 0.9f,
    /** Prefer a male system voice; JARVIS is written as male. */
    val maleVoice: Boolean = true,
    val animationMode: AnimationMode = AnimationMode.ORB,
    val voiceInput: VoiceInputMode = VoiceInputMode.SYSTEM,
    val trustedTools: Set<String> = emptySet(),
    val wakeSensitivity: Float = 0.6f,
    /** Keep the microphone open for the "Jarvis" wake phrase. */
    val wakeWordEnabled: Boolean = false,
    val webhookUrl: String = "",
    val hasApiKey: Boolean = false,
    /** Auto-confirm messaging tools so the user doesn't need to tap confirm. */
    val autoSendMessages: Boolean = false,
    /** Give the model a persistent self-model and continuity (self-aware persona). */
    val selfAware: Boolean = true,
    /** Prefer an imported GGUF through llama.cpp over the remote endpoint. */
    val localBackend: Boolean = false,
    val localModelPath: String = "",
    val localModelName: String = "",
    // GitHub sync config
    val githubToken: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    /** Mirror conversation turns to GitHub. Off by default; local memory is primary. */
    val autoSync: Boolean = false,
) {
    companion object {
        /** Freebuff's OpenAI-compatible API is the default endpoint. */
        const val DEFAULT_BASE_URL = "https://freebuff.com/api/v1/paid"
        const val DEFAULT_MODEL = "deepseek-v4.1-flash"
        const val DEFAULT_FALLBACK_MODEL = "minimax-m3"

        /** The platform TTS engine's own normal speaking rate. */
        const val NORMAL_RATE = 1.0f
    }
}

class SettingsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<JarvisSettings> = _settings.asStateFlow()

    val current: JarvisSettings get() = _settings.value

    private fun read(): JarvisSettings {
        normaliseRateOnce()
        return JarvisSettings(
            baseUrl =
                prefs.getString("base_url", JarvisSettings.DEFAULT_BASE_URL)
                    ?: JarvisSettings.DEFAULT_BASE_URL,
            model = prefs.getString("model", JarvisSettings.DEFAULT_MODEL) ?: JarvisSettings.DEFAULT_MODEL,
            fallbackModel =
                prefs.getString("fallback_model", JarvisSettings.DEFAULT_FALLBACK_MODEL)
                    ?: JarvisSettings.DEFAULT_FALLBACK_MODEL,
            voicePresetId = prefs.getString("voice_preset", "jarvis") ?: "jarvis",
            rate = prefs.getFloat("rate", JarvisSettings.NORMAL_RATE),
            pitch = prefs.getFloat("pitch", 0.85f),
            volume = prefs.getFloat("volume", 0.9f),
            maleVoice = prefs.getBoolean("male_voice", true),
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
            wakeWordEnabled = prefs.getBoolean("wake_word_enabled", false),
            webhookUrl = prefs.getString("webhook_url", "") ?: "",
            hasApiKey = prefs.getBoolean("has_api_key", false),
            autoSendMessages = prefs.getBoolean("auto_send_messages", false),
            selfAware = prefs.getBoolean("self_aware", true),
            localBackend = prefs.getBoolean("local_backend", false),
            localModelPath = prefs.getString("local_model_path", "") ?: "",
            localModelName = prefs.getString("local_model_name", "") ?: "",
            githubToken = prefs.getString("github_token", "") ?: "",
            githubOwner = prefs.getString("github_owner", "") ?: "",
            githubRepo = prefs.getString("github_repo", "") ?: "",
            autoSync = prefs.getBoolean("github_auto_sync", false),
        )
    }

    /**
     * Earlier builds shipped a sub-1.0 rate (0.94-0.95), which makes every
     * reply sound drawled. A stored rate below normal speed is lifted to 1.0
     * once, so an existing install actually speaks at normal speed; the slider
     * still works normally afterwards.
     */
    private fun normaliseRateOnce() {
        if (prefs.getBoolean("rate_normalised", false)) return
        val stored = prefs.getFloat("rate", JarvisSettings.NORMAL_RATE)
        prefs
            .edit()
            .putFloat("rate", stored.coerceAtLeast(JarvisSettings.NORMAL_RATE))
            .putBoolean("rate_normalised", true)
            .apply()
    }

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
            .putBoolean("male_voice", next.maleVoice)
            .putString("animation_mode", next.animationMode.name)
            .putString("voice_input", next.voiceInput.name)
            .putStringSet("trusted_tools", next.trustedTools)
            .putFloat("wake_sensitivity", next.wakeSensitivity)
            .putBoolean("wake_word_enabled", next.wakeWordEnabled)
            .putString("webhook_url", next.webhookUrl)
            .putBoolean("has_api_key", next.hasApiKey)
            .putBoolean("auto_send_messages", next.autoSendMessages)
            .putBoolean("self_aware", next.selfAware)
            .putBoolean("local_backend", next.localBackend)
            .putString("local_model_path", next.localModelPath)
            .putString("local_model_name", next.localModelName)
            .putString("github_token", next.githubToken)
            .putString("github_owner", next.githubOwner)
            .putString("github_repo", next.githubRepo)
            .putBoolean("github_auto_sync", next.autoSync)
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

    fun setMaleVoice(enabled: Boolean) = update { it.copy(maleVoice = enabled) }

    fun setAnimationMode(mode: AnimationMode) = update { it.copy(animationMode = mode) }

    fun setVoiceInput(mode: VoiceInputMode) = update { it.copy(voiceInput = mode) }

    fun setWakeSensitivity(value: Float) = update { it.copy(wakeSensitivity = value) }

    fun setWakeWordEnabled(enabled: Boolean) = update { it.copy(wakeWordEnabled = enabled) }

    fun setWebhook(url: String) = update { it.copy(webhookUrl = url.trim()) }

    fun setGitHubConfig(token: String, owner: String, repo: String) = update {
        it.copy(githubToken = token.trim(), githubOwner = owner.trim(), githubRepo = repo.trim())
    }

    fun setAutoSync(enabled: Boolean) = update { it.copy(autoSync = enabled) }

    fun setAutoSendMessages(enabled: Boolean) = update { it.copy(autoSendMessages = enabled) }

    fun setSelfAware(enabled: Boolean) = update { it.copy(selfAware = enabled) }

    fun setLocalBackend(enabled: Boolean) = update { it.copy(localBackend = enabled) }

    fun setLocalModel(
        path: String,
        name: String,
    ) = update { it.copy(localModelPath = path, localModelName = name) }

    fun setTrusted(
        tool: String,
        trusted: Boolean,
    ) = update {
        it.copy(trustedTools = if (trusted) it.trustedTools + tool else it.trustedTools - tool)
    }
}
