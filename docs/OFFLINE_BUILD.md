# Building the llama.cpp backend for Android

The `local-llm` feature compiles a `QwenLocal` backend against
[`llama-cpp-2`](https://crates.io/crates/llama-cpp-2) 0.1.161. It is **not** part
of the default app build and is **not wired into `EngineConfig` yet**: this page
proves the native build works for all three shipped ABIs. Runtime loading,
RAM guards, model import and the Kotlin model picker are the rest of Phase 3.

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
| arm64-v8a | ELF64 AArch64 | 6,317,776 bytes | yes |
| armeabi-v7a | ELF32 ARM | 4,182,624 bytes | yes |
| x86_64 | ELF64 x86-64 | 6,171,616 bytes | yes |

Each file is at `core/target/llama-probe/<abi>/libjarvis_core.so`, is Android
API 29, and has 34 `tokenizer.ggml.*` strings from llama.cpp's GGUF loader.
`llvm-readelf -d` reports a dependency on `libc++_shared.so` because
`llama-cpp-2` defaults to `android-shared-stdcxx`. Any APK that enables
`local-llm` must package the NDK's `libc++_shared.so` for each ABI (or switch
the crate to `static-stdcxx`). The default APK does not, because it does not
enable the feature.

Build time was about 1 minute 31 seconds for armv7 + x86_64 after the arm64
native build was warm. A cold three-ABI build is several minutes.

## What is implemented

`core/src/llm/local.rs` compiles and provides:

- `LocalLlmConfig` validation (model path, context size, threads, sampling);
- GGUF load/init with mmap-capable llama.cpp;
- chat-template application, with a ChatML fallback;
- a separate llama context per turn;
- token streaming on a blocking worker, cancellation checked per token;
- typed `JarvisError::Model` / `InvalidArgument` / `Cancelled` failures.

It is deliberately not selectable from Kotlin yet. `backend: "local"` is still
rejected by `EngineConfig`; this phase only proves that the native dependency
cross-compiles. No model was loaded and no inference was run: there is no
Android device attached and no GGUF in the repository.

## Next step

Wire the backend into `EngineConfig` (`modelPath`, context/thread/RAM limits),
add the model import and free-RAM guard, package `libc++_shared.so`, then
measure load time, first-token latency and peak RSS on the target phone. Do not
claim offline reasoning works until that device test exists.
