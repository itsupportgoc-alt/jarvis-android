package ai.jarvis.service

import ai.jarvis.JarvisApp
import ai.jarvis.MainActivity
import ai.jarvis.R
import ai.jarvis.audio.WakeListener
import ai.jarvis.core.Phase
import ai.jarvis.permissions.Permissions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and therefore the Rust engine) alive while JARVIS holds the
 * microphone. `microphone` service type is mandatory on API 30+; the
 * corresponding FOREGROUND_SERVICE_MICROPHONE permission is declared.
 *
 * When the wake word is enabled this service also runs the always-on listener,
 * so saying "Jarvis" works with the activity closed. The listener restarts the
 * platform recogniser after each short session and hands a matched command
 * straight to the controller, which is application-scoped; the activity is then
 * brought forward on a best-effort basis (Android may refuse the launch from the
 * background, which is why the action does not depend on it).
 */
class JarvisService : Service() {
    private var wake: WakeListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var phaseWatcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        controller().onServiceRunning(true)
        syncWakeListener()
        watchPhase()
        return START_STICKY
    }

    override fun onDestroy() {
        phaseWatcher?.cancel()
        scope.cancel()
        wake?.stop()
        wake = null
        controller().onServiceRunning(false)
        super.onDestroy()
    }

    /**
     * The wake recogniser and an active session cannot both hold the microphone,
     * so the wake word stands down while JARVIS is listening, thinking or
     * speaking, and comes back once it is idle again.
     */
    private fun watchPhase() {
        if (phaseWatcher != null) return
        phaseWatcher =
            scope.launch {
                controller().ui.collect { state ->
                    if (state.phase == Phase.IDLE) syncWakeListener() else pauseWake()
                }
            }
    }

    private fun pauseWake() {
        wake?.stop()
        wake = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun controller() = (application as JarvisApp).controller

    private fun syncWakeListener() {
        val enabled = controller().settings.value.wakeWordEnabled
        if (!enabled) {
            wake?.stop()
            wake = null
            return
        }
        if (wake != null) return
        if (!Permissions.granted(this, Permissions.MIC)) return
        val listener =
            WakeListener(
                this,
                onWake = ::onWake,
                onUnavailable = { message -> Log.w(TAG, message) },
            )
        wake = listener
        if (!listener.start()) wake = null
    }

    /** Runs the instruction the user said after the wake phrase. */
    private fun onWake(command: String) {
        // Release the wake recogniser before a session needs the microphone;
        // `watchPhase` brings it back when JARVIS is idle.
        pauseWake()
        val controller = controller()
        // Surfacing the window is best effort: a background start can be refused,
        // and the command still runs either way.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { Log.w(TAG, "could not bring the activity forward", it) }
        if (command.isBlank()) {
            controller.startListening()
        } else {
            controller.submitText(command)
        }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_listening),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_listening_text)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val wakeOn = controller().settings.value.wakeWordEnabled
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(
                getString(
                    if (wakeOn) R.string.notification_wake_title else R.string.notification_listening_title,
                ),
            ).setContentText(
                getString(
                    if (wakeOn) R.string.notification_wake_text else R.string.notification_listening_text,
                ),
            ).setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "ai.jarvis.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JarvisService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }
}
