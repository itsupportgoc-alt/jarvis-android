package ai.jarvis.audio

import java.util.Locale

/**
 * Which language the microphone listens for and the voice speaks.
 *
 * [AUTO] follows the phone's own language. [TAGALOG] pins Filipino, which both
 * the platform recogniser and Google TTS ship: an English recogniser returns
 * nothing usable for Tagalog speech, and an English voice reads Tagalog text as
 * if it were English. The reply itself is written by the model, which is told
 * to answer in whichever of the two the user just used.
 */
object SpeechLanguage {
    const val AUTO = "auto"
    const val ENGLISH = "en"
    const val TAGALOG = "fil"

    val CHOICES = listOf(AUTO, ENGLISH, TAGALOG)

    fun label(choice: String): String =
        when (choice) {
            ENGLISH -> "English"
            TAGALOG -> "Tagalog"
            else -> "Phone language"
        }

    /** Language tag for `RecognizerIntent.EXTRA_LANGUAGE`. */
    fun recognitionTag(choice: String): String =
        when (choice) {
            ENGLISH -> "en-US"
            TAGALOG -> "fil-PH"
            else -> Locale.getDefault().toLanguageTag()
        }

    /** Language tag for `TextToSpeech.setLanguage`, or null to keep the preset's. */
    fun ttsTag(choice: String): String? =
        when (choice) {
            TAGALOG -> "fil-PH"
            // The voice presets already speak English; an explicit en-US would
            // replace the British voice the presets are built around.
            else -> null
        }
}
