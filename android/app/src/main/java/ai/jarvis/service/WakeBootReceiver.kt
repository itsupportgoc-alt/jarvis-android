package ai.jarvis.service

import ai.jarvis.data.SettingsStore
import ai.jarvis.permissions.Permissions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the wake-word listener back after a reboot, an app update, or the user
 * swiping the app away.
 *
 * The listener has to outlive the activity: the whole point is that saying the
 * name works with the app closed. Every path is guarded and logged, because the
 * start can be refused: Android 12+ restricts microphone foreground services
 * started from the background, and MIUI additionally requires its own Autostart
 * permission. When a start is refused the alarm is retried a few times and then
 * given up, instead of spinning; opening the app always re-arms it.
 */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_RESTART
        ) {
            return
        }
        if (!SettingsStore(context).current.wakeWordEnabled) {
            Log.i(TAG, "$action ignored: the wake word is off")
            return
        }
        if (!Permissions.granted(context, Permissions.MIC)) {
            Log.i(TAG, "$action ignored: no microphone permission")
            return
        }
        runCatching { JarvisService.start(context) }
            .onSuccess {
                Log.i(TAG, "wake listener restored after $action")
                reset(context)
            }.onFailure {
                Log.w(TAG, "wake listener not restored after $action", it)
                retryAfterFailure(context)
            }
    }

    companion object {
        private const val TAG = "JarvisWakeBoot"

        /** Sent by [JarvisService.onTaskRemoved] when the app is swiped away. */
        const val ACTION_RESTART = "ai.jarvis.RESTART_WAKE"

        /** Give up after this many consecutive refused starts. */
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 30_000L
        private const val ATTEMPTS_KEY = "wake_restart_attempts"

        internal fun retryAfterFailure(context: Context) {
            val prefs = context.getSharedPreferences("jarvis_wake_restart", Context.MODE_PRIVATE)
            val attempts = prefs.getInt(ATTEMPTS_KEY, 0)
            if (attempts >= MAX_ATTEMPTS) {
                Log.w(TAG, "giving up after $attempts refused starts; open the app to re-arm")
                return
            }
            prefs.edit().putInt(ATTEMPTS_KEY, attempts + 1).apply()
            WakeRestart.schedule(context, RETRY_DELAY_MS)
        }

        internal fun reset(context: Context) {
            context
                .getSharedPreferences("jarvis_wake_restart", Context.MODE_PRIVATE)
                .edit()
                .putInt(ATTEMPTS_KEY, 0)
                .apply()
        }
    }
}

/**
 * One place that schedules the re-arm alarm.
 *
 * `setAndAllowWhileIdle` needs no exact-alarm permission and still fires while
 * the phone is dozing, which is what matters after a task swipe on MIUI.
 */
internal object WakeRestart {
    private const val TAG = "JarvisWakeBoot"
    private const val REQUEST_CODE = 7

    fun schedule(
        context: Context,
        delayMs: Long,
    ) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WakeBootReceiver::class.java)
                    .setAction(WakeBootReceiver.ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        runCatching {
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pending,
            )
        }.onFailure { Log.w(TAG, "the restart alarm was refused", it) }
    }
}
