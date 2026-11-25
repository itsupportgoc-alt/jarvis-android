package ai.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun extractsCommandAfterName() {
        assertEquals("open camera", WakeWord.command("Seven open camera"))
    }

    @Test
    fun matchesTheReportedPhrase() {
        // The phrase that was never firing on the device, as the recogniser
        // delivers it and as the debug transcript injects it.
        assertEquals("open settings", WakeWord.command("Seven open settings"))
        assertEquals("open settings", WakeWord.command("seven, open settings."))
    }

    @Test
    fun toleratesGreetingAndPunctuation() {
        assertEquals("what's the weather", WakeWord.command("Hey Seven, what's the weather?"))
    }

    @Test
    fun wakeWordAloneYieldsEmptyCommand() {
        assertEquals("", WakeWord.command("Seven"))
        assertEquals("", WakeWord.command("hey seven!"))
    }

    @Test
    fun unrelatedSpeechIsIgnored() {
        assertNull(WakeWord.command("how are you today"))
        assertNull(WakeWord.command(""))
    }

    @Test
    fun matchesNameInsideSentence() {
        assertEquals("turn on the lights", WakeWord.command("okay seven turn on the lights"))
    }

    @Test
    fun theOldNameIsNoLongerTheWakeWord() {
        assertNull(WakeWord.command("Jarvis open camera"))
        assertEquals("open camera", WakeWord.command("seven open camera"))
    }

    @Test
    fun doesNotMatchLookalikeWords() {
        assertNull(WakeWord.command("sevening speakers"))
    }

    @Test
    fun nameAloneIsNotACommand() {
        // The re-armed session hears only this and used to send it to the model.
        assertTrue(WakeWord.isWakeOnly("Seven"))
        assertTrue(WakeWord.isWakeOnly("seven."))
        assertTrue(WakeWord.isWakeOnly("Hey Seven?"))
        assertTrue(WakeWord.isWakeOnly(" okay, Seven! "))
        assertTrue(WakeWord.isWakeOnly("hoy seven"))
        // What the recogniser on the test phone actually returns for "Hey Seven".
        assertTrue(WakeWord.isWakeOnly("Play seven"))
        assertTrue(WakeWord.isWakeOnly("play 7"))
    }

    @Test
    fun spokenStopIsRecognised() {
        assertTrue(WakeWord.isStop("stop"))
        assertTrue(WakeWord.isStop("Stop."))
        assertTrue(WakeWord.isStop("stop it"))
        assertTrue(WakeWord.isStop("please stop now"))
        assertTrue(WakeWord.isStop("Seven, stop!"))
        assertTrue(WakeWord.isStop("cancel"))
        assertTrue(WakeWord.isStop("shut up"))
        assertTrue(WakeWord.isStop("quiet"))
    }

    @Test
    fun aSentenceThatOnlyMentionsStoppingIsNotAStop() {
        // The assistant's own reply can be transcribed while the microphone is
        // open during speech, so a mention must not cancel the turn.
        assertFalse(WakeWord.isStop("the bus will stop at the corner"))
        assertFalse(WakeWord.isStop("stop the timer at five minutes"))
        assertFalse(WakeWord.isStop("what is the bus stop"))
        assertFalse(WakeWord.isStop(""))
        assertFalse(WakeWord.isStop("open camera"))
    }

    @Test
    fun anythingWithAnInstructionIsACommand() {
        assertFalse(WakeWord.isWakeOnly("Seven open camera"))
        assertFalse(WakeWord.isWakeOnly("hey seven what's the weather"))
        assertFalse(WakeWord.isWakeOnly("turn on the lights"))
        assertFalse(WakeWord.isWakeOnly(""))
    }
}
