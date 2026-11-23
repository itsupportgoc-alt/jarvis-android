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
