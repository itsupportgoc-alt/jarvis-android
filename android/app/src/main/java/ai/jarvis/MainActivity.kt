package ai.jarvis

import ai.jarvis.core.JarvisController
import ai.jarvis.permissions.Permissions
import ai.jarvis.service.JarvisService
import ai.jarvis.ui.JarvisScreen
import ai.jarvis.ui.theme.JarvisTheme
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller: JarvisController = (application as JarvisApp).controller
        setContent {
            JarvisTheme {
                JarvisScreen(controller)
            }
        }
        injectDebugTranscript(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        injectDebugTranscript(intent)
    }

    /**
     * Re-arms the wake listener when the app is opened.
     *
     * The switch in Settings starts the foreground service once, but that start
     * is lost as soon as the process is killed (routine on MIUI) or the user
     * force-stops the app: the stored setting still says on while nothing is
     * listening, which looks exactly like a wake word that never fires. Opening
     * the app is the one moment the microphone service may be started, so it is
     * started here whenever the setting is on.
     */
    override fun onStart() {
        super.onStart()
        val controller = (application as JarvisApp).controller
        if (!controller.settings.value.wakeWordEnabled) return
        if (!Permissions.granted(this, Permissions.MIC)) return
        runCatching { JarvisService.start(this) }
            .onFailure { Log.w(TAG, "wake service could not be restarted", it) }
    }

    /**
     * Debug-only: `adb shell am start -n ai.jarvis/.MainActivity --es wakeTranscript
     * "Jarvis open settings"` replays a transcript through the wake matcher and
     * the service's normal hand-off, instead of requiring speech into the mic.
     */
    private fun injectDebugTranscript(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val text = intent?.getStringExtra("wakeTranscript")?.trim().orEmpty()
        if (text.isEmpty()) return
        runCatching { JarvisService.startDebugTranscript(this, text) }
            .onFailure { Log.w(TAG, "debug transcript injection failed", it) }
    }

    private companion object {
        const val TAG = "JarvisActivity"
    }
}
