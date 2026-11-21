package ai.jarvis

import ai.jarvis.core.JarvisController
import ai.jarvis.ui.JarvisScreen
import ai.jarvis.ui.theme.JarvisTheme
import android.os.Bundle
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
    }
}
