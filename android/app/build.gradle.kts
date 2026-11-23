import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.jarvis"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "ai.jarvis"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"

        ndk {
            // 64-bit is the primary target; armv7 for older devices; x86_64 for
            // emulators. cargo-ndk builds exactly these three.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField("String", "JARVIS_CORE_VERSION", "\"0.1.0\"")
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
 * Builds the Rust core for every shipped ABI and drops the .so files where AGP
 * expects them.
 *
 * Reproducible invocation (what this task runs):
 *   cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
 *       -o android/app/src/main/jniLibs build --release
 *
 * Prerequisites (see setup.sh): rustup targets, cargo-ndk 4.x, NDK r26+ and
 * ANDROID_NDK_HOME. Pass -PskipRust to assemble the Kotlin side alone.
 */
val coreDir = rootProject.projectDir.parentFile.resolve("core")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")
// `-PskipRust` (empty value) or `-PskipRust=true` both skip; only an explicit
// "false" keeps the Rust build.
val skipRust = providers.gradleProperty("skipRust").map { it.lowercase() != "false" }.getOrElse(false)

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile jarvis-core for arm64-v8a, armeabi-v7a and x86_64"
    workingDir = coreDir
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: System.getenv("NDK_HOME")
    environment("ANDROID_NDK_HOME", ndkHome ?: "")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )
    onlyIf { !skipRust }
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
