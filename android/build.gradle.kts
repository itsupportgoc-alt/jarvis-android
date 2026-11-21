// JARVIS root build. Versions are pinned deliberately: AGP 8.7.3 is the last
// 8.7 line compatible with Gradle 8.11, and Kotlin 2.0.21 ships the Compose
// compiler plugin used below.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
