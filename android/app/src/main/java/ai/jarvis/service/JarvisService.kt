package ai.jarvis.service

import ai.jarvis.BuildConfig
import ai.jarvis.JarvisApp
import ai.jarvis.MainActivity
import ai.jarvis.R
import ai.jarvis.audio.WakeListener
import ai.jarvis.audio.dispatchWakeTranscript
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private var wakeSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // A live service means any earlier refused restarts are settled.
        WakeBootReceiver.reset(this)
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
        // The notification's own toggle: no trip through Settings needed.
        if (intent?.action == ACTION_SET_WAKE) {
            val enable = intent.getBooleanExtra(EXTRA_WAKE, false)
            Log.i(TAG, "notification asked for wake=$enable")
            // Disabling stops this service from the controller, which removes
            // the notification and releases the microphone.
            controller().setWakeWordEnabled(enable)
            if (enable) {
                // Rebuild now that the text and action label changed.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                syncWakeListener()
            }
            return START_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        controller().onServiceRunning(true)
        // Only claim the microphone when no session already holds it; a restart
        // of the service during a reply must not fight the recogniser for it.
        if (controller().ui.value.phase == Phase.IDLE) syncWakeListener()
        watchPhase()
        // Debug-only: replay a fixed transcript through the same matcher the
        // recogniser feeds, so the wake path can be verified on a device
        // without speaking into the microphone.
        if (BuildConfig.DEBUG && intent?.action == ACTION_DEBUG_TRANSCRIPT) {
            val text = intent.getStringExtra(EXTRA_TRANSCRIPT).orEmpty()
            Log.i(TAG, "debug transcript injected: \"$text\"")
            if (ai.jarvis.audio.WakeWord.isStop(text)) onSpokenStop(text) else dispatchWakeTranscript(text, ::onWake)
        }
        return START_STICKY
    }

    /**
     * Swiping the app away must not take the wake word with it. The service is
     * started again a moment later through an alarm, which the system allows
     * from the background where a direct start may be refused.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; scheduling the wake listener to come back")
        WakeRestart.schedule(applicationContext, RESTART_DELAY_MS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroying; wake listener was ${if (wake != null) "active" else "null"}")
        phaseWatcher?.cancel()
        wakeSyncJob?.cancel()
        scope.cancel()
        wake?.stop()
        wake = null
        controller().onServiceRunning(false)
        super.onDestroy()
    }

    /**
     * The wake recogniser and an active session cannot both hold the microphone,
     * so the listener stands down only while JARVIS is listening. During
     * thinking and speaking the microphone is free again, and the listener keeps
     * running so a spoken "stop" can interrupt the reply.
     */
    private fun watchPhase() {
        if (phaseWatcher != null) return
        phaseWatcher =
            scope.launch {
                controller().ui
                    .map { it.phase }
                    .distinctUntilChanged()
                    .collect { phase ->
                        if (phase == Phase.LISTENING) pauseWake() else scheduleWakeSync()
                    }
            }
    }

    /**
     * Re-arms the wake listener once the session has actually settled on IDLE.
     *
     * Acting on every emission destroyed and recreated the recogniser three
     * times inside 200 ms after a wake (IDLE, THINKING, IDLE again before the
     * engine reported LISTENING), which produced ERROR_CLIENT and spent the
     * microphone reconnecting instead of listening.
     */
    private fun scheduleWakeSync() {
        wakeSyncJob?.cancel()
        wakeSyncJob =
            scope.launch {
                delay(WAKE_REARM_DELAY_MS)
                if (controller().ui.value.phase == Phase.LISTENING) return@launch
                syncWakeListener()
            }
    }

    private fun pauseWake() {
        wakeSyncJob?.cancel()
        wakeSyncJob = null
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
                onStop = ::onSpokenStop,
                onUnavailable = { message -> Log.w(TAG, message) },
            )
        wake = listener
        if (!listener.start()) wake = null
    }

    /**
     * "Stop" said while SEVEN is thinking or speaking drops the turn: the reply
     * stops mid-sentence and the microphone comes back armed.
     */
    private fun onSpokenStop(spoken: String) {
        Log.i(TAG, "spoken stop \"$spoken\"; cancelling the turn")
        controller().cancel()
    }

    /** Runs the instruction the user said after the wake phrase. */
    private fun onWake(command: String) {
        val phase = controller().ui.value.phase
        // The listener keeps the microphone while SEVEN is thinking or speaking
        // so it can hear "stop". That also means it can hear SEVEN itself, so a
        // wake match during a reply is the assistant's own voice and is dropped.
        if (phase == Phase.SPEAKING || phase == Phase.THINKING) {
            Log.i(TAG, "wake matched during $phase; ignoring, the reply would wake itself")
            return
        }
        Log.i(TAG, "wake matched, command=\"$command\"")
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
            Log.i(TAG, "no command after the name; opening a listening session")
            controller.startListening()
        } else {
            Log.i(TAG, "handing \"$command\" to the controller")
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
        val toggleWake =
            PendingIntent.getService(
                this,
                2,
                Intent(this, JarvisService::class.java)
                    .setAction(ACTION_SET_WAKE)
                    .putExtra(EXTRA_WAKE, !wakeOn),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
            .addAction(
                0,
                getString(
                    if (wakeOn) {
                        R.string.notification_wake_off_action
                    } else {
                        R.string.notification_wake_on_action
                    },
                ),
                toggleWake,
            ).addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val WAKE_REARM_DELAY_MS = 600L
        private const val RESTART_DELAY_MS = 1_500L
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "ai.jarvis.STOP"
        private const val ACTION_SET_WAKE = "ai.jarvis.SET_WAKE"
        private const val EXTRA_WAKE = "wake_enabled"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JarvisService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }

        /** Debug-only transcript injection, used by [MainActivity] diagnostics. */
        const val ACTION_DEBUG_TRANSCRIPT = "ai.jarvis.DEBUG_WAKE_TRANSCRIPT"
        const val EXTRA_TRANSCRIPT = "transcript"

        fun startDebugTranscript(
            context: Context,
            transcript: String,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JarvisService::class.java)
                    .setAction(ACTION_DEBUG_TRANSCRIPT)
                    .putExtra(EXTRA_TRANSCRIPT, transcript),
            )
        }
    }
}
