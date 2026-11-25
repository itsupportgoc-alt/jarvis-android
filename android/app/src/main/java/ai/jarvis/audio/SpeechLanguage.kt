package ai.jarvis.audio

import java.util.Collections
import java.util.Locale

/**
 * Which language the microphone listens for and the voice speaks.
 *
 * [AUTO] follows the phone's own language. [TAGALOG] pins Filipino, which the
 * platform recogniser and Google TTS usually ship: an English recogniser
 * returns nothing usable for Tagalog speech, and an English voice reads Tagalog
 * text as if it were English. The reply itself is written by the model, which is
 * told to answer in whichever of the two the user just used.
 *
 * Not every recogniser backend knows every language: the one on a Redmi 10
 * answers `ERROR_LANGUAGE_NOT_SUPPORTED` for `fil-PH`, which used to leave the
 * microphone permanently deaf. A tag the backend rejects is remembered here and
 * skipped, so the next session falls back to the phone's language instead.
 */
object SpeechLanguage {
    const val AUTO = "auto"
    const val ENGLISH = "en"
    const val TAGALOG = "fil"

    val CHOICES = listOf(AUTO, ENGLISH, TAGALOG)

    /** Tags already refused by the recogniser in this process. */
    private val rejected = Collections.synchronizedSet(mutableSetOf<String>())

    fun label(choice: String): String =
        when (choice) {
            ENGLISH -> "English"
            TAGALOG -> "Tagalog"
            else -> "Phone language"
        }

    /** Every tag worth trying for a choice, in order. */
    fun candidates(choice: String): List<String> =
        when (choice) {
            ENGLISH -> listOf("en-US", "en-GB")
            // Android accepts both spellings for Filipino; backends differ.
            TAGALOG -> listOf("fil-PH", "tl-PH")
            else -> listOf(Locale.getDefault().toLanguageTag())
        }.filter { it.isNotBlank() }

    /** Language tag for `RecognizerIntent.EXTRA_LANGUAGE`. */
    fun recognitionTag(choice: String): String =
        candidates(choice).firstOrNull { it !in rejected }
            ?: Locale.getDefault().toLanguageTag()

    /**
     * Records a tag the recogniser refused, so it is not tried again. The
     * phone's own language is never recorded: it is the last resort, and
     * rejecting it would only make the diagnostic say the wrong thing.
     */
    fun reject(tag: String) {
        if (tag.isBlank()) return
        if (tag.equals(Locale.getDefault().toLanguageTag(), ignoreCase = true)) return
        rejected.add(tag)
    }

    /** True when the tag the recogniser refused is the one that was asked for. */
    fun wasRejected(tag: String): Boolean = tag in rejected

    /** Language tag for `TextToSpeech.setLanguage`, or null to keep the preset's. */
    fun ttsTag(choice: String): String? =
        when (choice) {
            TAGALOG -> "fil-PH"
            // The voice presets already speak English; an explicit en-US would
            // replace the British voice the presets are built around.
            else -> null
        }
}
