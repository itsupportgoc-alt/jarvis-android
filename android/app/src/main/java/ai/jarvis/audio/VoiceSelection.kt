package ai.jarvis.audio

import java.util.Locale

/** A voice the platform TTS engine offers, reduced to what the picker needs. */
data class VoiceOption(
    val name: String,
    val localeTag: String,
    val requiresNetwork: Boolean,
)

/**
 * Chooses which installed system voice SEVEN speaks with.
 *
 * Android's platform TTS exposes no gender flag, so the male voices are known by
 * name: the Google engine calls them `en-gb-x-gbd`, `en-gb-x-rjs` and
 * `en-gb-x-gbb`, each measured at 112-150 Hz median pitch on the target phone
 * (the rest of the en-GB set sits at 189-233 Hz). Other engines ship voices with
 * plain names such as "Daniel" or "Arthur", hence the name hints.
 *
 * The order matters: an installed (local) British male voice beats a network one,
 * because speech has to work with the phone offline, and a male voice in the
 * wrong region still beats a female voice in the right one.
 *
 * Pure function, no Android types, so the choice is unit-tested.
 */
object VoiceSelection {
    /** Google-engine male voices, deepest measured first. */
    private val KNOWN_MALE_VOICES =
        listOf("en-gb-x-gbd", "en-gb-x-rjs", "en-gb-x-gbb", "en-us-x-iol")

    /** Name hints other engines use for their male voices. */
    private val MALE_NAME_HINTS =
        listOf("daniel", "arthur", "oliver", "george", "brian", "ryan", "thomas")

    /** A name that contains one of the hints but is the opposite. */
    private val FEMALE_NAME_HINTS = listOf("female", "woman", "girl")


    fun pick(
        voices: List<VoiceOption>,
        target: Locale,
        male: Boolean,
    ): VoiceOption? {
        if (voices.isEmpty()) return null
        val sameLanguage = voices.filter { tagOf(it).language == target.language }
        if (sameLanguage.isEmpty()) return null
        val ranked =
            sameLanguage.sortedWith(
                compareBy(
                    { if (male) maleRank(it) else 0 },
                    { if (tagOf(it).country == target.country) 0 else 1 },
                    { if (it.requiresNetwork) 1 else 0 },
                    { it.name },
                ),
            )
        if (male) {
            ranked.firstOrNull { maleRank(it) < NO_MALE_VOICE }?.let { return it }
        }
        return ranked.firstOrNull()
    }

    /** True when the picker found a real male voice rather than any fallback. */
    fun isMale(voice: VoiceOption): Boolean = maleRank(voice) < NO_MALE_VOICE

    private const val NO_MALE_VOICE = 900

    private fun maleRank(voice: VoiceOption): Int {
        val base = baseName(voice.name)
        val known = KNOWN_MALE_VOICES.indexOf(base)
        if (known >= 0) return known
        val lower = voice.name.lowercase(Locale.ROOT)
        if (FEMALE_NAME_HINTS.any { lower.contains(it) }) return NO_MALE_VOICE
        val hint = MALE_NAME_HINTS.indexOfFirst { lower.contains(it) }
        return if (hint >= 0) 100 + hint else NO_MALE_VOICE
    }

    private fun tagOf(voice: VoiceOption): Locale = Locale.forLanguageTag(voice.localeTag)

    private fun baseName(name: String): String =
        name
            .substringBefore('#')
            .removeSuffix("-local")
            .removeSuffix("-network")
            .lowercase(Locale.ROOT)
}
