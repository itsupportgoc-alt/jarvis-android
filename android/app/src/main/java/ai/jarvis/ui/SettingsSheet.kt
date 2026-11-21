package ai.jarvis.ui

import ai.jarvis.core.JarvisController
import ai.jarvis.core.JarvisUiState
import ai.jarvis.data.AnimationMode
import ai.jarvis.data.JarvisSettings
import ai.jarvis.data.VOICE_PRESETS
import ai.jarvis.data.VoiceInputMode
import ai.jarvis.data.presetFor
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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var fallback by remember(settings.fallbackModel) { mutableStateOf(settings.fallbackModel) }
    var rate by remember(settings.rate) { mutableFloatStateOf(settings.rate) }
    var pitch by remember(settings.pitch) { mutableFloatStateOf(settings.pitch) }
    var volume by remember(settings.volume) { mutableFloatStateOf(settings.volume) }
    var webhook by remember(settings.webhookUrl) { mutableStateOf(settings.webhookUrl) }
    var wake by remember(settings.wakeSensitivity) { mutableFloatStateOf(settings.wakeSensitivity) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = JarvisSurface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("SETTINGS", fontSize = 12.sp, letterSpacing = 4.sp, color = JarvisText)
            }

            // ---------------- Engine ----------------
            item { SectionTitle("REASONING ENGINE") }
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MonoBody,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Qwen API key", style = MonoSmall) },
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
                            "Stored with a non-exportable Keystore key; never written to Rust or prefs in plaintext.",
                            style = MonoSmall,
                        )
                    },
                    colors = fieldColors(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            controller.saveApiKey(apiKey)
                            apiKey = ""
                        },
                        enabled = apiKey.isNotBlank(),
                    ) { Text("SAVE KEY", style = MonoSmall, color = JarvisCyan) }
                    Text(
                        if (settings.hasApiKey) "Key present" else "No key configured",
                        style = MonoSmall,
                        color = if (settings.hasApiKey) JarvisCyan else JarvisAmber,
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
                    "Default qwen3-30b-a3b falls back to qwen2.5-14b-instruct where latency matters. Model IDs must exist on your endpoint.",
                    style = MonoSmall,
                )
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
                        "Android recogniser with EXTRA_PREFER_OFFLINE. Works out of the box; some devices still require a network."
                    } else {
                        "Rust VAD + Whisper. Requires a build with --features whisper and ggml-base.en.bin; otherwise voice reports a clear configuration error."
                    },
                    style = MonoSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
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
                    "Stored for the on-device openWakeWord detector. Wake word is not compiled in this build (see README); MIC is push-to-talk.",
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

private val NOTIFICATION_TOOLS = setOf("read_notifications", "dismiss_notification")
private val ACCESSIBILITY_TOOLS = setOf("read_screen_text", "tap_element", "scroll", "close_app")
