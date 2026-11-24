package ai.jarvis.awareness

import ai.jarvis.BuildConfig
import ai.jarvis.audio.preferredRecognitionService
import ai.jarvis.data.JarvisSettings
import ai.jarvis.data.VoiceInputMode
import ai.jarvis.permissions.Permissions
import ai.jarvis.tools.CapabilityStatus as ToolCapability
import ai.jarvis.tools.ToolRegistry
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Collects the Android-side facts and keeps one [IdentitySnapshot] current.
 *
 * This class only probes the platform; all decision rules live in
 * [IdentityComposer] so they can be tested without an Android runtime. The
 * probe is intentionally cheap (permissions, connectivity, package queries and
 * a directory listing), and it is called on user-visible transitions and when
 * the prompt is assembled, never per audio frame.
 */
class AwarenessRepository(
    context: Context,
    private val registry: ToolRegistry,
) {
    private val app: Context = context.applicationContext
    private val _identity = MutableStateFlow(initial())

    /** Latest self-model, for the Settings view and the awareness surface. */
    val identity: StateFlow<IdentitySnapshot> = _identity.asStateFlow()

    /** Latest snapshot without collecting the flow (used while building a prompt). */
    val current: IdentitySnapshot get() = _identity.value

    fun refresh(
        settings: JarvisSettings,
        hasApiKey: Boolean,
        engine: EngineFacts,
        cloudMemoryConfigured: Boolean,
    ): IdentitySnapshot {
        val snapshot =
            IdentityComposer.compose(
                platformFacts(settings, hasApiKey, cloudMemoryConfigured),
                engine,
            )
        _identity.value = snapshot
        return snapshot
    }

    private fun initial(): IdentitySnapshot =
        IdentityComposer.compose(
            PlatformFacts(
                appVersion = BuildConfig.VERSION_NAME,
                coreVersion = BuildConfig.JARVIS_CORE_VERSION,
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                deviceModel = Build.MODEL.orEmpty(),
            ),
            EngineFacts(),
        )

    private fun platformFacts(
        settings: JarvisSettings,
        hasApiKey: Boolean,
        cloudMemoryConfigured: Boolean,
    ): PlatformFacts =
        PlatformFacts(
            appVersion = BuildConfig.VERSION_NAME,
            coreVersion = BuildConfig.JARVIS_CORE_VERSION,
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            microphoneGranted = Permissions.granted(app, Permissions.MIC),
            notificationAccess = Permissions.hasNotificationAccess(app),
            accessibilityEnabled = Permissions.hasAccessibility(app),
            writeSettingsAllowed = Permissions.canWriteSettings(app),
            dndAccess = Permissions.hasDndAccess(app),
            recognitionAvailable = recognitionAvailable(),
            ttsAvailable = ttsAvailable(),
            networkAvailable = networkAvailable(),
            localModelFiles = localModelFiles(),
            tools =
                registry.all
                    .sortedBy { it.spec.name }
                    .map { tool ->
                        ToolFact(
                            name = tool.spec.name,
                            label = tool.spec.name.replace('_', ' '),
                            status = toolStatus(Permissions.status(app, tool)),
                        )
                    },
            behaviour =
                BehaviourFacts(
                    apiKeyPresent = hasApiKey,
                    wakeWordEnabled = settings.wakeWordEnabled,
                    voiceInputOnDevice = settings.voiceInput == VoiceInputMode.ON_DEVICE,
                    cloudMemoryConfigured = cloudMemoryConfigured,
                ),
        )

    /** Same check VoiceInput uses before it opens a session. */
    private fun recognitionAvailable(): Boolean =
        preferredRecognitionService(app) != null || SpeechRecognizer.isRecognitionAvailable(app)

    /** Whether a TTS service is installed at all; voice data is checked at speak time. */
    private fun ttsAvailable(): Boolean =
        runCatching {
            app.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .isNotEmpty()
        }.getOrDefault(false)

    private fun networkAvailable(): Boolean =
        try {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            false
        }

    /** GGUF files in the app's private model directory, if any. */
    private fun localModelFiles(): List<String> =
        runCatching {
            File(app.filesDir, "models")
                .listFiles { file -> file.isFile && file.name.endsWith(".gguf", ignoreCase = true) }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun toolStatus(status: ToolCapability): CapabilityStatus =
        when (status) {
            ToolCapability.GRANTED -> CapabilityStatus.AVAILABLE
            ToolCapability.NEEDS_GRANT -> CapabilityStatus.NEEDS_PERMISSION
            ToolCapability.BLOCKED -> CapabilityStatus.DISABLED
        }
}
