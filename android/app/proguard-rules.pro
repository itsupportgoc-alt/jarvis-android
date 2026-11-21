# JNI: the Rust exports are looked up by these exact names, and Kotlin calls
# the external methods reflectively through the runtime.
-keep class ai.jarvis.core.JarvisNative { *; }
-keep class ai.jarvis.core.JarvisNative$HostCallback { *; }

# Callback objects passed into native code must not be renamed.
-keepclasseswithmembers class * implements ai.jarvis.core.JarvisNative$HostCallback { *; }

# Compose keeps its own rules; nothing else is reflected here.
-dontwarn org.slf4j.**
