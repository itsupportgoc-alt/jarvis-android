package ai.jarvis.core

import org.junit.Assert.assertEquals
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
    fun stripsWhitespaceAndInvisibleCharacters() {
        assertEquals("sk-abc123", ProviderProbe.sanitizeKey(" sk-abc\u200B123\n"))
        assertEquals("sk-abc123", ProviderProbe.sanitizeKey("\tsk-abc\uFEFF123 "))
        assertEquals(
            "sk-05636cbcd6c0cb12ddcdbf1bb6561daacad75ca36b0b6063",
            ProviderProbe.sanitizeKey("sk-05636cbcd6c0cb12ddcdbf1bb6561daacad75ca36b0b6063"),
        )
    }
}
