package ai.jarvis.ui

import ai.jarvis.R
import ai.jarvis.core.JarvisController
import ai.jarvis.core.Phase
import ai.jarvis.data.AnimationMode
import ai.jarvis.data.providerPresetFor
import ai.jarvis.permissions.Permissions
import ai.jarvis.ui.theme.JarvisAmber
import ai.jarvis.ui.theme.JarvisCyan
import ai.jarvis.ui.theme.JarvisGold
import ai.jarvis.ui.theme.JarvisInk
import ai.jarvis.ui.theme.JarvisMuted
import ai.jarvis.ui.theme.JarvisRed
import ai.jarvis.ui.theme.JarvisText
import ai.jarvis.ui.theme.LocalAnimationsEnabled
import ai.jarvis.ui.theme.MonoBody
import ai.jarvis.ui.theme.MonoSmall
import ai.jarvis.ui.theme.rememberAnimationsEnabled
import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisScreen(controller: JarvisController) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val settings by controller.settings.collectAsStateWithLifecycle()
    val animationsEnabled by rememberAnimationsEnabled()
    // The prompt has to name the provider that is actually missing a key, which
    // is not always Freebuff once a preset has been switched.
    val activeProviderLabel = providerPresetFor(settings.baseUrl)?.label ?: "this endpoint"
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val notificationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }

    val micLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val activity = context as? Activity
            val canAskAgain =
                activity
                    ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    ?: true
            controller.onMicPermissionResult(granted, canAskAgain)
            if (granted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    val toolPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

    val accent =
        when (ui.phase) {
            Phase.IDLE, Phase.LISTENING -> JarvisCyan
            Phase.THINKING -> JarvisAmber
            Phase.SPEAKING -> JarvisGold
            Phase.ERROR -> JarvisRed
        }
    val background =
        remember(ui.phase) {
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.13f), JarvisInk, JarvisInk),
                radius = 1600f,
            )
        }

    fun sendText() {
        val text = input.trim()
        if (text.isEmpty()) return
        controller.submitText(text)
        input = ""
        keyboard?.hide()
    }

    CompositionLocalProvider(LocalAnimationsEnabled provides animationsEnabled) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(background)
                    .systemBarsPadding()
                    .imePadding(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
            ) {
                Header(
                    phase = ui.phase,
                    accent = accent,
                    onSettings = { showSettings = true },
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (settings.animationMode == AnimationMode.FACE) {
                            JarvisFace(
                                phase = ui.phase,
                                level = ui.ttsRms,
                                mood = ui.mood,
                                word = ui.speakingWord,
                                wordPhase = ui.speakingPhase,
                            )
                        } else {
                            ArcReactor(
                                phase = ui.phase,
                                level = if (ui.phase == Phase.SPEAKING) ui.ttsRms else ui.micRms,
                            )
                        }
                        Text(
                            text = ui.currentTool ?: phaseHint(ui.phase, ui.muted),
                            style = MonoSmall,
                            color = accent.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        if (ui.needsSetup) {
                            Text(
                                text = "Awaiting credentials. Add a key for $activeProviderLabel in Settings.",
                                style = MonoSmall,
                                color = JarvisMuted,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

                val hasTranscript = ui.lines.isNotEmpty() || ui.streaming.isNotBlank()
                AnimatedVisibility(
                    visible = hasTranscript,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(0.85f)
                            .padding(bottom = 4.dp),
                    enter = slideInVertically { it / 2 } + fadeIn(),
                    exit = slideOutVertically { it / 2 } + fadeOut(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .border(
                                    width = 1.dp,
                                    color = JarvisCyan.copy(alpha = 0.08f),
                                    shape =
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(10.dp),
                                ).padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        TranscriptPanel(
                            lines = ui.lines,
                            partial = ui.partial,
                            streaming = ui.streaming,
                        )
                    }
                }

                AnimatedVisibility(visible = ui.error != null) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(JarvisRed.copy(alpha = 0.10f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ui.error.orEmpty(),
                            style = MonoSmall,
                            color = JarvisRed,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { controller.clearError() }) {
                            Text("DISMISS", style = MonoSmall, color = JarvisRed)
                        }
                    }
                }

                AnimatedVisibility(visible = ui.undoLabel != null) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ui.undoLabel.orEmpty(),
                            style = MonoSmall,
                            color = JarvisMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { controller.performUndo() }) {
                            Text("UNDO", style = MonoSmall, color = JarvisCyan)
                        }
                    }
                }

                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    onSend = ::sendText,
                )

                Controls(
                    phase = ui.phase,
                    muted = ui.muted,
                    accent = accent,
                    onMic = { controller.onMicPressed() },
                    onLongPress = { controller.cancel() },
                    onMute = { controller.toggleMute() },
                    onInterrupt = { controller.cancel() },
                    onSettings = { showSettings = true },
                )
            }
        }

        if (ui.micPermissionNeeded) {
            AlertDialog(
                onDismissRequest = { controller.dismissMicRequest() },
                containerColor = ai.jarvis.ui.theme.JarvisSurface,
                title = { Text("MICROPHONE", style = MonoSmall, color = JarvisCyan) },
                text = {
                    Text(
                        text =
                            if (ui.micBlocked) {
                                stringResource(R.string.rationale_mic_blocked)
                            } else {
                                stringResource(R.string.rationale_mic_body)
                            },
                        style = MonoBody,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (ui.micBlocked) {
                            Permissions.openAppSettings(context)
                            controller.dismissMicRequest()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Text(
                            if (ui.micBlocked) "OPEN SETTINGS" else "ALLOW",
                            style = MonoSmall,
                            color = JarvisCyan,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { controller.dismissMicRequest() }) {
                        Text("TEXT ONLY", style = MonoSmall, color = JarvisMuted)
                    }
                },
            )
        }

        ui.pendingTool?.let { pending ->
            val irreversible = pending.name in setOf("send_sms", "call_contact", "send_whatsapp", "control_device")
            AlertDialog(
                onDismissRequest = { controller.denyPendingTool() },
                containerColor = ai.jarvis.ui.theme.JarvisSurface,
                title = { Text("AUTHORIZATION", style = MonoSmall, color = JarvisAmber) },
                text = {
                    Column {
                        Text("JARVIS wants to ${pending.sentence}.", style = MonoBody)
                        Text(
                            text =
                                if (irreversible) {
                                    "This action cannot be undone."
                                } else {
                                    "A five-second undo appears after it runs."
                                },
                            style = MonoSmall,
                            color = JarvisMuted,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { controller.confirmPendingTool() }) {
                        Text("CONFIRM", style = MonoSmall, color = JarvisCyan)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { controller.denyPendingTool() }) {
                        Text("CANCEL", style = MonoSmall, color = JarvisMuted)
                    }
                },
            )
        }

        if (showSettings) {
            SettingsSheet(
                controller = controller,
                settings = settings,
                uiState = ui,
                onDismiss = { showSettings = false },
                onRequestPermissions = { permissions ->
                    toolPermissionLauncher.launch(permissions.toTypedArray())
                },
            )
        }
    }
}

@Composable
private fun Header(
    phase: Phase,
    accent: Color,
    onSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "J.A.R.V.I.S.",
                fontSize = 13.sp,
                letterSpacing = 6.sp,
                color = JarvisText,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .background(accent, androidx.compose.foundation.shape.CircleShape),
                )
                Text(
                    text = phaseLabel(phase),
                    style = MonoSmall,
                    color = accent.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        TextButton(onClick = onSettings) {
            Text("SETTINGS", style = MonoSmall, color = JarvisMuted)
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MonoBody,
            placeholder = { Text("Type a command", style = MonoSmall) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JarvisCyan.copy(alpha = 0.5f),
                    unfocusedBorderColor = JarvisLineColor,
                    cursorColor = JarvisCyan,
                    focusedTextColor = JarvisText,
                    unfocusedTextColor = JarvisText,
                ),
        )
        TextButton(
            onClick = onSend,
            enabled = value.isNotBlank(),
        ) {
            Text(
                "SEND",
                style = MonoSmall,
                color = if (value.isNotBlank()) JarvisCyan else JarvisMuted,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Controls(
    phase: Phase,
    muted: Boolean,
    accent: Color,
    onMic: () -> Unit,
    onLongPress: () -> Unit,
    onMute: () -> Unit,
    onInterrupt: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Control(
            label = if (phase == Phase.LISTENING) "STOP" else "MIC",
            color = accent,
            onLongClick = onLongPress,
            onClick = onMic,
        )
        Control(
            label = if (muted) "UNMUTE" else "MUTE",
            color = if (muted) JarvisAmber else JarvisMuted,
            onClick = onMute,
        )
        Control(
            label = "INTERRUPT",
            color = if (phase == Phase.THINKING || phase == Phase.SPEAKING) JarvisGold else JarvisMuted,
            onClick = onInterrupt,
        )
        Control(label = "TRACE", color = JarvisMuted, onClick = onSettings)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Control(
    label: String,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(label, style = MonoSmall, color = color, fontSize = 11.sp)
    }
}

private val JarvisLineColor = Color(0xFF16232E)

private fun phaseLabel(phase: Phase): String =
    when (phase) {
        Phase.IDLE -> "STANDBY"
        Phase.LISTENING -> "LISTENING"
        Phase.THINKING -> "THINKING"
        Phase.SPEAKING -> "SPEAKING"
        Phase.ERROR -> "FAULT"
    }

private fun phaseHint(
    phase: Phase,
    muted: Boolean,
): String =
    when {
        muted && phase == Phase.SPEAKING -> "Muted"
        phase == Phase.IDLE -> "Tap MIC or type"
        phase == Phase.LISTENING -> "Go ahead"
        phase == Phase.THINKING -> "Consulting the model"
        phase == Phase.SPEAKING -> "Speaking"
        else -> "Recovering"
    }
