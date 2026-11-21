package ai.jarvis.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Three haptics only, so they stay meaningful:
 * one tick on wake, two on confirmation, a longer pulse for force-stop.
 */
class Haptics(
    context: Context,
) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun tick() = pulse(12, 90)

    fun confirm() {
        pulse(10, 140)
        // A second, slightly stronger tick ~90 ms later.
        vibrator?.let {
            it.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 0, 90, 14, 70), intArrayOf(0, 0, 150, 0, 110), -1),
            )
        }
    }

    fun stop() = pulse(40, 180)

    private fun pulse(
        durationMs: Long,
        amplitude: Int,
    ) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        device.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
    }
}
