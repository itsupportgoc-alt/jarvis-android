package ai.jarvis.service

import ai.jarvis.data.SettingsStore
import ai.jarvis.permissions.Permissions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the wake-word listener back after a reboot or after the user swiped the
 * app away.
 *
 * The listener has to outlive the activity: the whole point is that saying the
 * name works with the app closed. Android still refuses a microphone-typed
 * foreground service started from the background in some versions, so every
 * path here is guarded and logs the refusal instead of crashing; opening the app
 * always re-arms it.
 */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_RESTART) return
        if (!SettingsStore(context).current.wakeWordEnabled) return
        if (!Permissions.granted(context, Permissions.MIC)) return
        runCatching { JarvisService.start(context) }
            .onFailure { Log.w(TAG, "wake listener not restored after $action", it) }
    }

    companion object {
        private const val TAG = "JarvisWakeBoot"

        /** Sent by [JarvisService.onTaskRemoved] when the app is swiped away. */
        const val ACTION_RESTART = "ai.jarvis.RESTART_WAKE"
    }
}
