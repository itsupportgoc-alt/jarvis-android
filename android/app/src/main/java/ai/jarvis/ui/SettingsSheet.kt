package ai.jarvis.ui

import ai.jarvis.awareness.BackendKind
import ai.jarvis.awareness.IdentitySnapshot
import ai.jarvis.core.JarvisController
import ai.jarvis.core.JarvisUiState
import ai.jarvis.data.AnimationMode
import ai.jarvis.data.JarvisSettings
import ai.jarvis.data.PROVIDER_PRESETS
import ai.jarvis.data.ProviderPreset
import ai.jarvis.data.ProviderTier
import ai.jarvis.data.VOICE_PRESETS
import ai.jarvis.data.VoiceInputMode
import ai.jarvis.data.presetFor
import ai.jarvis.data.providerPresetFor
import ai.jarvis.permissions.Permissions
import ai.jarvis.tools.CapabilityStatus
import ai.jarvis.tools.Confirmation
import ai.jarvis.tools.Tool
import ai.jarvis.ui.theme.JarvisAmber
import ai.jarvis.ui.theme.JarvisCyan
import ai.jarvis.ui.theme.JarvisGold
import ai.jarvis.ui.theme.JarvisMuted
import ai.jarvis.ui.theme.JarvisRed
import ai.jarvis.ui.theme.JarvisSurface
import ai.jarvis.ui.theme.JarvisText
import ai.jarvis.ui.theme.MonoBody
import ai.jarvis.ui.theme.MonoSmall
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    controller: JarvisController,
    settings: JarvisSettings,
    uiState: JarvisUiState,
    onDismiss: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tools = remember { controller.tools() }
    val identity by controller.identity.collectAsStateWithLifecycle()
    val memoryState by controller.memory.collectAsStateWithLifecycle()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { controller.exportMemory(it) }
        }
    var memoryQuery by remember { mutableStateOf("") }
    var newMemory by remember { mutableStateOf("") }
    var newMemoryKind by remember { mutableStateOf("long_term") }
    var confirmClearMemory by remember { mutableStateOf(false) }

    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    val activeProvider = providerPresetFor(settings.baseUrl)
    val providerLabel = activeProvider?.label ?: "custom endpoint"
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var fallback by remember(settings.fallbackModel) { mutableStateOf(settings.fallbackModel) }
    var rate by remember(settings.rate) { mutableFloatStateOf(settings.rate) }
    var pitch by remember(settings.pitch) { mutableFloatStateOf(settings.pitch) }
    var volume by remember(settings.volume) { mutableFloatStateOf(settings.volume) }
    var webhook by remember(settings.webhookUrl) { mutableStateOf(settings.webhookUrl) }
    var wake by remember(settings.wakeSensitivity) { mutableFloatStateOf(settings.wakeSensitivity) }
    var githubToken by remember(settings.githubToken) { mutableStateOf(settings.githubToken) }
    var githubOwner by remember(settings.githubOwner) { mutableStateOf(settings.githubOwner) }
    var githubRepo by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }

    val keyboard = LocalSoftwareKeyboardController.current
    var keyStatus by remember { mutableStateOf<String?>(null) }
    var keyStatusOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>>(emptyList()) }

    fun saveKey() {
        val ok = controller.saveApiKey(apiKey)
        keyStatusOk = ok
        keyStatus =
            if (ok) {
                "Key saved for $providerLabel and verified in the Keystore."
            } else {
                "Key was not saved. Check the value and try again."
            }
        if (ok) apiKey = ""
        keyboard?.hide()
    }

    fun testKey() {
        testing = true
        keyStatus = "Testing ${baseUrl.ifBlank { settings.baseUrl }} …"
        controller.testProvider(baseUrl, apiKey, model) { result ->
            testing = false
            keyStatusOk = result.ok
            keyStatus = result.message
            if (result.ok && result.models.isNotEmpty()) discoveredModels = result.models
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = JarvisSurface,
    ) {
        LazyColumn(
            modifier = Modifier.imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("SETTINGS", fontSize = 12.sp, letterSpacing = 4.sp, color = JarvisText)
            }

            // ---------------- Verified self-model ----------------
            item { SectionTitle("SELF-MODEL") }
            item { IdentityBlock(identity) }
            item { Divider() }

            // ---------------- Engine ----------------
            item { SectionTitle("REASONING ENGINE") }
            item {
                ProviderRow(
                    header = "FREE TIER",
                    presets = PROVIDER_PRESETS.filter { it.tier == ProviderTier.FREE },
                    currentBaseUrl = settings.baseUrl,
                ) { preset ->
                    baseUrl = preset.baseUrl
                    model = preset.model
                    fallback = preset.fallback
                    apiKey = ""
                    keyStatus = null
                    discoveredModels = emptyList()
                    controller.saveEndpoint(preset.baseUrl, preset.model, preset.fallback)
                }
            }
            item {
                ProviderRow(
                    header = "PAID / OWN KEY",
                    presets = PROVIDER_PRESETS.filter { it.tier == ProviderTier.PAID },
                    currentBaseUrl = settings.baseUrl,
                ) { preset ->
                    baseUrl = preset.baseUrl
                    model = preset.model
                    fallback = preset.fallback
                    apiKey = ""
                    keyStatus = null
                    discoveredModels = emptyList()
                    controller.saveEndpoint(preset.baseUrl, preset.model, preset.fallback)
                }
            }
            item {
                Text(
                    "$providerLabel: ${activeProvider?.note ?: "Type any OpenAI-compatible base URL below."}",
                    style = MonoSmall,
                    color = JarvisMuted,
                )
                activeProvider?.let { preset ->
                    TextButton(onClick = { openUrl(context, preset.consoleUrl) }) {
                        Text("GET A KEY \u2197", style = MonoSmall, color = JarvisCyan)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        keyStatus = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MonoBody,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
                    keyboardActions = KeyboardActions(onDone = { saveKey() }),
                    label = { Text("API key \u00b7 $providerLabel", style = MonoSmall) },
                    placeholder = {
                        Text(
                            if (settings.hasApiKey) {
                                "Saved in Android Keystore"
                            } else {
                                "sk-…"
                            },
                            style = MonoSmall,
                        )
                    },
                    supportingText = {
                        Text(
                            "Optional: memory, Settings and device tools work without a key. Paste this " +
                                "provider's key, then press SAVE KEY; each provider keeps its own key under a " +
                                "non-exportable Keystore key, never in plain preferences.",
                            style = MonoSmall,
                        )
                    },
                    colors = fieldColors(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { saveKey() },
                        enabled = apiKey.isNotBlank(),
                    ) {
                        Text(
                            "SAVE KEY",
                            style = MonoSmall,
                            color = if (apiKey.isNotBlank()) JarvisCyan else JarvisMuted,
                        )
                    }
                    TextButton(
                        onClick = { testKey() },
                        enabled = !testing && (apiKey.isNotBlank() || settings.hasApiKey),
                    ) { Text(if (testing) "TESTING…" else "TEST CONNECTION", style = MonoSmall, color = JarvisGold) }
                }
                Text(
                    if (settings.hasApiKey) "Key stored for $providerLabel" else "No key stored for $providerLabel",
                    style = MonoSmall,
                    color = if (settings.hasApiKey) JarvisCyan else JarvisAmber,
                )
                keyStatus?.let { message ->
                    Text(
                        message,
                        style = MonoSmall,
                        color = if (keyStatusOk) JarvisCyan else JarvisRed,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MonoBody,
                    label = { Text("Base URL (OpenAI-compatible)", style = MonoSmall) },
                    colors = fieldColors(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MonoBody,
                        label = { Text("Model", style = MonoSmall) },
                        colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = fallback,
                        onValueChange = { fallback = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MonoBody,
                        label = { Text("Fallback", style = MonoSmall) },
                        colors = fieldColors(),
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        controller.saveEndpoint(baseUrl, model, fallback)
                    }) { Text("APPLY & RESTART ENGINE", style = MonoSmall, color = JarvisCyan) }
                }
                Text(
                    "Presets only fill the endpoint. Every entry above speaks the OpenAI-compatible chat API, " +
                        "so one key per provider is all it needs. Model IDs must exist for your key — use " +
                        "TEST CONNECTION to read the ones your key allows.",
                    style = MonoSmall,
                )
                if (discoveredModels.isNotEmpty()) {
                    Text(
                        "Models allowed for this key (tap to use):",
                        style = MonoSmall,
                        color = JarvisGold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        discoveredModels.forEach { id ->
                            FilterChip(
                                selected = model == id,
                                onClick = {
                                    model = id
                                    controller.saveEndpoint(baseUrl, id, fallback)
                                },
                                label = { Text(id, style = MonoSmall) },
                                colors = chipColors(),
                            )
                        }
                    }
                }
            }

            item { Divider() }

            // ---------------- Voice ----------------
            item { SectionTitle("VOICE") }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VOICE_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = settings.voicePresetId == preset.id,
                            onClick = {
                                controller.saveVoice(preset.id, rate, pitch, volume)
                            },
                            label = { Text(preset.label, style = MonoSmall) },
                            colors = chipColors(),
                        )
                    }
                }
                Text(
                    presetFor(settings.voicePresetId).description,
                    style = MonoSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                LabelledSlider("Rate", rate, 0.6f, 1.6f) { rate = it }
                LabelledSlider("Pitch", pitch, 0.5f, 1.5f) { pitch = it }
                LabelledSlider("Volume", volume, 0f, 1f) { volume = it }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Male voice", style = MonoSmall, color = JarvisCyan)
                        Text(
                            "Picks the deepest male voice installed for this language (a lower pitch if there is none).",
                            style = MonoSmall,
                        )
                    }
                    Switch(
                        checked = settings.maleVoice,
                        onCheckedChange = { controller.setMaleVoice(it) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        controller.saveVoice(settings.voicePresetId, rate, pitch, volume)
                    }) { Text("SAVE VOICE", style = MonoSmall, color = JarvisCyan) }
                    TextButton(onClick = {
                        controller.saveVoice(settings.voicePresetId, rate, pitch, volume)
                        controller.speakTestLine()
                    }) { Text("SPEAK TEST LINE", style = MonoSmall, color = JarvisGold) }
                }
            }

            item { Divider() }

            // ---------------- Input ----------------
            item { SectionTitle("VOICE INPUT") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceInputMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.voiceInput == mode,
                            onClick = { controller.setVoiceInput(mode) },
                            label = {
                                Text(
                                    if (mode == VoiceInputMode.SYSTEM) "System recogniser" else "On-device (Rust)",
                                    style = MonoSmall,
                                )
                            },
                            colors = chipColors(),
                        )
                    }
                }
                Text(
                    if (settings.voiceInput == VoiceInputMode.SYSTEM) {
                        "Android's own recogniser, on-device where the phone has a working model and on the network otherwise."
                    } else {
                        "Rust VAD + Whisper. Requires a build with --features whisper and ggml-base.en.bin; otherwise voice reports a clear configuration error."
                    },
                    style = MonoSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wake word \"Jarvis\"", style = MonoSmall, color = JarvisCyan)
                        Text(
                            "Keeps the microphone open in a foreground service, so saying \"Jarvis ...\" " +
                                "works with the app closed. Uses battery; on MIUI allow JARVIS to autostart " +
                                "so it comes back after a reboot.",
                            style = MonoSmall,
                        )
                    }
                    Switch(
                        checked = settings.wakeWordEnabled,
                        onCheckedChange = { controller.setWakeWordEnabled(it) },
                    )
                }
            }

            item { Divider() }

            // ---------------- Presentation ----------------
            item { SectionTitle("PRESENTATION") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.animationMode == mode,
                            onClick = { controller.setAnimationMode(mode) },
                            label = {
                                Text(
                                    if (mode == AnimationMode.ORB) "Arc reactor" else "Character face",
                                    style = MonoSmall,
                                )
                            },
                            colors = chipColors(),
                        )
                    }
                }
                Text(
                    "System animation scale is respected: with animations disabled the orb renders a static state ring and the face holds still.",
                    style = MonoSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                LabelledSlider("Wake sensitivity", wake, 0f, 1f) {
                    wake = it
                    controller.setWakeSensitivity(it)
                }
                Text(
                    "Phrase matching runs through the platform speech recogniser, so it may use a " +
                        "network. The on-device openWakeWord detector is not compiled into this build; " +
                        "MIC remains push-to-talk.",
                    style = MonoSmall,
                )
            }

            item { Divider() }

            // ---------------- Smart home ----------------
            item { SectionTitle("SMART HOME") }
            item {
                OutlinedTextField(
                    value = webhook,
                    onValueChange = { webhook = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MonoBody,
                    label = { Text("HTTPS webhook", style = MonoSmall) },
                    placeholder = { Text("https://home.example/api/jarvis", style = MonoSmall) },
                    supportingText = {
                        Text("control_device POSTs {\"device\",\"action\",\"value\"} here.", style = MonoSmall)
                    },
                    colors = fieldColors(),
                )
                TextButton(onClick = { controller.setWebhook(webhook) }) {
                    Text("SAVE WEBHOOK", style = MonoSmall, color = JarvisCyan)
                }
            }

            item { Divider() }

            // ---------------- Trust ----------------
            item { SectionTitle("TRUSTED MODE") }
            item {
                Text(
                    "Skipping confirmation is per tool and never global. Outgoing messages and calls default to asking.",
                    style = MonoSmall,
                )
            }
            items(
                tools.filter { it.spec.confirmation == Confirmation.REQUIRED },
                key = { "trust-${it.spec.name}" },
            ) { tool ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tool.spec.name, style = MonoBody, color = JarvisText)
                        if (tool.spec.restricted) {
                            Text("Play-restricted capability", style = MonoSmall, color = JarvisAmber)
                        }
                    }
                    Switch(
                        checked = tool.spec.name in settings.trustedTools,
                        onCheckedChange = { controller.setTrusted(tool.spec.name, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = JarvisCyan),
                    )
                }
            }

            item { Divider() }

            // ---------------- Capabilities ----------------
            item { SectionTitle("CAPABILITIES") }
            item {
                Text(
                    "Green: granted. Amber: requested at first use. Accessibility and notification access are opt-in and shown in system settings while active.",
                    style = MonoSmall,
                )
            }
            items(tools, key = { "cap-${it.spec.name}" }) { tool ->
                CapabilityRow(
                    tool = tool,
                    status = controller.capabilityStatus(tool),
                    onGrant = {
                        when {
                            tool.spec.name in NOTIFICATION_TOOLS -> controller.openNotificationAccess()
                            tool.spec.name in ACCESSIBILITY_TOOLS -> controller.openAccessibilitySettings()
                            else -> {
                                val special = tool.spec.permissions.firstOrNull { Permissions.isSpecial(it) }
                                if (special != null) {
                                    controller.openSpecialAccess(special)
                                } else {
                                    val missing =
                                        tool.spec.permissions.filter {
                                            it != Manifest.permission.QUERY_ALL_PACKAGES &&
                                                !Permissions.granted(context, it)
                                        }
                                    if (missing.isNotEmpty()) onRequestPermissions(missing)
                                }
                            }
                        }
                    },
                )
            }

            item {
                Text(
                    "Google Play policy: call log, SMS and accessibility are restricted permissions. A public listing needs a declared core use, a Permissions Declaration Form, and may still be rejected. This build is intended for sideloaded/personal use.",
                    style = MonoSmall,
                    color = JarvisAmber,
                )
            }

            item { Divider() }

            // ---------------- Persona ----------------
            item { SectionTitle("PERSONA") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Inject self-model", style = MonoBody, color = JarvisText)
                        Text(
                            "The model is told what it can actually do on this phone: app and core " +
                                "versions, the active reasoning backend, granted and missing capabilities, " +
                                "and known limits. It never claims an unavailable capability or a " +
                                "successful action without a tool result.",
                            style = MonoSmall,
                            color = JarvisMuted,
                        )
                    }
                    Switch(
                        checked = settings.selfAware,
                        onCheckedChange = { controller.setSelfAware(it) },
                    )
                }
            }
            item {
                Text(
                    "The switch controls the persona and continuity lines. The verified facts are " +
                        "still sent when it is off, so the model cannot claim abilities this build lacks.",
                    style = MonoSmall,
                    color = JarvisAmber,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            item { Divider() }

            // ---------------- Auto-send messages ----------------
            item { SectionTitle("AUTO-SEND MESSAGES") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Send messages without confirmation", style = MonoBody, color = JarvisText)
                        Text(
                            "When enabled, messaging tools (SMS, WhatsApp, etc.) send automatically " +
                                "without asking you to confirm first. Turn off if you want to approve each message.",
                            style = MonoSmall,
                            color = JarvisMuted,
                        )
                    }
                    Switch(
                        checked = settings.autoSendMessages,
                        onCheckedChange = { controller.setAutoSendMessages(it) },
                    )
                }
            }
            item {
                Text(
                    "SMS: sends directly, no tap needed.\n" +
                        "WhatsApp: opens with message pre-filled, may need one tap.\n" +
                        "Messenger/Telegram/Signal/Instagram: opens the app with the message ready to send. " +
                        "These apps vary - some need you to tap send inside the app.\n\n" +
                        "Note: Android security prevents fully automatic sending to third-party apps. " +
                        "Only SMS can send without any user interaction. For other apps, the best we can do " +
                        "is open them with the message prepared - you may still need to tap send.",
                    style = MonoSmall,
                    color = JarvisAmber,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            item { Divider() }

            // ---------------- Local encrypted memory ----------------
            item { SectionTitle("LOCAL MEMORY (ENCRYPTED)") }
            item {
                Text(
                    "Stored on this phone with AES-256-GCM under the Keystore data key. " +
                        "${memoryState.active} active record(s)" +
                        if (memoryState.corrupt > 0) {
                            " \u00b7 ${memoryState.corrupt} corrupted record(s) quarantined"
                        } else {
                            ""
                        },
                    style = MonoSmall,
                    color = JarvisMuted,
                )
            }
            item {
                OutlinedTextField(
                    value = memoryQuery,
                    onValueChange = {
                        memoryQuery = it
                        controller.searchMemory(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MonoBody,
                    label = { Text("Search memory", style = MonoSmall) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.searchMemory(memoryQuery) }),
                    colors = fieldColors(),
                )
            }
            if (memoryState.error != null) {
                item {
                    Text(memoryState.error.orEmpty(), style = MonoSmall, color = JarvisRed)
                }
            }
            items(memoryState.entries.take(10), key = { "mem-${it.id}" }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${entry.kind.replace('_', ' ')} \u00b7 ${(entry.importance * 100).toInt()}%" +
                                if (entry.approved) " \u00b7 approved" else "",
                            style = MonoSmall,
                            color = JarvisCyan,
                        )
                        Text(
                            entry.text,
                            style = MonoBody,
                            color = JarvisText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { controller.deleteMemory(entry.id) }) {
                        Text("DELETE", style = MonoSmall, color = JarvisRed)
                    }
                }
            }
            if (memoryState.entries.isEmpty()) {
                item { Text("Nothing remembered yet.", style = MonoSmall, color = JarvisMuted) }
            }
            item {
                OutlinedTextField(
                    value = newMemory,
                    onValueChange = { newMemory = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MonoBody,
                    label = { Text("Remember a fact", style = MonoSmall) },
                    colors = fieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "long_term" to "LONG-TERM",
                        "preference" to "PREFERENCE",
                        "goal" to "GOAL",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = newMemoryKind == id,
                            onClick = { newMemoryKind = id },
                            label = { Text(label, style = MonoSmall) },
                            colors = chipColors(),
                        )
                    }
                }
                TextButton(
                    onClick = {
                        controller.addMemory(newMemory, newMemoryKind)
                        newMemory = ""
                    },
                    enabled = newMemory.isNotBlank(),
                ) {
                    Text("SAVE MEMORY", style = MonoSmall, color = JarvisCyan)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { exportLauncher.launch("jarvis-memory.json") },
                        enabled = memoryState.entries.isNotEmpty(),
                    ) {
                        Text("EXPORT", style = MonoSmall, color = JarvisGold)
                    }
                    TextButton(
                        onClick = { confirmClearMemory = true },
                        enabled = memoryState.entries.isNotEmpty(),
                    ) {
                        Text("CLEAR ALL", style = MonoSmall, color = JarvisRed)
                    }
                }
            }

            item { Divider() }

            // ---------------- Optional GitHub export ----------------
            item { SectionTitle("ONLINE MEMORY (GITHUB)") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mirror conversations to GitHub", style = MonoBody, color = JarvisText)
                        Text(
                            "Optional and off by default. Local encrypted memory above is the primary " +
                                "store; this mirrors turns to conversations/memory.json as plaintext in " +
                                "the repository you configure.",
                            style = MonoSmall,
                            color = JarvisMuted,
                        )
                    }
                    Switch(
                        checked = settings.autoSync,
                        onCheckedChange = { controller.setAutoSync(it) },
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MonoBody,
                    label = { Text("GitHub token", style = MonoSmall) },
                    placeholder = { Text("ghp_... or github_pat_...", style = MonoSmall) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = githubOwner,
                        onValueChange = { githubOwner = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MonoBody,
                        label = { Text("Owner / Org", style = MonoSmall) },
                        colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = githubRepo,
                        onValueChange = { githubRepo = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MonoBody,
                        label = { Text("Repo name", style = MonoSmall) },
                        colors = fieldColors(),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            controller.setGitHubConfig(githubToken, githubOwner, githubRepo)
                            controller.testGitHubConnection()
                        },
                        enabled = githubToken.isNotBlank() &&
                            githubOwner.isNotBlank() &&
                            githubRepo.isNotBlank(),
                    ) {
                        Text("TEST CONNECTION", style = MonoSmall, color = JarvisGold)
                    }
                    TextButton(
                        onClick = {
                            controller.setGitHubConfig(githubToken, githubOwner, githubRepo)
                            controller.pushToGitHub()
                        },
                        enabled = githubToken.isNotBlank() &&
                            githubOwner.isNotBlank() &&
                            githubRepo.isNotBlank(),
                    ) {
                        Text("PUSH NOW", style = MonoSmall, color = JarvisCyan)
                    }
                }
                Text(
                    "Token: GitHub → Settings → Developer settings → Personal access tokens (classic), " +
                        "repo scope. Save with TEST CONNECTION once; every turn is then appended to " +
                        "conversations/memory.json automatically.",
                    style = MonoSmall,
                    color = JarvisMuted,
                )
            }

            item { Divider() }

            // ---------------- Diagnostics ----------------
            item { SectionTitle("DEBUG: LATENCY") }
            items(uiState.latency.takeLast(8)) { sample ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(sample.stage, style = MonoSmall, color = JarvisMuted, modifier = Modifier.weight(1f))
                    Text("%.0f ms".format(sample.ms), style = MonoSmall, color = JarvisCyan)
                }
            }
            if (uiState.latency.isEmpty()) {
                item { Text("No turns yet.", style = MonoSmall) }
            }

            item { SectionTitle("DEBUG: TOOL TRACES") }
            items(uiState.traces.take(12)) { trace ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row {
                        Text(trace.tool, style = MonoSmall, color = if (trace.ok) JarvisCyan else JarvisRed)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "%s · %.0f ms".format(
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(trace.at)),
                                trace.latencyMs,
                            ),
                            style = MonoSmall,
                        )
                    }
                    Text(trace.arguments, style = MonoSmall, color = JarvisMuted, maxLines = 2)
                    Text(trace.result, style = MonoSmall, color = JarvisText, maxLines = 3)
                    HorizontalDivider(color = Color(0xFF12202A))
                }
            }
            item {
                Text(
                    "Traces are also appended to an AES-256-GCM encrypted local log (jarvis/transcript.log).",
                    style = MonoSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (confirmClearMemory) {
        AlertDialog(
            onDismissRequest = { confirmClearMemory = false },
            containerColor = JarvisSurface,
            title = { Text("CLEAR LOCAL MEMORY", style = MonoSmall, color = JarvisRed) },
            text = {
                Text(
                    "Delete every locally stored memory? This cannot be undone.",
                    style = MonoBody,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearMemory()
                    confirmClearMemory = false
                }) {
                    Text("DELETE ALL", style = MonoSmall, color = JarvisRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearMemory = false }) {
                    Text("CANCEL", style = MonoSmall, color = JarvisMuted)
                }
            },
        )
    }
}

@Composable
private fun IdentityBlock(identity: IdentitySnapshot) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "${identity.name} ${identity.appVersion} \u00b7 core ${identity.coreVersion}",
            style = MonoBody,
            color = JarvisText,
        )
        Text(
            when (identity.backend) {
                BackendKind.REMOTE ->
                    "Reasoning: remote \u00b7 ${identity.model.ifBlank { "no model named" }} \u00b7 " +
                        identity.providerLabel.ifBlank { "custom endpoint" }
                BackendKind.LOCAL -> "Reasoning: local \u00b7 ${identity.model.ifBlank { "no model named" }}"
                BackendKind.NONE -> "Reasoning: none configured"
            },
            style = MonoSmall,
            color = JarvisMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "${identity.availableCount} capabilities available \u00b7 " +
                "${identity.toolUsableCount()}/${identity.tools.size} tools usable now",
            style = MonoSmall,
            color = JarvisMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        val blocked = identity.registry.blocked()
        if (blocked.isNotEmpty()) {
            Text(
                "Not available: " + blocked.take(6).joinToString(", ") { it.label },
                style = MonoSmall,
                color = JarvisAmber,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        identity.limitations.take(4).forEach { limit ->
            Text(
                "\u00b7 $limit",
                style = MonoSmall,
                color = JarvisMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    tool: Tool,
    status: CapabilityStatus,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .background(
                        when (status) {
                            CapabilityStatus.GRANTED -> Color(0xFF4DE1A5)
                            CapabilityStatus.NEEDS_GRANT -> JarvisAmber
                            CapabilityStatus.BLOCKED -> JarvisRed
                        },
                        CircleShape,
                    ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(tool.spec.name, style = MonoSmall, color = JarvisText)
            val permissions =
                if (tool.spec.permissions.isEmpty()) {
                    "no permission"
                } else {
                    tool.spec.permissions.joinToString { it.substringAfterLast('.') }
                }
            Text(
                if (tool.spec.restricted) "$permissions · Play-restricted" else permissions,
                style = MonoSmall,
                color = if (tool.spec.restricted) JarvisAmber else JarvisMuted,
            )
        }
        if (status != CapabilityStatus.GRANTED) {
            TextButton(onClick = onGrant) {
                Text("GRANT", style = MonoSmall, color = JarvisCyan)
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MonoSmall, modifier = Modifier.width(108.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
            colors =
                SliderDefaults.colors(
                    thumbColor = JarvisCyan,
                    activeTrackColor = JarvisCyan.copy(alpha = 0.5f),
                    inactiveTrackColor = Color(0xFF1A2933),
                ),
        )
        Text("%.2f".format(value), style = MonoSmall, modifier = Modifier.width(46.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MonoSmall, color = JarvisGold)
}

@Composable
private fun Divider() {
    HorizontalDivider(color = Color(0xFF12202A), modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun fieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisCyan.copy(alpha = 0.5f),
        unfocusedBorderColor = Color(0xFF16232E),
        cursorColor = JarvisCyan,
        focusedTextColor = JarvisText,
        unfocusedTextColor = JarvisText,
    )

@Composable
private fun chipColors() =
    FilterChipDefaults.filterChipColors(
        selectedContainerColor = JarvisCyan.copy(alpha = 0.18f),
        selectedLabelColor = JarvisCyan,
    )

@Composable
private fun ProviderRow(
    header: String,
    presets: List<ProviderPreset>,
    currentBaseUrl: String,
    onPick: (ProviderPreset) -> Unit,
) {
    Text(header, style = MonoSmall, color = JarvisMuted, modifier = Modifier.padding(bottom = 4.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets, key = { "provider-${it.id}" }) { preset ->
            FilterChip(
                selected = providerPresetFor(currentBaseUrl)?.id == preset.id,
                onClick = { onPick(preset) },
                label = { Text(preset.label, style = MonoSmall) },
                colors = chipColors(),
            )
        }
    }
}

private fun openUrl(
    context: android.content.Context,
    url: String,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private val NOTIFICATION_TOOLS = setOf("read_notifications", "dismiss_notification")
private val ACCESSIBILITY_TOOLS = setOf("read_screen_text", "tap_element", "scroll", "close_app")
