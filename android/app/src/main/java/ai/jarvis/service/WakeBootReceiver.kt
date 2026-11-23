package ai.jarvis.service

import ai.jarvis.data.SettingsStore
import ai.jarvis.permissions.Permissions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores the wake-word listener after a reboot, but only when the user turned
 * the wake word on and the microphone is still granted. Android 14+ refuses a
 * microphone-typed foreground service started from boot, so the start is
 * guarded: the user can always re-enable it by opening JARVIS.
 */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore(context).current.wakeWordEnabled) return
        if (!Permissions.granted(context, Permissions.MIC)) return
        runCatching { JarvisService.start(context) }
            .onFailure { Log.w(TAG, "wake word not restored after boot", it) }
    }

    private companion object {
        const val TAG = "JarvisWakeBoot"
    }
}
