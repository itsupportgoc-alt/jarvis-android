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

    /** Words that mean "stop talking / abandon this turn". */
    private val STOP_WORDS = setOf("stop", "cancel", "quiet", "enough", "halt")

    /**
     * True when the utterance is a spoken stop, not a request.
     *
     * Deliberately strict: one meaningful word ("stop", "stop it", "please
     * stop", "seven stop") or the phrase "shut up". The microphone stays open
     * while SEVEN is thinking or speaking, so the assistant's own voice can be
     * transcribed; a stricter match keeps a sentence that merely contains
     * "stop" from cancelling the reply.
     */
    fun isStop(transcript: String): Boolean {
        val words =
            transcript
                .lowercase()
                .replace(Regex("[^a-z ]"), " ")
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        if (words == listOf("shut", "up")) return true
        val meaningful = words.filterNot { it == "seven" || it == "it" || it == "now" || it == "please" || it in FILLER }
        return meaningful.size == 1 && meaningful.first() in STOP_WORDS
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
