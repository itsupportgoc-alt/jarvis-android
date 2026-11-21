package ai.jarvis

import ai.jarvis.core.JarvisController
import android.app.Application

class JarvisApp : Application() {
    lateinit var controller: JarvisController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = JarvisController(this)
    }

    override fun onTerminate() {
        controller.shutdown()
        super.onTerminate()
    }
}
