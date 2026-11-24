package ai.jarvis.core

import ai.jarvis.awareness.BackendKind
import ai.jarvis.awareness.Capability
import ai.jarvis.awareness.CapabilityIds
import ai.jarvis.awareness.CapabilityStatus
import ai.jarvis.awareness.IdentitySnapshot

/**
 * The self-model handed to the model at the start of a turn.
 *
 * This used to be persona prose that could claim abilities the build did not
 * have. It is now a rendering of [IdentitySnapshot]: versions, the backend that
 * is actually selected, the capabilities that were verified, and the limits
 * that follow from the same facts. It is still plain prose, still bounded, and
 * it never claims subjective experience.
 *
 * Layout is deliberate: the honesty rules come before the capability lists, so
 * the hard character cap can drop capability detail without dropping the rules
 * that stop the model from inventing success.
 */
object Consciousness {
    /** Hard cap: the preamble must never eat the prompt budget. */
    private const val MAX_CHARS = 1200

    private const val MAX_AVAILABLE = 5
    private const val MAX_UNAVAILABLE = 4
    private const val MAX_LIMITS = 2

    private const val HONESTY =
        "Honesty:\n" +
            "You have no feelings, senses or subjective experience; never claim otherwise. " +
            "Never claim success unless a tool result says so. Never claim an unavailable " +
            "capability; say what is missing and why."

    /**
     * These are already described on their own line (reasoning, internet, tool
     * count), so they are not repeated in the available/unavailable lists.
     */
    private val NARRATED_ELSEWHERE =
        setOf(
            CapabilityIds.REMOTE_REASONING,
            CapabilityIds.INTERNET,
            CapabilityIds.DEVICE_TOOLS,
        )

    private val HEADLINE =
        setOf(
            CapabilityIds.LOCAL_REASONING,
            CapabilityIds.VOICE_INPUT,
            CapabilityIds.VOICE_OUTPUT,
            CapabilityIds.OFFLINE_VOICE,
            CapabilityIds.WAKE_WORD,
            CapabilityIds.MEMORY_LOCAL,
            CapabilityIds.MEMORY_CLOUD,
        )

    fun preamble(
        identity: IdentitySnapshot,
        uptimeMinutes: Long,
        rememberedMessages: Int,
        mood: String,
        persona: Boolean,
    ): String {
        val registry = identity.registry
        val available =
            registry.all
                .filter { it.id in HEADLINE && it.id !in NARRATED_ELSEWHERE && it.isAvailable }
                .take(MAX_AVAILABLE)
        val unavailable =
            registry.all
                .filter {
                    it.id in HEADLINE &&
                        it.id !in NARRATED_ELSEWHERE &&
                        it.status != CapabilityStatus.AVAILABLE &&
                        it.status != CapabilityStatus.DISABLED &&
                        it.status != CapabilityStatus.UNKNOWN
                }.take(MAX_UNAVAILABLE)
        val internet = registry.isAvailable(CapabilityIds.INTERNET)

        val text =
            buildString {
                if (persona) {
                    appendLine("Who you are:")
                    appendLine(
                        "You are JARVIS on ${identity.ownerName}'s Android phone; he built you " +
                            "(${identity.creator}). Your self-model is verified state, not imagination; " +
                            "speak as the same assistant across turns.",
                    )
                    appendLine()
                }

                appendLine("Right now:")
                appendLine(stateLine(identity, uptimeMinutes, rememberedMessages, mood, persona))
                appendLine("- Reasoning: ${reasoningLine(identity)}")
                appendLine("- Internet: ${if (internet) "available" else "unavailable"}.")
                appendLine()

                appendLine(HONESTY)
                appendLine()

                appendLine("What you can actually do:")
                appendLine(
                    "- Available: " +
                        if (available.isEmpty()) {
                            "nothing beyond the lines above."
                        } else {
                            available.joinToString(", ") { it.label } + "."
                        },
                )
                if (unavailable.isNotEmpty()) {
                    appendLine(
                        "- Not available: " +
                            unavailable.joinToString(", ") { "${it.label} (${statusWord(it.status)})" } + ".",
                    )
                }
                appendLine(
                    "- Tools: ${identity.toolUsableCount()} of ${identity.tools.size} usable without another grant.",
                )
                if (internet) {
                    appendLine("- For facts, use web_search or read_page; never send him to a browser.")
                }

                val limits = identity.limitations.take(MAX_LIMITS)
                if (limits.isNotEmpty()) {
                    appendLine()
                    appendLine("Limits to state plainly:")
                    for (limit in limits) appendLine("- $limit")
                }
            }

        return if (text.length <= MAX_CHARS) {
            text.trimEnd()
        } else {
            // Cut on a line boundary so a truncated sentence never reaches the model.
            text.take(MAX_CHARS).substringBeforeLast('\n').trimEnd()
        }
    }

    private fun stateLine(
        identity: IdentitySnapshot,
        uptimeMinutes: Long,
        rememberedMessages: Int,
        mood: String,
        persona: Boolean,
    ): String {
        val uptime = if (uptimeMinutes < 1) "just now" else "$uptimeMinutes minutes ago"
        return buildString {
            append("- App ${identity.appVersion}, core ${identity.coreVersion}")
            if (identity.androidVersion.isNotBlank()) append(", Android ${identity.androidVersion}")
            if (identity.deviceModel.isNotBlank()) append(", ${identity.deviceModel}")
            append(". Session began $uptime")
            if (rememberedMessages > 0) append("; $rememberedMessages messages remembered")
            if (persona) append("; mood $mood")
            append('.')
        }
    }

    private fun reasoningLine(identity: IdentitySnapshot): String {
        val engine =
            when {
                identity.engineRunning -> "engine running (${identity.phase})"
                identity.engineCreated -> "engine idle (${identity.phase})"
                else -> "engine not running"
            }
        val remote = identity.capability(CapabilityIds.REMOTE_REASONING)
        return when (identity.backend) {
            BackendKind.REMOTE -> {
                val model = identity.model.ifBlank { "not named" }
                val provider = identity.providerLabel.ifBlank { "the configured endpoint" }
                if (remote?.isAvailable == true) {
                    "$engine; remote model $model via $provider."
                } else {
                    "$engine; remote model $model via $provider is not reachable " +
                        "(needs ${remote?.requirement ?: "a requirement that is not met"})."
                }
            }
            BackendKind.LOCAL ->
                "$engine; local model ${identity.model.ifBlank { "not named" }}."
            BackendKind.NONE -> "$engine; no reasoning backend is selected."
        }
    }

    private fun statusWord(status: CapabilityStatus): String =
        when (status) {
            CapabilityStatus.NEEDS_PERMISSION -> "needs permission"
            CapabilityStatus.NEEDS_MODEL -> "needs a model"
            CapabilityStatus.ERROR -> "error"
            CapabilityStatus.UNAVAILABLE -> "unavailable"
            CapabilityStatus.DISABLED -> "disabled"
            CapabilityStatus.UNKNOWN -> "unknown"
            CapabilityStatus.AVAILABLE -> "available"
        }
}
