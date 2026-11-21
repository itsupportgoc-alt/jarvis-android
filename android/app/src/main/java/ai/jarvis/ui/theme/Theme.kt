package ai.jarvis.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Arc-reactor palette. Used sparingly: glow, accent, and one warm highlight.
val JarvisCyan = Color(0xFF5FE6FF)
val JarvisCyanDim = Color(0xFF1C6B7C)
val JarvisGold = Color(0xFFFFC870)
val JarvisAmber = Color(0xFFFFA63D)
val JarvisRed = Color(0xFFFF5D5D)
val JarvisInk = Color(0xFF04070C)
val JarvisSurface = Color(0xFF0A1119)
val JarvisLine = Color(0xFF16232E)
val JarvisText = Color(0xFFDDEBF4)
val JarvisMuted = Color(0xFF7E93A3)

private val Scheme =
    darkColorScheme(
        primary = JarvisCyan,
        onPrimary = JarvisInk,
        secondary = JarvisGold,
        onSecondary = JarvisInk,
        background = JarvisInk,
        onBackground = JarvisText,
        surface = JarvisSurface,
        onSurface = JarvisText,
        surfaceVariant = JarvisLine,
        onSurfaceVariant = JarvisMuted,
        error = JarvisRed,
        onError = JarvisInk,
        outline = JarvisLine,
    )

private val JarvisTypography =
    Typography().let { base ->
        base.copy(
            displayLarge =
                base.displayLarge.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 6.sp,
                ),
            titleMedium =
                base.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                ),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }

val MonoSmall =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 1.4.sp,
        color = JarvisMuted,
    )

val MonoBody =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = JarvisText,
    )

/**
 * System-wide animation preference. Read from Settings.Global and observed live,
 * because `ANIMATOR_DURATION_SCALE == 0` means accessibility has asked for no
 * motion: the orb then renders a static state ring instead of animating.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberAnimationsEnabled(): State<Boolean> {
    val context = LocalContext.current
    val enabled = remember { mutableStateOf(readAnimationScale(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled.value = readAnimationScale(context)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun readAnimationScale(context: android.content.Context): Boolean =
    runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }.getOrDefault(true)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    // Dark only by design: a light variant would fight the orb's glow.
    @Suppress("UNUSED_VARIABLE")
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = Scheme,
        typography = JarvisTypography,
        content = content,
    )
}
