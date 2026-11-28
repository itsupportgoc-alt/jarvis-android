package ai.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceSelectionTest {
    private val british = Locale.forLanguageTag("en-GB")

    private fun voice(
        name: String,
        tag: String,
        network: Boolean = false,
    ) = VoiceOption(name, tag, network)

    @Test
    fun `prefers the measured British male voice over the engine order`() {
        val voices =
            listOf(
                voice("en-gb-x-gba-local", "en-GB"),
                voice("en-gb-x-gbd-local", "en-GB"),
                voice("en-us-x-sfg-local", "en-US"),
            )
        val picked = VoiceSelection.pick(voices, british, male = true)
        assertEquals("en-gb-x-gbd-local", picked?.name)
        assertTrue(VoiceSelection.isMale(picked!!))
    }

    @Test
    fun `an installed male voice beats a network one`() {
        val voices =
            listOf(
                voice("en-gb-x-rjs-network", "en-GB", network = true),
                voice("en-gb-x-rjs-local", "en-GB"),
            )
        assertEquals("en-gb-x-rjs-local", VoiceSelection.pick(voices, british, male = true)?.name)
    }

    @Test
    fun `a male voice in another region still wins for the male preset`() {
        val voices =
            listOf(
                voice("en-us-x-sfg-local", "en-US"),
                voice("en-us-x-iol-local", "en-US"),
                voice("en-us-x-tpc-local", "en-US"),
            )
        val picked = VoiceSelection.pick(voices, british, male = true)
        assertEquals("en-us-x-iol-local", picked?.name)
        assertTrue(VoiceSelection.isMale(picked!!))
    }

    @Test
    fun `falls back to any same-language voice when no male voice is installed`() {
        val voices = listOf(voice("en-gb-x-gba-local", "en-GB"))
        val picked = VoiceSelection.pick(voices, british, male = true)
        assertEquals("en-gb-x-gba-local", picked?.name)
        assertFalse(VoiceSelection.isMale(picked!!))
    }

    @Test
    fun `other engines' plain male names are recognised`() {
        val voices =
            listOf(
                voice("English (United Kingdom) female", "en-GB"),
                voice("Daniel", "en-GB"),
            )
        val picked = VoiceSelection.pick(voices, british, male = true)
        assertEquals("Daniel", picked?.name)
        assertTrue(VoiceSelection.isMale(picked!!))
    }

    @Test
    fun `a language with no male voice keeps working`() {
        val voices = listOf(voice("fil-ph-x-fil-local", "fil-PH"))
        val picked = VoiceSelection.pick(voices, Locale.forLanguageTag("fil-PH"), male = true)
        assertEquals("fil-ph-x-fil-local", picked?.name)
        assertFalse(VoiceSelection.isMale(picked!!))
    }

    @Test
    fun `no voices at all is null, not a crash`() {
        assertNull(VoiceSelection.pick(emptyList(), british, male = true))
    }

    @Test
    fun `a different language family is not used`() {
        val voices = listOf(voice("de-de-x-dea-local", "de-DE"))
        assertNull(VoiceSelection.pick(voices, british, male = true))
    }
}
