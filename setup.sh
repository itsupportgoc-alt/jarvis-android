#!/usr/bin/env bash
# JARVIS one-shot developer setup.
#
# Installs the Rust Android targets and cargo-ndk, verifies Java/NDK/SDK, and
# writes android/local.properties. Safe to re-run. It does not install the
# Android SDK or NDK themselves; both are large and licensed, so we check for
# them and print the exact missing piece.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() { printf '\n[setup] %s\n' "$*" >&2; exit 1; }
note() { printf '[setup] %s\n' "$*"; }

command -v rustup >/dev/null || fail "rustup not found: install Rust 1.80+ from https://rustup.rs"
command -v cargo >/dev/null || fail "cargo not found"
command -v java >/dev/null || fail "Java 17+ not found"

JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ -n "${JAVA_MAJOR:-}" ] && [ "$JAVA_MAJOR" -ge 17 ] || fail "Java 17+ required (found ${JAVA_MAJOR:-unknown})"

note "installing Rust targets for aarch64, armv7 and x86_64"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
rustup component add rustfmt clippy || true

if ! cargo ndk --version >/dev/null 2>&1; then
    note "installing cargo-ndk 4.1.2"
    cargo install cargo-ndk --locked --version 4.1.2
fi
note "$(cargo ndk --version)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    for candidate in "$ROOT/android/.sdk" "$HOME/Android/Sdk" "$LOCALAPPDATA/Android/Sdk" "/usr/local/lib/android/sdk"; do
        [ -d "$candidate" ] && SDK="$candidate" && break
    done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || fail "Android SDK not found. Set ANDROID_HOME (platforms;android-35, build-tools;35.0.0)."
[ -d "$SDK/platforms/android-35" ] || fail "SDK platform 35 missing: sdkmanager 'platforms;android-35'"
[ -d "$SDK/build-tools/35.0.0" ] || fail "build-tools 35.0.0 missing: sdkmanager 'build-tools;35.0.0'"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [ -z "$NDK" ]; then
    [ -d "$SDK/ndk" ] && NDK="$(find "$SDK/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    fail "NDK r26+ not found. Install it (sdkmanager 'ndk;26.1.10909125') and set ANDROID_NDK_HOME."
fi
note "NDK: $NDK"

# Optional llama.cpp cross-build (local-llm). The default app does not need
# CMake, but Phase 3's backend does. Check both a PATH install and the SDK's
# cmake package without failing the normal setup path.
CMAKE_FOUND=0
if command -v cmake >/dev/null 2>&1 && command -v ninja >/dev/null 2>&1; then
    note "CMake: $(command -v cmake); Ninja: $(command -v ninja)"
    CMAKE_FOUND=1
else
    for candidate in "$SDK"/cmake/*/bin; do
        if [ -x "$candidate/cmake" ] || [ -x "$candidate/cmake.exe" ]; then
            note "CMake/Ninja (SDK): $candidate"
            CMAKE_FOUND=1
        fi
    done
fi
if [ "$CMAKE_FOUND" -eq 0 ]; then
    note "CMake/Ninja not found: local-llm needs sdkmanager 'cmake;3.31.6' (see docs/OFFLINE_BUILD.md)"
fi
LIBCLANG_CANDIDATE="${LIBCLANG_PATH:-$ROOT/android/.tools/libclang/clang/native}"
if [ -d "$LIBCLANG_CANDIDATE" ]; then
    note "libclang: $LIBCLANG_CANDIDATE"
else
    note "libclang not found: local-llm needs 'python -m pip install --target android/.tools/libclang libclang'"
fi

# local.properties wants forward slashes on every host.
SDK_FORWARD="$(printf '%s' "$SDK" | sed 's#\\#/#g')"
printf 'sdk.dir=%s\n' "$SDK_FORWARD" > "$ROOT/android/local.properties"
note "wrote android/local.properties"

if [ ! -f "$ROOT/android/gradle/wrapper/gradle-wrapper.jar" ]; then
    note "gradle wrapper jar missing; the wrapper cannot run. Install Gradle 8.11.1 and run 'gradle wrapper'."
fi

note "verifying the Rust core"
( cd "$ROOT/core" && cargo test --quiet && cargo clippy --all-targets -- -D warnings )

cat <<'EOF'

[setup] Done.

Next:
  1. Download optional on-device models:   ./models/download_models.sh
  2. Build the app:                         ./android/gradlew -p android assembleDebug
     (Kotlin only, no NDK:                 ./android/gradlew -p android assembleDebug -PskipRust)
  3. Install on a device:                   android/.sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
  4. Launch, open SETTINGS, paste a Qwen API key.
EOF
