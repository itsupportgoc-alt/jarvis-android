package ai.jarvis.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Read-only notification access for the assistant.
 *
 * The user must grant Notification access in system settings. Nothing is
 * forwarded off-device: the tool returns a truncated snapshot to Qwen only when
 * the user asks. Password-bearing notifications are never exposed by the
 * platform to a listener.
 */
class JarvisNotificationListener : NotificationListenerService() {
    data class Entry(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
    )

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: JarvisNotificationListener? = null

        fun snapshot(limit: Int): List<Entry> {
            val service = instance ?: return emptyList()
            return runCatching {
                service.activeNotifications
                    .asSequence()
                    .sortedByDescending { it.postTime }
                    .take(limit)
                    .map { it.toEntry() }
                    .toList()
            }.getOrElse {
                Log.w("JarvisNotify", "snapshot failed", it)
                emptyList()
            }
        }

        fun dismiss(key: String): Boolean {
            val service = instance ?: return false
            return runCatching {
                service.cancelNotification(key)
                true
            }.getOrDefault(false)
        }

        private fun StatusBarNotification.toEntry(): Entry {
            val extras = notification.extras
            return Entry(
                key = key,
                app = packageName,
                title = extras.getCharSequence("android.title")?.toString().orEmpty(),
                text = extras.getCharSequence("android.text")?.toString().orEmpty(),
                time = postTime,
            )
        }
    }
}
