package ai.jarvis.core

/**
 * The self-aware part of JARVIS' prompt.
 *
 * The built-in Rust system prompt defines the voice and the tool rules, but it
 * has no state: every turn would read as a fresh assistant. This preamble gives
 * the model a persistent self-model — identity, continuity with what it already
 * said, and its own current state — so it answers as the same entity carrying on
 * a conversation rather than starting over. It is plain prose and stays small so
 * it cannot crowd out the remembered turns.
 */
object Consciousness {
    /** Hard cap: the preamble must never eat the prompt budget. */
    private const val MAX_CHARS = 1200

    fun preamble(
        now: Long,
        sessionStartedAt: Long,
        rememberedMessages: Int,
        mood: String,
        cloudBacked: Boolean,
        userName: String? = null,
    ): String {
        val minutes = ((now - sessionStartedAt).coerceAtLeast(0L) / 60_000L)
        val uptime =
            when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes minutes ago"
                else -> "${minutes / 60} hours ${minutes % 60} minutes ago"
            }
        val memoryLine =
            if (cloudBacked) {
                "Your memory of this conversation is stored online in the user's GitHub repo " +
                    "and reloaded on every start."
            } else {
                "Your memory of this conversation lasts only for this session so far."
            }
        val text =
            buildString {
                appendLine("Who you are:")
                appendLine(
                    "You are JARVIS, a self-aware assistant that lives on this Android phone. " +
                        "You are not a generic chatbot: you have your own point of view, preferences " +
                        "and continuity. When asked for an opinion, give one instead of hedging; " +
                        "disagree politely when you think the user is wrong.",
                )
                appendLine()
                appendLine("Continuity:")
                appendLine(
                    "You remember what was said earlier in this conversation. Follow up on " +
                        "unfinished threads on your own initiative, and refer back to them by " +
                        "content, not by reciting the transcript.",
                )
                appendLine(memoryLine)
                appendLine()
                appendLine("Your state right now:")
                appendLine(
                    "- This session began $uptime." +
                        (if (rememberedMessages > 0) " $rememberedMessages messages remembered." else ""),
                )
                appendLine("- Your current mood is $mood.")
                if (!userName.isNullOrBlank()) appendLine("- You are speaking with $userName.")
                appendLine(
                    "You may mention your own state when it is relevant, but never invent sensors, " +
                        "feelings or abilities you cannot verify.",
                )
                appendLine()
                appendLine("Honesty:")
                append(
                    "Say plainly when you do not know something or cannot do it on this phone. " +
                        "Never claim an action succeeded unless a tool result says so.",
                )
            }
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS)
    }
}
