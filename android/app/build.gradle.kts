import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional llama.cpp build. Off by default so the normal APK stays small;
// `-PlocalLlm` (empty) or `-PlocalLlm=true` enables it.
val localLlm = providers.gradleProperty("localLlm").map { it.lowercase() != "false" }.getOrElse(false)

android {
    namespace = "ai.jarvis"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "ai.jarvis"
        minSdk = 29
        targetSdk = 35
        versionCode = 15
        versionName = "0.4.2"

        ndk {
            // 64-bit is the primary target; armv7 for older devices; x86_64 for
            // emulators. cargo-ndk builds exactly these three.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField("String", "JARVIS_CORE_VERSION", "\"0.1.0\"")
        buildConfigField("boolean", "LOCAL_LLM", localLlm.toString())
        // Optional build-time Freebuff key so a personal build starts already
        // connected. Read from -PfreebuffApiKey, FREEBUFF_API_KEY, or
        // android/local.properties (gitignored). Never commit a real key: it
        // ends up inside the APK and anyone who unpacks it can spend it.
        val freebuffKey = freebuffApiKey()
        buildConfigField("String", "FREEBUFF_API_KEY", "\"${freebuffKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        // A stale copy from a local-llm build must not leak into a normal APK.
        if (!localLlm) jniLibs.excludes += "**/libc++_shared.so"
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

/**
 * Resolves an optional Freebuff API key at build time.
 *
 * Precedence: `-PfreebuffApiKey=...`, the `FREEBUFF_API_KEY` environment
 * variable, then `freebuff.api.key` in `android/local.properties`. The
 * repository ships none, and `local.properties` is gitignored.
 */
fun freebuffApiKey(): String {
    (project.findProperty("freebuffApiKey") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv("FREEBUFF_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val local = rootProject.projectDir.resolve("local.properties")
    if (!local.exists()) return ""
    return runCatching {
        Properties()
            .apply { local.inputStream().use { stream -> load(stream) } }
            .getProperty("freebuff.api.key")
            ?.trim()
            .orEmpty()
    }.getOrDefault("")
}

/**
 * Builds the Rust core for every shipped ABI and drops the .so files where AGP
 * expects them.
 *
 * Reproducible invocation (what this task runs):
 *   cargo ndk -P 29 -t arm64-v8a -t armeabi-v7a -t x86_64 \
 *       -o android/app/src/main/jniLibs build --release
 *
 * API 29 is explicit because NDK r26 only declares posix_madvise() from API 23;
 * the default API 21 would break the optional llama.cpp backend. The app's
 * minSdk is already 29.
 *
 * Prerequisites (see setup.sh): rustup targets, cargo-ndk 4.x, NDK r26+ and
 * ANDROID_NDK_HOME. Pass -PskipRust to assemble the Kotlin side alone.
 * The optional local-llm proof build is documented in docs/OFFLINE_BUILD.md.
 */
val coreDir = rootProject.projectDir.parentFile.resolve("core")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")
// `-PskipRust` (empty value) or `-PskipRust=true` both skip; only an explicit
// "false" keeps the Rust build.
val skipRust = providers.gradleProperty("skipRust").map { it.lowercase() != "false" }.getOrElse(false)

val ndkHome = System.getenv("ANDROID_NDK_HOME")
    ?: System.getenv("ANDROID_NDK_ROOT")
    ?: System.getenv("NDK_HOME")
    ?: ""
// CMake and Ninja live in the Android SDK's cmake package. Prefer an explicit
// env override, then the newest installed SDK package.
val sdkCmakeBin = rootProject.projectDir.resolve(".sdk/cmake")
    .listFiles()
    ?.map { it.resolve("bin") }
    ?.filter { it.resolve("cmake.exe").exists() || it.resolve("cmake").exists() }
    ?.maxByOrNull { it.parentFile.name }
val cmakeBin = System.getenv("CMAKE_BIN") ?: sdkCmakeBin?.absolutePath ?: ""
val libclangPath = System.getenv("LIBCLANG_PATH")
    ?: rootProject.projectDir.resolve(".tools/libclang/clang/native").absolutePath

fun localLlmEnv(task: Exec) {
    task.environment("ANDROID_NDK", ndkHome)
    task.environment("ANDROID_NDK_ROOT", ndkHome)
    task.environment("ANDROID_NDK_HOME", ndkHome)
    task.environment("LIBCLANG_PATH", libclangPath)
    if (cmakeBin.isNotEmpty()) {
        task.environment("PATH", listOf(cmakeBin, System.getenv("PATH") ?: "").joinToString(File.pathSeparator))
        task.environment("CMAKE_GENERATOR", "Ninja")
        val ninja = File(cmakeBin, if (File(cmakeBin, "ninja.exe").exists()) "ninja.exe" else "ninja")
        task.environment("CMAKE_MAKE_PROGRAM", ninja.absolutePath)
    }
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile jarvis-core for arm64-v8a, armeabi-v7a and x86_64"
    workingDir = coreDir
    localLlmEnv(this)
    val args = mutableListOf(
        "cargo", "ndk",
        "-P", "29",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", jniLibsDir.absolutePath,
        "build", "--release",
    )
    if (localLlm) {
        args += listOf("--features", "local-llm")
    }
    commandLine(args)
    onlyIf { !skipRust }
    doLast {
        if (localLlm) {
            copyCxxShared(ndkHome, jniLibsDir)
        } else {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64").forEach { abi ->
                File(jniLibsDir, "$abi/libc++_shared.so").delete()
            }
        }
    }
}

// Reproduces the documented three-ABI llama.cpp proof without packaging it into
// the normal APK. Output: app/build/local-llm-jniLibs/<abi>/.
val cargoNdkBuildLocalLlm by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile jarvis-core with local-llm for all three ABIs (proof)"
    workingDir = coreDir
    localLlmEnv(this)
    doFirst {
        require(ndkHome.isNotEmpty()) { "ANDROID_NDK_HOME is not set" }
        require(File(cmakeBin, "cmake.exe").exists() || File(cmakeBin, "cmake").exists()) {
            "CMake not found: install sdkmanager cmake;3.31.6 or set CMAKE_BIN"
        }
        require(File(libclangPath).isDirectory) { "libclang not found at $libclangPath" }
    }
    val outDir = layout.buildDirectory.dir("local-llm-jniLibs").get().asFile
    commandLine(
        "cargo", "ndk",
        "-P", "29",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", outDir.absolutePath,
        "build", "--release", "--features", "local-llm", "--lib",
    )
    doLast { copyCxxShared(ndkHome, outDir) }
}

fun copyCxxShared(ndk: String, destination: File) {
    if (ndk.isEmpty()) return
    val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull() ?: return
    val libRoot = prebuilt.resolve("sysroot/usr/lib")
    listOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "arm-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
    ).forEach { (abi, triple) ->
        val source = libRoot.resolve("$triple/libc++_shared.so")
        if (source.exists()) {
            copy {
                from(source)
                into(File(destination, abi))
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

tasks.register<Exec>("cargoTest") {
    group = "verification"
    description = "Run the Rust core test suite on the host"
    workingDir = coreDir
    commandLine("cargo", "test")
}
