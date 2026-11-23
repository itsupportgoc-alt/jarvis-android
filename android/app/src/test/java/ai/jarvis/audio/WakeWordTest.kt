package ai.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    @Test
    fun extractsCommandAfterName() {
        assertEquals("open camera", WakeWord.command("Jarvis open camera"))
    }

    @Test
    fun toleratesGreetingAndPunctuation() {
        assertEquals("what's the weather", WakeWord.command("Hey Jarvis, what's the weather?"))
    }

    @Test
    fun wakeWordAloneYieldsEmptyCommand() {
        assertEquals("", WakeWord.command("Jarvis"))
        assertEquals("", WakeWord.command("hey jarvis!"))
    }

    @Test
    fun unrelatedSpeechIsIgnored() {
        assertNull(WakeWord.command("how are you today"))
        assertNull(WakeWord.command(""))
    }

    @Test
    fun matchesNameInsideSentence() {
        assertEquals("turn on the lights", WakeWord.command("okay jarvis turn on the lights"))
    }

    @Test
    fun doesNotMatchLookalikeWords() {
        assertNull(WakeWord.command("jarvisonic speakers"))
    }
}
