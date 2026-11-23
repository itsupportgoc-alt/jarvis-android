package ai.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {
    @Test
    fun idsAreUniqueAndEndpointsAreSecure() {
        assertEquals(PROVIDER_PRESETS.size, PROVIDER_PRESETS.map { it.id }.toSet().size)
        PROVIDER_PRESETS.forEach { preset ->
            assertTrue("${preset.id} is not https", preset.baseUrl.startsWith("https://"))
            assertTrue("${preset.id} has no model", preset.model.isNotBlank())
            assertTrue("${preset.id} has no fallback", preset.fallback.isNotBlank())
            assertTrue("${preset.id} has no console", preset.consoleUrl.startsWith("https://"))
        }
    }

    @Test
    fun bothTiersAreOffered() {
        assertTrue(PROVIDER_PRESETS.any { it.tier == ProviderTier.FREE })
        assertTrue(PROVIDER_PRESETS.any { it.tier == ProviderTier.PAID })
    }

    @Test
    fun lookupToleratesSlashesAndCase() {
        val gemini = providerPresetFor("https://generativelanguage.googleapis.com/v1beta/openai/")
        assertEquals("gemini", gemini?.id)
        assertEquals("groq", providerPresetFor(" HTTPS://api.groq.com/openai/v1 ".trim())?.id)
        assertEquals(null, providerPresetFor("https://example.invalid/v1"))
    }

    @Test
    fun customEndpointGetsItsOwnKeySlot() {
        assertEquals("groq", providerIdFor("https://api.groq.com/openai/v1"))
        assertEquals(CUSTOM_PROVIDER_ID, providerIdFor("https://my-endpoint.example/v1"))
    }
}
