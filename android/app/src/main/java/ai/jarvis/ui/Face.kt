package ai.jarvis.ui

import ai.jarvis.core.Phase
import ai.jarvis.ui.theme.JarvisAmber
import ai.jarvis.ui.theme.JarvisCyan
import ai.jarvis.ui.theme.JarvisGold
import ai.jarvis.ui.theme.JarvisInk
import ai.jarvis.ui.theme.JarvisRed
import ai.jarvis.ui.theme.LocalAnimationsEnabled
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private enum class Mouth { CLOSED, NARROW, WIDE, ROUND, TEETH, FV, L, SMILE }

/**
 * Character mode.
 *
 * The face is layered vector shapes: head, brows, eyes, pupils and mouth, with
 * an arc-reactor halo where the orb used to be. Blinks are randomised every
 * 3-7 s, the head sways slowly, pupils drift. The mouth is driven by the TTS
 * word currently being spoken (real range callbacks) plus the syllable envelope;
 * no canned lip-sync loop.
 *
 * When system animations are disabled the face renders a still, open-eyed
 * portrait with a resting mouth.
 */
@Composable
fun JarvisFace(
    phase: Phase,
    level: Float,
    mood: String,
    word: String,
    wordPhase: Float,
    modifier: Modifier = Modifier,
) {
    val animations = LocalAnimationsEnabled.current
    val seconds = remember { mutableFloatStateOf(0f) }
    val blink = remember { Animatable(0f) }

    LaunchedEffect(animations, phase) {
        if (!animations) {
            seconds.floatValue = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) seconds.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    LaunchedEffect(animations) {
        if (!animations) return@LaunchedEffect
        while (isActive) {
            delay(Random.nextLong(3_000, 7_000))
            blink.animateTo(1f, tween(70, easing = LinearEasing))
            blink.animateTo(0f, tween(110, easing = LinearEasing))
            // Occasional double blink reads as human.
            if (Random.nextFloat() < 0.18f) {
                delay(90)
                blink.animateTo(1f, tween(60))
                blink.animateTo(0f, tween(100))
            }
        }
    }

    val accent =
        when (phase) {
            Phase.IDLE, Phase.LISTENING -> JarvisCyan
            Phase.THINKING -> JarvisAmber
            Phase.SPEAKING -> JarvisGold
            Phase.ERROR -> JarvisRed
        }
    val openAmount = if (animations) level.coerceIn(0f, 1f) else 0f
    val mouth = mouthFor(word, openAmount, mood)

    Canvas(modifier = modifier.size(320.dp)) {
        val t = if (animations) seconds.floatValue else 0f
        val blinkAmount = if (animations) blink.value else 0f
        val w = size.width
        val h = size.height
        val sway = if (animations) sin(t * 0.55f) * w * 0.008f else 0f
        val tilt = if (animations) sin(t * 0.38f) * 1.6f else 0f

        drawHalo(accent, t, openAmount, phase)
        rotate(tilt, Offset(w / 2f, h / 2f)) {
            drawHead(accent, w, h, sway)
            drawBrows(accent, w, h, sway, mood)
            drawEyes(accent, w, h, sway, t, blinkAmount)
            drawNose(w, h, sway)
            drawMouth(accent, w, h, sway, mouth, openAmount)
        }
    }
}

private fun DrawScope.drawHalo(
    accent: Color,
    t: Float,
    level: Float,
    phase: Phase,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) / 2f
    val breathe = 0.5f + 0.5f * sin(t * 0.9f)
    drawCircle(accent.copy(alpha = 0.035f), radius * 0.95f, center)
    drawCircle(accent.copy(alpha = 0.05f), radius * 0.82f, center)
    drawCircle(accent.copy(alpha = 0.55f), radius * 0.70f, center, style = Stroke(width = 1.2f))
    drawArc(
        color = accent.copy(alpha = 0.75f),
        startAngle = (t * 40f) % 360f,
        sweepAngle = 54f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.70f, center.y - radius * 0.70f),
        size = Size(radius * 1.4f, radius * 1.4f),
        style = Stroke(width = 2.2f, cap = StrokeCap.Round),
    )
    if (phase == Phase.SPEAKING) {
        drawCircle(
            accent.copy(alpha = 0.10f + level * 0.18f),
            radius * (0.58f + level * 0.05f),
            center,
        )
    } else if (phase == Phase.THINKING) {
        drawCircle(accent.copy(alpha = 0.06f * (0.5f + breathe)), radius * 0.62f, center)
    }
}

private fun DrawScope.drawHead(
    accent: Color,
    w: Float,
    h: Float,
    sway: Float,
) {
    val cx = w / 2f + sway
    val cy = h / 2f - h * 0.02f
    val headW = w * 0.42f
    val headH = h * 0.50f
    // Skull and jaw as two ovals; a hologram, not skin.
    drawOval(
        color = Color(0xFF08131B),
        topLeft = Offset(cx - headW / 2f, cy - headH / 2f),
        size = Size(headW, headH),
    )
    drawOval(
        color = accent.copy(alpha = 0.35f),
        topLeft = Offset(cx - headW / 2f, cy - headH / 2f),
        size = Size(headW, headH),
        style = Stroke(width = 1.4f),
    )
    // Jaw taper.
    drawOval(
        color = Color(0xFF08131B),
        topLeft = Offset(cx - headW * 0.32f, cy + headH * 0.10f),
        size = Size(headW * 0.64f, headH * 0.46f),
    )
    drawOval(
        color = accent.copy(alpha = 0.20f),
        topLeft = Offset(cx - headW * 0.32f, cy + headH * 0.10f),
        size = Size(headW * 0.64f, headH * 0.46f),
        style = Stroke(width = 1f),
    )
    // Temple guides, the only decorative lines.
    drawLine(
        color = accent.copy(alpha = 0.18f),
        start = Offset(cx - headW * 0.40f, cy - headH * 0.20f),
        end = Offset(cx - headW * 0.52f, cy - headH * 0.06f),
        strokeWidth = 1f,
    )
    drawLine(
        color = accent.copy(alpha = 0.18f),
        start = Offset(cx + headW * 0.40f, cy - headH * 0.20f),
        end = Offset(cx + headW * 0.52f, cy - headH * 0.06f),
        strokeWidth = 1f,
    )
}

private fun DrawScope.drawBrows(
    accent: Color,
    w: Float,
    h: Float,
    sway: Float,
    mood: String,
) {
    val cx = w / 2f + sway
    val cy = h / 2f
    val eyeY = cy - h * 0.06f
    val span = w * 0.085f
    // Amused lifts the outer ends; concerned pulls the inner ends down.
    val innerLift =
        when (mood) {
            "amused" -> -h * 0.012f
            "concerned" -> h * 0.014f
            "attentive" -> -h * 0.008f
            else -> 0f
        }
    val outerLift =
        when (mood) {
            "amused" -> -h * 0.020f
            "concerned" -> -h * 0.004f
            "working" -> -h * 0.006f
            else -> 0f
        }
    drawLine(
        color = accent.copy(alpha = 0.8f),
        start = Offset(cx - span * 1.9f, eyeY - h * 0.045f + outerLift),
        end = Offset(cx - span * 0.6f, eyeY - h * 0.052f + innerLift),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = accent.copy(alpha = 0.8f),
        start = Offset(cx + span * 0.6f, eyeY - h * 0.052f + innerLift),
        end = Offset(cx + span * 1.9f, eyeY - h * 0.045f + outerLift),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawEyes(
    accent: Color,
    w: Float,
    h: Float,
    sway: Float,
    t: Float,
    blink: Float,
) {
    val cx = w / 2f + sway
    val cy = h / 2f
    val eyeY = cy - h * 0.058f
    val eyeW = w * 0.085f
    val eyeH = h * 0.034f * (1f - blink * 0.92f)
    if (eyeH < 0.6f) {
        // Closed: a single lid line per eye.
        for (sign in intArrayOf(-1, 1)) {
            drawLine(
                color = accent.copy(alpha = 0.8f),
                start = Offset(cx + sign * w * 0.115f - eyeW / 2f, eyeY),
                end = Offset(cx + sign * w * 0.115f + eyeW / 2f, eyeY),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        }
        return
    }
    val drift = sin(t * 0.6f) * w * 0.004f
    for (sign in intArrayOf(-1, 1)) {
        val ex = cx + sign * w * 0.115f
        drawOval(
            color = Color(0xFF0B1E28),
            topLeft = Offset(ex - eyeW / 2f, eyeY - eyeH / 2f),
            size = Size(eyeW, eyeH),
        )
        drawOval(
            color = accent.copy(alpha = 0.65f),
            topLeft = Offset(ex - eyeW / 2f, eyeY - eyeH / 2f),
            size = Size(eyeW, eyeH),
            style = Stroke(width = 1.3f),
        )
        val irisR = eyeH * 0.62f
        drawCircle(
            color = accent.copy(alpha = 0.85f),
            radius = irisR,
            center = Offset(ex + drift, eyeY),
        )
        drawCircle(
            color = JarvisInk,
            radius = irisR * 0.48f,
            center = Offset(ex + drift, eyeY),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = irisR * 0.20f,
            center = Offset(ex + drift - irisR * 0.3f, eyeY - irisR * 0.3f),
        )
    }
}

private fun DrawScope.drawNose(
    w: Float,
    h: Float,
    sway: Float,
) {
    val cx = w / 2f + sway
    val cy = h / 2f
    drawLine(
        color = JarvisCyan.copy(alpha = 0.16f),
        start = Offset(cx, cy - h * 0.005f),
        end = Offset(cx - w * 0.012f, cy + h * 0.065f),
        strokeWidth = 1.4f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawMouth(
    accent: Color,
    w: Float,
    h: Float,
    sway: Float,
    mouth: Mouth,
    level: Float,
) {
    val cx = w / 2f + sway
    val cy = h / 2f + h * 0.115f
    val openness = (level * 0.055f * h).coerceIn(0f, h * 0.045f)
    val halfWidth =
        w *
            when (mouth) {
                Mouth.WIDE -> 0.070f
                Mouth.ROUND -> 0.042f
                Mouth.TEETH -> 0.060f
                Mouth.FV -> 0.040f
                Mouth.L -> 0.035f
                Mouth.SMILE -> 0.070f
                else -> 0.052f
            }

    when (mouth) {
        Mouth.CLOSED ->
            drawLine(
                color = accent.copy(alpha = 0.75f),
                start = Offset(cx - halfWidth, cy),
                end = Offset(cx + halfWidth, cy),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )

        Mouth.SMILE ->
            drawArc(
                color = accent.copy(alpha = 0.8f),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - halfWidth, cy - halfWidth * 0.55f),
                size = Size(halfWidth * 2f, halfWidth * 0.9f),
                style = Stroke(width = 2f, cap = StrokeCap.Round),
            )

        Mouth.ROUND ->
            drawOval(
                color = accent.copy(alpha = 0.85f),
                topLeft = Offset(cx - halfWidth, cy - openness * 0.7f - h * 0.006f),
                size = Size(halfWidth * 2f, openness + h * 0.012f),
                style = Stroke(width = 2f),
            )

        Mouth.NARROW, Mouth.L ->
            drawOval(
                color = accent.copy(alpha = 0.85f),
                topLeft = Offset(cx - halfWidth, cy - openness * 0.5f),
                size = Size(halfWidth * 2f, openness + 2f),
                style = Stroke(width = 1.8f),
            )

        Mouth.WIDE, Mouth.TEETH, Mouth.FV -> {
            drawOval(
                color = accent.copy(alpha = 0.85f),
                topLeft = Offset(cx - halfWidth, cy - openness * 0.6f),
                size = Size(halfWidth * 2f, openness + h * 0.014f),
                style = Stroke(width = 2f),
            )
            drawLine(
                color = Color(0xFFDDEBF4).copy(alpha = 0.55f),
                start = Offset(cx - halfWidth * 0.75f, cy + h * 0.003f),
                end = Offset(cx + halfWidth * 0.75f, cy + h * 0.003f),
                strokeWidth = 1.4f,
            )
        }
    }
}

/**
 * Same mapping as the Rust `viseme::from_text` helper: the currently spoken
 * word's final phoneme picks the shape, the envelope picks the openness.
 */
private fun mouthFor(
    word: String,
    level: Float,
    mood: String,
): Mouth {
    if (level < 0.07f || word.isBlank()) {
        return if (mood == "amused") Mouth.SMILE else Mouth.CLOSED
    }
    val last = word.lowercase().lastOrNull { it.isLetter() }
    return when (last) {
        'a', 'e' -> Mouth.WIDE
        'o', 'u', 'w' -> Mouth.ROUND
        'i', 'y' -> Mouth.NARROW
        'f', 'v' -> Mouth.FV
        'l' -> Mouth.L
        'm', 'b', 'p' -> Mouth.CLOSED
        's', 't', 'c', 'z' -> Mouth.TEETH
        null -> Mouth.SMILE
        else -> Mouth.NARROW
    }
}
