package ai.jarvis.core

import ai.jarvis.awareness.BackendKind
import ai.jarvis.awareness.BehaviourFacts
import ai.jarvis.awareness.CapabilityStatus
import ai.jarvis.awareness.EngineFacts
import ai.jarvis.awareness.IdentityComposer
import ai.jarvis.awareness.IdentitySnapshot
import ai.jarvis.awareness.PlatformFacts
import ai.jarvis.awareness.ToolFact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsciousnessTest {
    private fun tools(count: Int): List<ToolFact> =
        (1..count).map { index ->
            ToolFact(
                name = "tool_$index",
                label = "tool $index",
                status =
                    if (index % 3 == 0) {
                        CapabilityStatus.NEEDS_PERMISSION
                    } else {
                        CapabilityStatus.AVAILABLE
                    },
            )
        }

    private fun platform(
        network: Boolean = true,
        apiKey: Boolean = true,
        mic: Boolean = true,
        wake: Boolean = true,
        models: List<String> = emptyList(),
        toolCount: Int = 39,
    ) = PlatformFacts(
        appVersion = "0.2.1",
        coreVersion = "0.1.0",
        androidVersion = "12",
        deviceModel = "Redmi 10",
        microphoneGranted = mic,
        notificationAccess = true,
        accessibilityEnabled = false,
        writeSettingsAllowed = false,
        dndAccess = false,
        recognitionAvailable = true,
        ttsAvailable = true,
        networkAvailable = network,
        localModelFiles = models,
        tools = tools(toolCount),
        behaviour =
            BehaviourFacts(
                apiKeyPresent = apiKey,
                wakeWordEnabled = wake,
                cloudMemoryConfigured = false,
            ),
    )

    private fun snapshot(
        facts: PlatformFacts = platform(),
        backend: BackendKind = BackendKind.REMOTE,
    ): IdentitySnapshot =
        IdentityComposer.compose(
            facts,
            EngineFacts(
                created = true,
                running = true,
                phase = "listening",
                backend = backend,
                providerLabel = "Freebuff API",
                model = "deepseek-v4.1-flash",
            ),
            now = 0L,
        )

    private fun render(
        snapshot: IdentitySnapshot = snapshot(),
        persona: Boolean = true,
    ): String =
        Consciousness.preamble(
            identity = snapshot,
            uptimeMinutes = 12,
            rememberedMessages = 6,
            mood = "neutral",
            persona = persona,
        )

    @Test
    fun staysWithinTheBudgetWithAFullToolRegistry() {
        val text = render()
        assertTrue("preamble is ${text.length} chars", text.length <= 1200)
        // The tail must survive, not be cut by the budget.
        assertTrue(text.contains("Limits to state plainly:"))
        assertTrue(text.contains("No on-device language model"))
    }

    @Test
    fun neverClaimsConsciousnessOrInventedSuccess() {
        val text = render()
        assertFalse(text.contains("self-aware", ignoreCase = true))
        assertTrue(text.contains("no feelings"))
        assertTrue(text.contains("Never claim success unless a tool result says so"))
    }

    @Test
    fun doesNotClaimInternetWhenThereIsNoNetwork() {
        val text = render(snapshot(platform(network = false, apiKey = true)))
        assertTrue(text.contains("Internet: unavailable"))
        assertTrue(text.contains("not reachable"))
        assertFalse(text.contains("Internet: available"))
        assertFalse(text.contains("web_search"))
        assertFalse(text.contains("live internet access"))
    }

    @Test
    fun usesWebToolsOnlyWhenInternetIsVerified() {
        val text = render(snapshot(platform(network = true)))
        assertTrue(text.contains("Internet: available"))
        assertTrue(text.contains("web_search"))
    }

    @Test
    fun listsUnavailableCapabilitiesAsUnavailable() {
        val text = render(snapshot(platform(models = emptyList())))
        assertTrue(text.contains("Not available:"))
        assertTrue(text.contains("local reasoning (needs a model)"))
        assertTrue(text.contains("offline voice (unavailable)"))
    }

    @Test
    fun aDeniedMicrophoneIsReportedAsNeedingPermission() {
        val text = render(snapshot(platform(mic = false, wake = true)))
        assertTrue(text.contains("voice input (needs permission)"))
        assertFalse(text.contains("Available: voice input"))
    }

    @Test
    fun installedModelIsDescribedHonestly() {
        val text = render(snapshot(platform(models = listOf("qwen2.5-0.5b.gguf"))))
        assertTrue(text.contains("Not available:"))
        assertTrue(text.contains("local reasoning (unavailable)"))
    }

    @Test
    fun personaOffStillSendsTheFactsAndHonestyRules() {
        val text = render(persona = false)
        assertFalse(text.contains("Who you are:"))
        assertTrue(text.contains("Right now:"))
        assertTrue(text.contains("Honesty:"))
        assertTrue(text.contains("0.2.1"))
    }

    @Test
    fun carriesVersionAndBackendFacts() {
        val text = render()
        assertTrue(text.contains("0.2.1"))
        assertTrue(text.contains("0.1.0"))
        assertTrue(text.contains("Freebuff API"))
        assertTrue(text.contains("deepseek-v4.1-flash"))
        assertTrue(text.contains("39 usable") || text.contains("of 39"))
    }
}
