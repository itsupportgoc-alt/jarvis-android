package ai.jarvis.service

import ai.jarvis.JarvisApp
import ai.jarvis.MainActivity
import ai.jarvis.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process (and therefore the Rust engine) alive while JARVIS holds the
 * microphone. `microphone` service type is mandatory on API 30+; the
 * corresponding FOREGROUND_SERVICE_MICROPHONE permission is declared.
 *
 * The wake word runs inside this service when an on-device detector is compiled
 * in. In this build wake detection is a documented gap, so the service is
 * started when the user opens the mic or leaves JARVIS in standby.
 */
class JarvisService : Service() {
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
        return START_STICKY
    }

    override fun onDestroy() {
        controller().onServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun controller() = (application as JarvisApp).controller

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
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(getString(R.string.notification_listening_title))
            .setContentText(getString(R.string.notification_listening_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
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
