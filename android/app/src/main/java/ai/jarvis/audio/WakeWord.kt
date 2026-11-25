package ai.jarvis.audio

/**
 * Recognises the wake phrase and extracts the command that follows it.
 *
 * Kept pure so the matching rules are unit-testable without a microphone: the
 * wake listener gets raw transcripts from the platform recogniser, which vary
 * in casing, punctuation and spacing.
 */
object WakeWord {
    private val WAKE = Regex("\\b(?:hey\\s+)?seven\\b", RegexOption.IGNORE_CASE)

    /**
     * Words that may accompany the name without forming a command.
     *
     * "play" is here because the recogniser on the test phone transcribes the
     * greeting "Hey Seven" as "Play Seven" (and "Play 7"); without it that
     * became a bogus command sent to the model instead of the wake word.
     */
    private val FILLER = setOf("hey", "ok", "okay", "yo", "hoy", "play")

    /**
     * True when the transcript is nothing but the name, e.g. "Seven",
     * "Hey Seven?" or "okay seven".
     *
     * This is the phrase the recogniser returns when a silent session re-arms
     * and the user starts over by saying the wake word: it is an address, not
     * an instruction, so it must never be handed to the model as a command.
     */
    fun isWakeOnly(transcript: String): Boolean {
        val words =
            transcript
                .lowercase()
                // Digits stay: the recogniser often writes the name as "7".
                .replace(Regex("[^a-z0-9 ]"), " ")
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.none { it == "seven" || it == "7" }) return false
        return words.all { it == "seven" || it == "7" || it in FILLER }
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
