package ai.jarvis.awareness

/**
 * Verified capability state for the self-model.
 *
 * Every value here must come from something the app can actually check: an
 * Android permission, a service that is installed, a file that exists, a
 * setting that is on, or a compiled engine path. Nothing may be inferred from
 * what the build "should" be able to do.
 */
enum class CapabilityStatus {
    /** Checked and working now. */
    AVAILABLE,

    /** Checked and not working, and no single grant would fix it. */
    UNAVAILABLE,

    /** The user turned it off. */
    DISABLED,

    /** The platform is installed but an Android permission/access is missing. */
    NEEDS_PERMISSION,

    /** The runtime exists but its model file is missing. */
    NEEDS_MODEL,

    /** The last check failed. */
    ERROR,

    /** Not checked yet. Never rendered as available. */
    UNKNOWN,
    ;

    val isAvailable: Boolean get() = this == AVAILABLE
}

/**
 * One row of the self-model: what it is, whether it works, and what is missing.
 *
 * [requirement] is the single thing the user can supply (a permission, a model
 * file, a key). [detail] is the short verified explanation shown in Settings and
 * handed to the model.
 */
data class Capability(
    val id: String,
    val label: String,
    val status: CapabilityStatus,
    val requirement: String? = null,
    val detail: String = "",
) {
    val isAvailable: Boolean get() = status == CapabilityStatus.AVAILABLE
}

/** Stable capability ids, used by the composer, the prompt and the tests. */
object CapabilityIds {
    const val REMOTE_REASONING = "reasoning.remote"
    const val LOCAL_REASONING = "reasoning.local"
    const val VOICE_INPUT = "voice.input"
    const val VOICE_OUTPUT = "voice.output"
    const val OFFLINE_VOICE = "voice.offline"
    const val WAKE_WORD = "wake.word"
    const val INTERNET = "network.internet"
    const val MEMORY_LOCAL = "memory.local"
    const val MEMORY_CLOUD = "memory.cloud"
    const val MONITORING = "monitoring.health"
    const val REFLECTION = "reflection"
    const val GOALS = "goals"
    const val NOTIFICATIONS = "access.notifications"
    const val SCREEN = "access.screen"
    const val WRITE_SETTINGS = "access.writeSettings"
    const val DND = "access.dnd"
    const val DEVICE_TOOLS = "tools.device"
}

/** Lookup helper so callers never have to scan the list by hand. */
class CapabilityRegistry(
    val all: List<Capability>,
) {
    private val index = all.associateBy { it.id }

    fun byId(id: String): Capability? = index[id]

    fun statusOf(id: String): CapabilityStatus = index[id]?.status ?: CapabilityStatus.UNKNOWN

    fun isAvailable(id: String): Boolean = index[id]?.isAvailable == true

    fun available(): List<Capability> = all.filter { it.isAvailable }

    fun blocked(): List<Capability> = all.filterNot { it.isAvailable }

    fun count(status: CapabilityStatus): Int = all.count { it.status == status }

    companion object {
        fun of(vararg capabilities: Capability): CapabilityRegistry = CapabilityRegistry(capabilities.toList())
    }
}
