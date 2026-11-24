# Building the llama.cpp backend for Android

The `local-llm` feature compiles a `QwenLocal` backend against
[`llama-cpp-2`](https://crates.io/crates/llama-cpp-2) 0.1.161. It is opt-in:
`-PlocalLlm` turns on both the Rust feature and the Settings model-import UI.
The default APK stays unchanged. This page records the toolchain, the three-ABI
proof, and what is still unmeasured.

## Toolchain installed on this machine

| Tool | Version | Location |
| --- | --- | --- |
| CMake | 3.31.6 | `android/.sdk/cmake/3.31.6/bin/cmake.exe` |
| Ninja | 1.12.1 | `android/.sdk/cmake/3.31.6/bin/ninja.exe` |
| libclang | 18.1.1 | `android/.tools/libclang/clang/native/libclang.dll` |
| NDK | 26.1.10909125 | `android/.sdk/ndk/26.1.10909125` |
| cargo-ndk | 4.1.2 | on `PATH` |

CMake and Ninja came from the Android SDK package, not a separate installer:

```bash
cd android
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
yes | ./.sdk/cmdline-tools/latest/bin/sdkmanager.bat \
  --sdk_root="$(pwd)/.sdk" "cmake;3.31.6"
```

`libclang` is needed by `llama-cpp-sys`'s bindgen step. It is installed into the
gitignored tool directory:

```bash
python -m pip install --target android/.tools/libclang libclang
```

On this Windows GNU Rust host, `dlltool` is also required by bindgen's
`libloading` build dependency; MSYS2 provides it at
`C:/msys64/mingw64/bin/dlltool.exe`.

## Exact build

From the repository root, in Git Bash:

```bash
export NDK="$(pwd)/android/.sdk/ndk/26.1.10909125"
export ANDROID_NDK="$NDK"
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_NDK_HOME="$NDK"

export PATH="$(pwd)/android/.sdk/cmake/3.31.6/bin:/c/msys64/mingw64/bin:$PATH"
export LIBCLANG_PATH="$(pwd)/android/.tools/libclang/clang/native"
export CMAKE_GENERATOR=Ninja
export CMAKE_MAKE_PROGRAM="$(pwd)/android/.sdk/cmake/3.31.6/bin/ninja.exe"

cd core
cargo ndk -P 29 \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o target/llama-probe \
  build --release --features local-llm --lib
```

Why these details matter:

- **`-P 29`**: cargo-ndk defaults to API 21, but NDK r26 only declares
  `posix_madvise()` from API 23. llama.cpp's `llama-mmap.cpp` uses it, so an
  API-21 build fails with `use of undeclared identifier 'posix_madvise'`.
  The app's `minSdk` is already 29.
- **`ANDROID_NDK` / `ANDROID_NDK_ROOT`**: `llama-cpp-sys`'s build script does
  not check `ANDROID_NDK_HOME` alone. Set all three.
- **`CMAKE_GENERATOR=Ninja`**: without it, the CMake crate falls back to NMake
  on Windows and fails with `'nmake' '-?'`.
- **`LIBCLANG_PATH`**: bindgen needs the directory containing `libclang.dll`,
  not the DLL path.
- **`dlltool`**: only needed on a Windows GNU host; an MSVC host toolchain does
  not need it.

## Result

Produced by the command above:

| ABI | ELF | Size | llama.cpp present |
| --- | --- | --- | --- |
| arm64-v8a | ELF64 AArch64 | 11,046,584 bytes | yes |
| armeabi-v7a | ELF32 ARM | 7,848,708 bytes | yes |
| x86_64 | ELF64 x86-64 | 11,209,872 bytes | yes |

Each file is at `core/target/llama-probe/<abi>/libjarvis_core.so` (or the Gradle
task's `app/build/local-llm-jniLibs/<abi>/`), is Android API 29, and has 34
`tokenizer.ggml.*` strings from llama.cpp's GGUF loader. The grep output is from
the unstripped probe; AGP strips these copies before packaging.
`llvm-readelf -d` reports a dependency on `libc++_shared.so` because
`llama-cpp-2` defaults to `android-shared-stdcxx`.

The same proof is available as a Gradle task:

```bash
cd android
./gradlew --no-daemon :app:cargoNdkBuildLocalLlm
# output: app/build/local-llm-jniLibs/<abi>/{libjarvis_core.so,libc++_shared.so}
```

For an installable opt-in APK:

```bash
./gradlew --no-daemon :app:assembleDebug -PlocalLlm
```

That build packages the NDK's `libc++_shared.so` for each ABI; the default APK
explicitly excludes it. The debug local APK is about 56 MB because it carries
three llama.cpp copies plus the C++ runtime.

Build time was about 1 minute 31 seconds for armv7 + x86_64 after the arm64
native build was warm. A cold three-ABI build is several minutes.

## What is implemented

`core/src/llm/local.rs` and the engine wiring provide:

- `LocalLlmConfig` validation (model path, context size, threads, sampling);
- GGUF load/init with mmap-capable llama.cpp;
- chat-template application, with a ChatML fallback;
- a separate llama context per turn;
- token streaming on a blocking worker, cancellation checked per token;
- typed `JarvisError::Model` / `InvalidArgument` / `Cancelled` failures;
- `EngineConfig` selection through `backend: "local"`, `modelPath`,
  `contextTokens`, `threads`, `localMaxTokens` and `maxRamMb`;
- a RAM guard that refuses a model before loading if `model + 512 MB` exceeds
  either `maxRamMb` (default 2,048) or Android's `MemAvailable`;
- Settings import of a `.gguf` through the document picker, copied into
  app-private `files/models/`, with the same ceiling checked before selection;
- model-load time emitted as the `local_model_load` latency stage; first-token
  latency already appears in the existing debug `qwen_first_token` stage.

No GGUF was loaded and no inference was run here: there is no Android device
attached and no model in the repository. The default 95 Rust tests cover backend
selection (including the missing-model-path refusal); the RAM-guard tests sit
behind `local-llm` and were compiled into the Android library but not executed
because there is no device. None of this proves real inference.

## Device benchmark procedure (not run)

No device is attached here, so there are no measured numbers. To run it:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -s JarvisController | grep -E 'local_model_load|qwen_first_token'
```

Import a small Q4_K_M GGUF, switch **ON-DEVICE MODEL** on, and send one turn.
`local_model_load` is the GGUF load time; `qwen_first_token` is measured from the
start of the turn to the first streamed token. Also record peak RSS and the
phone model. Do not claim offline reasoning works until that device test exists.
