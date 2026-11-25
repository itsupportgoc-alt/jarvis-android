package ai.jarvis.awareness

/**
 * The single source of truth for JARVIS' identity.
 *
 * The values here are facts: the installed app version, the compiled core
 * version, the selected endpoint and the capabilities the app could verify.
 * They are not personality prompts, and nothing in this file may claim a
 * capability before it has been checked.
 */
object Identity {
    const val NAME = "SEVEN"
    const val CREATOR = "John Lester Macabulos"
    const val OWNER_NAME = "Lester"
}

enum class BackendKind { REMOTE, LOCAL, NONE }

/** One registered tool and the permission state it currently has. */
data class ToolFact(
    val name: String,
    val label: String,
    val status: CapabilityStatus,
)

/** Settings that change what is actually reachable. */
data class BehaviourFacts(
    val apiKeyPresent: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val voiceInputOnDevice: Boolean = false,
    val cloudMemoryConfigured: Boolean = false,
)

/**
 * Facts Android can verify. Kept free of Android types so the composer and the
 * prompt stay unit-testable on the JVM.
 */
data class PlatformFacts(
    val appVersion: String,
    val coreVersion: String,
    val androidVersion: String = "",
    val deviceModel: String = "",
    val microphoneGranted: Boolean = false,
    val notificationAccess: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val writeSettingsAllowed: Boolean = false,
    val dndAccess: Boolean = false,
    val recognitionAvailable: Boolean = false,
    val ttsAvailable: Boolean = false,
    val networkAvailable: Boolean = false,
    val localModelFiles: List<String> = emptyList(),
    /** Whether this APK was compiled with the optional llama.cpp backend. */
    val localInferenceCompiled: Boolean = false,
    val tools: List<ToolFact> = emptyList(),
    val behaviour: BehaviourFacts = BehaviourFacts(),
)

/** What the native engine reports about itself. */
data class EngineFacts(
    val created: Boolean = false,
    val running: Boolean = false,
    val phase: String = "idle",
    val backend: BackendKind = BackendKind.NONE,
    val providerLabel: String = "",
    val model: String = "",
)

/**
 * One immutable snapshot of who JARVIS is right now.
 *
 * The prompt, the Settings sheet and (later) the awareness dashboard all read
 * this same object, so a capability cannot be described two different ways.
 */
data class IdentitySnapshot(
    val appVersion: String,
    val coreVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val backend: BackendKind,
    val providerLabel: String,
    val model: String,
    val engineCreated: Boolean,
    val engineRunning: Boolean,
    val phase: String,
    val registry: CapabilityRegistry,
    val tools: List<ToolFact>,
    val limitations: List<String>,
    val generatedAt: Long,
) {
    val name: String get() = Identity.NAME
    val creator: String get() = Identity.CREATOR
    val ownerName: String get() = Identity.OWNER_NAME

    val availableCount: Int get() = registry.available().size

    fun toolUsableCount(): Int = tools.count { it.status.isAvailable }

    fun capability(id: String): Capability? = registry.byId(id)

    fun isAvailable(id: String): Boolean = registry.isAvailable(id)
}

/**
 * Builds an [IdentitySnapshot] from verified facts.
 *
 * Rules enforced here (and covered by tests):
 * * a missing permission is always [CapabilityStatus.NEEDS_PERMISSION], never
 *   available;
 * * no local model is ever reported available while local inference is not
 *   compiled into the build;
 * * remote reasoning needs both a key and a network;
 * * disabled features are disabled, not broken;
 * * limitations are derived from the same facts as the capabilities.
 */
object IdentityComposer {
    private const val MAX_LIMITATIONS = 6

    fun compose(
        platform: PlatformFacts,
        engine: EngineFacts,
        now: Long = System.currentTimeMillis(),
    ): IdentitySnapshot {
        val caps = buildList {
            addAll(reasoning(platform, engine))
            addAll(voice(platform))
            addAll(access(platform))
            add(deviceTools(platform))
            addAll(notImplemented())
        }
        val registry = CapabilityRegistry(caps)
        return IdentitySnapshot(
            appVersion = platform.appVersion,
            coreVersion = platform.coreVersion,
            androidVersion = platform.androidVersion,
            deviceModel = platform.deviceModel,
            backend = engine.backend,
            providerLabel = engine.providerLabel,
            model = engine.model,
            engineCreated = engine.created,
            engineRunning = engine.running,
            phase = engine.phase,
            registry = registry,
            tools = platform.tools,
            limitations = limitations(platform, engine, registry),
            generatedAt = now,
        )
    }

    private fun reasoning(
        platform: PlatformFacts,
        engine: EngineFacts,
    ): List<Capability> {
        val provider = engine.providerLabel.ifBlank { "the configured endpoint" }
        val model = engine.model.ifBlank { "the configured model" }
        val remote =
            when {
                !platform.behaviour.apiKeyPresent ->
                    Capability(
                        CapabilityIds.REMOTE_REASONING,
                        "remote reasoning",
                        CapabilityStatus.UNAVAILABLE,
                        requirement = "an API key",
                        detail = "No API key is configured.",
                    )
                !platform.networkAvailable ->
                    Capability(
                        CapabilityIds.REMOTE_REASONING,
                        "remote reasoning",
                        CapabilityStatus.UNAVAILABLE,
                        requirement = "a network connection",
                        detail = "No network connection is available, so the remote endpoint cannot be reached.",
                    )
                else ->
                    Capability(
                        CapabilityIds.REMOTE_REASONING,
                        "remote reasoning",
                        CapabilityStatus.AVAILABLE,
                        detail = "OpenAI-compatible model $model via $provider.",
                    )
            }

        val installedModel = platform.localModelFiles.firstOrNull()
        val local =
            when {
                engine.backend == BackendKind.LOCAL && engine.running ->
                    Capability(
                        CapabilityIds.LOCAL_REASONING,
                        "local reasoning",
                        CapabilityStatus.AVAILABLE,
                        detail = "Local model ${engine.model.ifBlank { installedModel ?: "loaded" }} is running.",
                    )
                installedModel != null && platform.localInferenceCompiled ->
                    Capability(
                        CapabilityIds.LOCAL_REASONING,
                        "local reasoning",
                        CapabilityStatus.DISABLED,
                        requirement = "local reasoning switched on in Settings",
                        detail =
                            "A GGUF model ($installedModel) is installed and llama.cpp is compiled in, " +
                                "but local reasoning is currently switched off.",
                    )
                installedModel != null ->
                    Capability(
                        CapabilityIds.LOCAL_REASONING,
                        "local reasoning",
                        CapabilityStatus.UNAVAILABLE,
                        requirement = "a local inference engine",
                        detail =
                            "A GGUF model ($installedModel) is installed, but local inference is not " +
                                "compiled into this build.",
                    )
                else ->
                    Capability(
                        CapabilityIds.LOCAL_REASONING,
                        "local reasoning",
                        CapabilityStatus.NEEDS_MODEL,
                        requirement = "a GGUF model",
                        detail =
                            if (platform.localInferenceCompiled) {
                                "No GGUF model is imported; this build has the local llama.cpp backend."
                            } else {
                                "No local model is installed, and local inference is not compiled into this build."
                            },
                    )
            }
        return listOf(remote, local)
    }

    private fun voice(platform: PlatformFacts): List<Capability> {
        val input =
            when {
                !platform.microphoneGranted ->
                    Capability(
                        CapabilityIds.VOICE_INPUT,
                        "voice input",
                        CapabilityStatus.NEEDS_PERMISSION,
                        requirement = "microphone permission",
                        detail = "Microphone permission has not been granted.",
                    )
                platform.behaviour.voiceInputOnDevice ->
                    Capability(
                        CapabilityIds.VOICE_INPUT,
                        "voice input",
                        CapabilityStatus.UNAVAILABLE,
                        requirement = "an on-device recogniser",
                        detail =
                            "On-device capture is selected, but no on-device recogniser is compiled " +
                                "into this build. Select the system recogniser instead.",
                    )
                !platform.recognitionAvailable ->
                    Capability(
                        CapabilityIds.VOICE_INPUT,
                        "voice input",
                        CapabilityStatus.UNAVAILABLE,
                        requirement = "a speech recognition service",
                        detail = "No speech recognition service is installed on this device.",
                    )
                else ->
                    Capability(
                        CapabilityIds.VOICE_INPUT,
                        "voice input",
                        CapabilityStatus.AVAILABLE,
                        detail = "Platform speech recogniser. It may use the network.",
                    )
            }

        val output =
            if (platform.ttsAvailable) {
                Capability(
                    CapabilityIds.VOICE_OUTPUT,
                    "speech output",
                    CapabilityStatus.AVAILABLE,
                    detail = "Platform text-to-speech. Offline speech depends on the installed voice data.",
                )
            } else {
                Capability(
                    CapabilityIds.VOICE_OUTPUT,
                    "speech output",
                    CapabilityStatus.UNAVAILABLE,
                    requirement = "a text-to-speech engine",
                    detail = "No text-to-speech engine is installed.",
                )
            }

        return listOf(
            input,
            output,
            Capability(
                CapabilityIds.OFFLINE_VOICE,
                "offline voice",
                CapabilityStatus.UNAVAILABLE,
                requirement = "on-device speech models",
                detail = "No on-device speech recognition or synthesis is compiled into this build.",
            ),
        )
    }

    private fun access(platform: PlatformFacts): List<Capability> {
        val wake =
            when {
                !platform.behaviour.wakeWordEnabled ->
                    Capability(
                        CapabilityIds.WAKE_WORD,
                        "wake phrase",
                        CapabilityStatus.DISABLED,
                        detail = "The wake phrase is switched off in Settings.",
                    )
                !platform.microphoneGranted ->
                    Capability(
                        CapabilityIds.WAKE_WORD,
                        "wake phrase",
                        CapabilityStatus.NEEDS_PERMISSION,
                        requirement = "microphone permission",
                        detail = "The wake phrase needs the microphone.",
                    )
                !platform.recognitionAvailable ->
                    Capability(
                        CapabilityIds.WAKE_WORD,
                        "wake phrase",
                        CapabilityStatus.UNAVAILABLE,
                        requirement = "a speech recognition service",
                        detail = "No speech recognition service is installed.",
                    )
                else ->
                    Capability(
                        CapabilityIds.WAKE_WORD,
                        "wake phrase",
                        CapabilityStatus.AVAILABLE,
                        detail = "Phrase matching through the platform recogniser. It may use the network.",
                    )
            }

        return listOf(
            wake,
            Capability(
                CapabilityIds.INTERNET,
                "internet",
                if (platform.networkAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
                requirement = if (platform.networkAvailable) null else "a network connection",
                detail =
                    if (platform.networkAvailable) {
                        "A network connection is available."
                    } else {
                        "No network connection is available."
                    },
            ),
            if (platform.behaviour.cloudMemoryConfigured) {
                Capability(
                    CapabilityIds.MEMORY_CLOUD,
                    "GitHub memory",
                    CapabilityStatus.AVAILABLE,
                    detail = "Conversation mirroring to GitHub is configured; the repository file is plaintext.",
                )
            } else {
                Capability(
                    CapabilityIds.MEMORY_CLOUD,
                    "GitHub memory",
                    CapabilityStatus.DISABLED,
                    detail = "GitHub sync is not configured.",
                )
            },
            accessCapability(
                CapabilityIds.NOTIFICATIONS,
                "notification access",
                platform.notificationAccess,
                "notification access",
            ),
            accessCapability(
                CapabilityIds.SCREEN,
                "screen access",
                platform.accessibilityEnabled,
                "the accessibility service",
            ),
            accessCapability(
                CapabilityIds.WRITE_SETTINGS,
                "brightness settings",
                platform.writeSettingsAllowed,
                "WRITE_SETTINGS access",
            ),
            accessCapability(
                CapabilityIds.DND,
                "Do Not Disturb access",
                platform.dndAccess,
                "Do Not Disturb access",
            ),
            Capability(
                CapabilityIds.MEMORY_LOCAL,
                "local memory",
                CapabilityStatus.UNAVAILABLE,
                requirement = "local memory storage",
                detail = "Conversation memory is not written to local storage in this build.",
            ),
        )
    }

    private fun accessCapability(
        id: String,
        label: String,
        granted: Boolean,
        requirement: String,
    ): Capability =
        if (granted) {
            Capability(id, label, CapabilityStatus.AVAILABLE, detail = "Granted.")
        } else {
            Capability(
                id,
                label,
                CapabilityStatus.NEEDS_PERMISSION,
                requirement = requirement,
                detail = "Access has not been granted.",
            )
        }

    private fun deviceTools(platform: PlatformFacts): Capability {
        if (platform.tools.isEmpty()) {
            return Capability(
                CapabilityIds.DEVICE_TOOLS,
                "device tools",
                CapabilityStatus.UNAVAILABLE,
                detail = "No device tools are registered.",
            )
        }
        val usable = platform.tools.count { it.status.isAvailable }
        val needsPermission = platform.tools.count { it.status == CapabilityStatus.NEEDS_PERMISSION }
        val detail =
            buildString {
                append("$usable of ${platform.tools.size} usable without another grant")
                if (needsPermission > 0) append("; $needsPermission need a permission")
                append('.')
            }
        return Capability(
            CapabilityIds.DEVICE_TOOLS,
            "device tools",
            CapabilityStatus.AVAILABLE,
            detail = detail,
        )
    }

    private fun notImplemented(): List<Capability> =
        listOf(
            Capability(
                CapabilityIds.MONITORING,
                "health monitoring",
                CapabilityStatus.UNAVAILABLE,
                detail = "Not implemented in this build.",
            ),
            Capability(
                CapabilityIds.REFLECTION,
                "reflection",
                CapabilityStatus.UNAVAILABLE,
                detail = "Not implemented in this build.",
            ),
            Capability(
                CapabilityIds.GOALS,
                "goals",
                CapabilityStatus.UNAVAILABLE,
                detail = "Not implemented in this build.",
            ),
        )

    private fun limitations(
        platform: PlatformFacts,
        engine: EngineFacts,
        registry: CapabilityRegistry,
    ): List<String> =
        buildList {
            if (platform.localModelFiles.isNotEmpty() && engine.backend != BackendKind.LOCAL) {
                add(
                    if (platform.localInferenceCompiled) {
                        "A local GGUF model (${platform.localModelFiles.first()}) is installed, but local " +
                            "reasoning is switched off in Settings."
                    } else {
                        "A local GGUF model (${platform.localModelFiles.first()}) is installed, but this build " +
                            "cannot load it."
                    },
                )
            }
            if (!registry.isAvailable(CapabilityIds.LOCAL_REASONING)) {
                add("No on-device language model: replies need a network and an API key.")
            }
            if (!registry.isAvailable(CapabilityIds.OFFLINE_VOICE)) {
                add("Speech recognition and synthesis are not guaranteed to work offline.")
            }
            if (!registry.isAvailable(CapabilityIds.MEMORY_LOCAL)) {
                add("Memory is not stored locally; it lasts for this session unless GitHub sync is configured.")
            }
            if (!registry.isAvailable(CapabilityIds.MONITORING)) {
                add("Health monitoring, reflection and goals are not implemented in this build.")
            }
            if (!platform.behaviour.wakeWordEnabled) {
                add("The wake phrase is switched off.")
            }
            if (platform.tools.any { it.status == CapabilityStatus.NEEDS_PERMISSION }) {
                add("Some device tools need a permission you have not granted.")
            }
        }.take(MAX_LIMITATIONS)
}
