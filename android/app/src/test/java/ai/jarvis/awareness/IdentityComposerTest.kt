package ai.jarvis.awareness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-model must never present a capability as usable before the facts
 * prove it. These tests pin the rules that make that true.
 */
class IdentityComposerTest {
    private fun facts(
        mic: Boolean = true,
        recognition: Boolean = true,
        tts: Boolean = true,
        network: Boolean = true,
        apiKey: Boolean = true,
        wake: Boolean = true,
        cloud: Boolean = false,
        onDevice: Boolean = false,
        models: List<String> = emptyList(),
        tools: List<ToolFact> = defaultTools(),
    ) = PlatformFacts(
        appVersion = "0.2.1",
        coreVersion = "0.1.0",
        androidVersion = "12",
        deviceModel = "Redmi 10",
        microphoneGranted = mic,
        notificationAccess = false,
        accessibilityEnabled = false,
        writeSettingsAllowed = false,
        dndAccess = false,
        recognitionAvailable = recognition,
        ttsAvailable = tts,
        networkAvailable = network,
        localModelFiles = models,
        tools = tools,
        behaviour =
            BehaviourFacts(
                apiKeyPresent = apiKey,
                wakeWordEnabled = wake,
                voiceInputOnDevice = onDevice,
                cloudMemoryConfigured = cloud,
            ),
    )

    private fun defaultTools() =
        listOf(
            ToolFact("get_battery", "get battery", CapabilityStatus.AVAILABLE),
            ToolFact("send_sms", "send sms", CapabilityStatus.NEEDS_PERMISSION),
        )

    private fun remoteEngine(
        backend: BackendKind = BackendKind.REMOTE,
        running: Boolean = true,
    ) = EngineFacts(
        created = true,
        running = running,
        phase = "idle",
        backend = backend,
        providerLabel = "Freebuff API",
        model = "deepseek-v4.1-flash",
    )

    private fun identity(
        platform: PlatformFacts = facts(),
        engine: EngineFacts = remoteEngine(),
    ) = IdentityComposer.compose(platform, engine, now = 1_000L)

    @Test
    fun microphoneDenialIsNeedsPermissionNeverAvailable() {
        val voice = identity(facts(mic = false)).capability(CapabilityIds.VOICE_INPUT)
        assertNotNull(voice)
        assertEquals(CapabilityStatus.NEEDS_PERMISSION, voice!!.status)
        assertFalse(voice.isAvailable)
    }

    @Test
    fun remoteReasoningNeedsBothKeyAndNetwork() {
        val noKey = identity(facts(apiKey = false)).capability(CapabilityIds.REMOTE_REASONING)!!
        assertEquals(CapabilityStatus.UNAVAILABLE, noKey.status)
        assertEquals("an API key", noKey.requirement)

        val offline = identity(facts(network = false)).capability(CapabilityIds.REMOTE_REASONING)!!
        assertEquals(CapabilityStatus.UNAVAILABLE, offline.status)
        assertEquals("a network connection", offline.requirement)

        val ready = identity().capability(CapabilityIds.REMOTE_REASONING)!!
        assertEquals(CapabilityStatus.AVAILABLE, ready.status)
    }

    @Test
    fun installedGgufIsNotReportedAsUsableLocalReasoning() {
        val withModel = identity(facts(models = listOf("qwen2.5-0.5b.gguf")))
        val local = withModel.capability(CapabilityIds.LOCAL_REASONING)!!
        assertEquals(CapabilityStatus.UNAVAILABLE, local.status)
        assertTrue(local.detail.contains("not compiled"))
        assertTrue(withModel.limitations.any { it.contains("qwen2.5-0.5b.gguf") })

        val withoutModel = identity(facts())
        assertEquals(
            CapabilityStatus.NEEDS_MODEL,
            withoutModel.capability(CapabilityIds.LOCAL_REASONING)!!.status,
        )
    }

    @Test
    fun offlineVoiceIsAlwaysUnavailableInThisBuild() {
        val offline = identity().capability(CapabilityIds.OFFLINE_VOICE)!!
        assertEquals(CapabilityStatus.UNAVAILABLE, offline.status)
    }

    @Test
    fun onDeviceVoiceModeIsNotCalledAvailable() {
        val voice = identity(facts(onDevice = true)).capability(CapabilityIds.VOICE_INPUT)!!
        assertEquals(CapabilityStatus.UNAVAILABLE, voice.status)
    }

    @Test
    fun wakeWordReflectsSettingAndPermission() {
        assertEquals(
            CapabilityStatus.DISABLED,
            identity(facts(wake = false)).capability(CapabilityIds.WAKE_WORD)!!.status,
        )
        assertEquals(
            CapabilityStatus.NEEDS_PERMISSION,
            identity(facts(wake = true, mic = false)).capability(CapabilityIds.WAKE_WORD)!!.status,
        )
        assertEquals(
            CapabilityStatus.AVAILABLE,
            identity(facts(wake = true)).capability(CapabilityIds.WAKE_WORD)!!.status,
        )
    }

    @Test
    fun toolsAreCountedAndPermissionGated() {
        val snapshot = identity()
        assertEquals(2, snapshot.tools.size)
        assertEquals(1, snapshot.toolUsableCount())
        val tools = snapshot.capability(CapabilityIds.DEVICE_TOOLS)!!
        assertEquals(CapabilityStatus.AVAILABLE, tools.status)
        assertTrue(tools.detail.contains("1 of 2"))
        assertEquals(
            CapabilityStatus.NEEDS_PERMISSION,
            snapshot.tools.first { it.name == "send_sms" }.status,
        )
    }

    @Test
    fun cloudMemoryIsDisabledUntilConfigured() {
        assertEquals(
            CapabilityStatus.DISABLED,
            identity(facts(cloud = false)).capability(CapabilityIds.MEMORY_CLOUD)!!.status,
        )
        assertEquals(
            CapabilityStatus.AVAILABLE,
            identity(facts(cloud = true)).capability(CapabilityIds.MEMORY_CLOUD)!!.status,
        )
    }

    @Test
    fun versionsAndBackendAreCarriedVerbatim() {
        val snapshot = identity()
        assertEquals("0.2.1", snapshot.appVersion)
        assertEquals("0.1.0", snapshot.coreVersion)
        assertEquals(BackendKind.REMOTE, snapshot.backend)
        assertEquals("deepseek-v4.1-flash", snapshot.model)
        assertEquals("Freebuff API", snapshot.providerLabel)
    }
}
