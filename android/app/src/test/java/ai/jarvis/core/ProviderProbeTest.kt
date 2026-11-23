package ai.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProbeTest {
    @Test
    fun joinsBaseUrlAndPathOnce() {
        assertEquals(
            "https://api.vv666.ai/v1/models",
            ProviderProbe.endpoint("https://api.vv666.ai/v1/", "/models"),
        )
        assertEquals(
            "https://api.vv666.ai/v1/models",
            ProviderProbe.endpoint(" https://api.vv666.ai/v1 ", "models"),
        )
    }

    @Test
    fun onlyAuthStatusesCountAsARejectedKey() {
        assertTrue(ProviderProbe.isAuthFailure(401))
        assertTrue(ProviderProbe.isAuthFailure(403))
        assertFalse(ProviderProbe.isAuthFailure(400))
        assertFalse(ProviderProbe.isAuthFailure(404))
        assertFalse(ProviderProbe.isAuthFailure(429))
        assertFalse(ProviderProbe.isAuthFailure(200))
    }

    @Test
    fun stripsWhitespaceAndInvisibleCharacters() {
        assertEquals("sk-abc123", ProviderProbe.sanitizeKey(" sk-abc\u200B123\n"))
        assertEquals("sk-abc123", ProviderProbe.sanitizeKey("\tsk-abc\uFEFF123 "))
        // A realistic length and shape, never a real credential.
        val pasted = "sk-0000000000000000000000000000000000000000000000"
        assertEquals(pasted, ProviderProbe.sanitizeKey("$pasted\r\n"))
    }
}
