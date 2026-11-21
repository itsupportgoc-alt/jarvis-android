# Latency

## What was measured, and where

Everything below was measured on the authoring machine (Windows 11, x86_64,
Rust 1.99, `cargo bench --bench latency`). **It is not a phone.** The numbers
therefore describe the algorithm's cost, not the product's end-to-end latency;
the device rows are instrumented but unmeasured and say so.

| Stage | Target (design budget) | Measured (host x86_64) | Device |
|---|---|---|---|
| Sentence chunker, 27-token reply | < 1 ms | **5.13 µs** | unmeasured |
| SSE parser, 40 delta events | < 2 ms | **27.7 µs** | unmeasured |
| Per 20 ms audio frame: RMS + VAD + visual level + viseme | < 200 µs | **2.02 µs** (0.01% of frame budget) | unmeasured |
| Speech builder, 3 lines + prosody | < 1 ms | **1.86 µs** | unmeasured |
| Wake word detection | < 300 ms | not built | — |
| ASR (Whisper base.en, 3 s utterance) | real time factor < 0.5 | not built | — |
| End of speech → ack audio | < 700 ms | network-dependent | unmeasured |
| LLM token → first spoken sentence | < 250 ms | parser 5.13 µs + network | unmeasured |
| TTS sentence → first audio | < 150 ms | Android engine-dependent | unmeasured |
| Inter-sentence gap | < 120 ms | Android queue | unmeasured |

Targets are engineering budgets chosen to keep the conversation feeling
instantaneous; they are not vendor guarantees.

### Reading the host numbers

The per-frame figure is the important one: at 50 frames per second the DSP path
spends about 100 µs of CPU per second of audio on one core (2.02 µs × 50). Even a
10× slower phone core leaves the audio thread idle >99% of the time, which is why
the capture ring is sized for jitter rather than throughput.

The chunker and SSE parser run off the audio thread. Their cost is noise next to
a network round trip; the design goal was *no full-reply buffering*, and the
benchmark confirms a token costs well under a microsecond to scan.

## Reproduce

```bash
cd core
cargo bench --bench latency            # full criterion run
cargo bench --bench latency -- --quick # one sample, used above
```

## Measuring on a device

The engine emits `latency` events (`stage`, `ms`) at each transition. They are
visible in **SETTINGS → DIAGNOSTICS** and can be captured with:

```bash
adb logcat -s JarvisEngine:V | grep -i latency
```

Suggested protocol (Pixel 6a or similar, release build, screen on, Wi-Fi 5 GHz):

1. **Wake** — 20 trials, `hey jarvis` at 0.5 m. Record the frame timestamp of
   keyword onset (from a simultaneous recording) and the first `state=listening`
   event. Report p50/p95.
2. **ASR** — 10 utterances of 2–5 s. Compare `speechEnd` to `transcript`; the
   Whisper build should log its real-time factor.
3. **First token** — `speechEnd` → first `assistantDelta`. Separate the network
   component with `curl -w '%{time_starttransfer}'` against the same endpoint.
4. **First audio** — first `speak` → TTS `onStart`. This isolates the device
   speech engine.
5. **Frame health** — `adb shell dumpsys gfxinfo ai.jarvis framestats` after a
   60 s speaking session; report janky frames. The orb allocates nothing per
   frame, so any jank is composition or GC elsewhere.
6. **Barge-in** — `bargeIn` event to audible silence. Target < 120 ms; the Rust
   VAD path cancels the token and Kotlin stops TTS on the same frame.

Record device model, Android version, build type, thermal state and whether the
recogniser was offline. Without those, latency numbers are not comparable.

## Build evidence (this workspace)

| Check | Result |
|---|---|
| `cargo test` (core) | 77 passed |
| `cargo clippy --all-targets -- -D warnings` | clean |
| `cargo bench --bench latency -- --quick` | values above |
| `ktlint` (main + test) | clean |
| `./gradlew :app:assembleDebug` (NDK 26.1 + cargo-ndk 4.1.2) | **BUILD SUCCESSFUL**, 32 MB debug APK |
| Native libraries in the APK | `libjarvis_core.so` for arm64-v8a (3.7 MB), armeabi-v7a (2.3 MB), x86_64 (4.1 MB) |
| APK manifest | package `ai.jarvis`, minSdk 29, targetSdk 35, native-code all three ABIs |
| `./gradlew :app:testDebugUnitTest` | 4 tests, 0 failures |
| Install / launch on hardware | **not run** (no device attached) |

The APK is complete, but runtime behaviour (engine load, permissions, audio,
TTS, tools) was not exercised on a device. That is the remaining verification
gap; the source compiles and packages end to end.
