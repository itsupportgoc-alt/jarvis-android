package ai.jarvis.ui

import ai.jarvis.core.Phase
import ai.jarvis.ui.theme.JarvisAmber
import ai.jarvis.ui.theme.JarvisCyan
import ai.jarvis.ui.theme.JarvisGold
import ai.jarvis.ui.theme.JarvisRed
import ai.jarvis.ui.theme.LocalAnimationsEnabled
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The arc reactor.
 *
 * Drawn entirely with Canvas primitives, no bitmaps and no Lottie. Time comes
 * from `withFrameNanos` into one float; every read of that state inside the draw
 * scope invalidates drawing only, not composition. Geometry and colours are
 * remembered, so the draw path allocates nothing.
 *
 * Amplitude is the real measured level: Rust mic RMS while listening, TTS word
 * envelope while speaking. In idle the rings breathe on their own, which is the
 * only motion not driven by audio.
 */
@Composable
fun ArcReactor(
    phase: Phase,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val animations = LocalAnimationsEnabled.current
    val seconds = remember { mutableFloatStateOf(0f) }
    val smoothLevel = remember { mutableFloatStateOf(0f) }
    val liveLevel = rememberUpdatedState(level.coerceIn(0f, 1f))

    LaunchedEffect(phase, animations) {
        if (!animations) {
            seconds.floatValue = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) {
                    seconds.floatValue += (now - last) / 1_000_000_000f
                }
                last = now
            }
        }
    }

    // Visual smoothing happens in the state, not with allocations per frame.
    LaunchedEffect(animations) {
        if (!animations) return@LaunchedEffect
        while (isActive) {
            val current = smoothLevel.floatValue
            smoothLevel.floatValue += (liveLevel.value - current) * 0.22f
            kotlinx.coroutines.delay(16)
        }
    }

    val accent = accentFor(phase)
    val bars = remember { FloatArray(64) { it / 64f * 2f * PI.toFloat() } }
    val glowRadii = remember { floatArrayOf(0.92f, 0.80f, 0.68f) }
    val period =
        when (phase) {
            Phase.IDLE -> 8f
            Phase.LISTENING -> 6f
            Phase.THINKING -> 2f
            Phase.SPEAKING -> 3f
            Phase.ERROR -> 0.7f
        }

    Canvas(modifier = modifier.size(320.dp)) {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val t = if (animations) seconds.floatValue else 0f
        val amplitude = if (animations) smoothLevel.floatValue else 0f
        val spin = (t / period) * 360f

        drawAmbient(center, radius, accent, amplitude, t, phase)
        drawRings(center, radius, accent, spin, phase, t, amplitude)
        if (phase == Phase.THINKING) {
            drawParticles(center, radius, accent, t)
        }
        drawWaveform(center, radius, accent, amplitude, bars, phase)
        drawCore(center, radius, accent, t, amplitude, phase)
    }
}

private fun accentFor(phase: Phase): Color =
    when (phase) {
        Phase.IDLE, Phase.LISTENING -> JarvisCyan
        Phase.THINKING -> JarvisAmber
        Phase.SPEAKING -> JarvisGold
        Phase.ERROR -> JarvisRed
    }

private fun DrawScope.drawAmbient(
    center: Offset,
    radius: Float,
    accent: Color,
    amplitude: Float,
    t: Float,
    phase: Phase,
) {
    // Layered translucent circles instead of a radial-gradient shader, so no
    // Brush is allocated each frame.
    val pulse = 0.5f + 0.5f * sin(t * 0.9f)
    val baseAlpha =
        when (phase) {
            Phase.IDLE -> 0.030f
            Phase.LISTENING -> 0.045f + amplitude * 0.05f
            Phase.THINKING -> 0.050f
            Phase.SPEAKING -> 0.055f + amplitude * 0.07f
            Phase.ERROR -> 0.06f * (0.4f + 0.6f * pulse)
        }
    drawCircle(accent.copy(alpha = baseAlpha), radius * 0.92f, center)
    drawCircle(accent.copy(alpha = baseAlpha * 0.7f), radius * 0.78f, center)
    drawCircle(accent.copy(alpha = baseAlpha * 0.5f), radius * 0.62f, center)
}

private fun DrawScope.drawRings(
    center: Offset,
    radius: Float,
    accent: Color,
    spin: Float,
    phase: Phase,
    t: Float,
    amplitude: Float,
) {
    val breathe =
        if (phase == Phase.IDLE) {
            1f + 0.02f * sin(t * (2f * PI.toFloat() / 4f))
        } else {
            1f + amplitude * 0.025f
        }
    val outer = radius * 0.70f * breathe
    val inner = radius * 0.56f * breathe
    val flicker =
        if (phase == Phase.ERROR) {
            0.35f + 0.65f * (0.5f + 0.5f * sin(t * 34f))
        } else {
            1f
        }

    rotate(spin, center) {
        drawArc(
            color = accent.copy(alpha = 0.55f * flicker),
            startAngle = 8f,
            sweepAngle = 118f,
            useCenter = false,
            topLeft = Offset(center.x - outer, center.y - outer),
            size = Size(outer * 2f, outer * 2f),
            style = Stroke(width = 1.6f, cap = StrokeCap.Round),
        )
        drawArc(
            color = accent.copy(alpha = 0.30f * flicker),
            startAngle = 168f,
            sweepAngle = 76f,
            useCenter = false,
            topLeft = Offset(center.x - outer, center.y - outer),
            size = Size(outer * 2f, outer * 2f),
            style = Stroke(width = 1.0f, cap = StrokeCap.Round),
        )
    }

    rotate(-spin * 0.7f, center) {
        drawArc(
            color = accent.copy(alpha = 0.45f * flicker),
            startAngle = 32f,
            sweepAngle = 210f,
            useCenter = false,
            topLeft = Offset(center.x - inner, center.y - inner),
            size = Size(inner * 2f, inner * 2f),
            style = Stroke(width = 1.2f, cap = StrokeCap.Round),
        )
    }

    // Shimmer: a short bright arc that sweeps the circumference while thinking.
    if (phase == Phase.THINKING) {
        val sweep = (t * 180f) % 360f
        rotate(sweep, center) {
            drawArc(
                color = accent.copy(alpha = 0.85f),
                startAngle = 0f,
                sweepAngle = 26f,
                useCenter = false,
                topLeft = Offset(center.x - outer * 1.03f, center.y - outer * 1.03f),
                size = Size(outer * 2.06f, outer * 2.06f),
                style = Stroke(width = 2.4f, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawWaveform(
    center: Offset,
    radius: Float,
    accent: Color,
    amplitude: Float,
    angles: FloatArray,
    phase: Phase,
) {
    if (phase != Phase.LISTENING && phase != Phase.SPEAKING) return
    val base = radius * 0.70f
    val maxLength = radius * 0.22f
    for (i in angles.indices) {
        val angle = angles[i]
        // A travelling envelope so the ring blooms rather than pulsing uniformly.
        val bloom = 0.45f + 0.55f * (0.5f + 0.5f * sin(angle * 3f + amplitude * 6f))
        val length = maxLength * amplitude * bloom
        if (length < 1.2f) continue
        val cosA = cos(angle)
        val sinA = sin(angle)
        val start = Offset(center.x + cosA * base, center.y + sinA * base)
        val end = Offset(center.x + cosA * (base + length), center.y + sinA * (base + length))
        drawLine(
            color = accent.copy(alpha = 0.55f + 0.35f * bloom),
            start = start,
            end = end,
            strokeWidth = 1.7f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawCore(
    center: Offset,
    radius: Float,
    accent: Color,
    t: Float,
    amplitude: Float,
    phase: Phase,
) {
    val breath = 1f + 0.02f * sin(t * (2f * PI.toFloat() / 4f))
    val speakPulse = if (phase == Phase.SPEAKING) amplitude * 0.30f else 0f
    val coreRadius = radius * (0.15f * breath + 0.05f * speakPulse)
    drawCircle(accent.copy(alpha = 0.16f), coreRadius * 2.1f, center)
    drawCircle(accent.copy(alpha = 0.30f), coreRadius * 1.5f, center)
    drawCircle(accent.copy(alpha = 0.92f), coreRadius, center)
    drawCircle(Color.White.copy(alpha = 0.55f), coreRadius * 0.38f, center)
}

private fun DrawScope.drawParticles(
    center: Offset,
    radius: Float,
    accent: Color,
    t: Float,
) {
    val orbit = radius * 0.62f
    for (i in 0 until 10) {
        val baseAngle = (i / 10f) * 2f * PI.toFloat()
        val angle = baseAngle + t * (0.6f + i * 0.05f)
        val wobble = 1f + 0.06f * sin(t * 1.7f + i)
        val point =
            Offset(
                center.x + cos(angle) * orbit * wobble,
                center.y + sin(angle) * orbit * wobble,
            )
        drawCircle(
            color = accent.copy(alpha = 0.55f),
            radius = 1.4f + (i % 3) * 0.6f,
            center = point,
        )
    }
}
