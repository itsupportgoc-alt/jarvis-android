package ai.jarvis.audio

/**
 * Recognises the wake phrase and extracts the command that follows it.
 *
 * Kept pure so the matching rules are unit-testable without a microphone: the
 * wake listener gets raw transcripts from the platform recogniser, which vary
 * in casing, punctuation and spacing.
 */
object WakeWord {
    private val WAKE = Regex("\\b(?:hey\\s+)?jarvis\\b", RegexOption.IGNORE_CASE)

    /** Words that may accompany the name without forming a command. */
    private val FILLER = setOf("hey", "ok", "okay", "yo")

    /**
     * True when the transcript is nothing but the name, e.g. "Jarvis",
     * "Hey Jarvis?" or "okay jarvis".
     *
     * This is the phrase the recogniser returns when a silent session re-arms
     * and the user starts over by saying the wake word: it is an address, not
     * an instruction, so it must never be handed to the model as a command.
     */
    fun isWakeOnly(transcript: String): Boolean {
        val words =
            transcript
                .lowercase()
                .replace(Regex("[^a-z ]"), " ")
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.isEmpty() || "jarvis" !in words) return false
        return words.all { it == "jarvis" || it in FILLER }
    }

    /**
     * @return null when the transcript does not contain the wake word, an empty
     *   string when only the wake word was said, or the trailing instruction.
     */
    fun command(transcript: String): String? {
        val match = WAKE.find(transcript) ?: return null
        return transcript
            .substring(match.range.last + 1)
            .trim()
            .trimStart(',', '.', '!', '?', ':', '-', ' ')
            .trimEnd(',', '.', '!', '?', ':', '-', ' ')
            .trim()
    }
}
