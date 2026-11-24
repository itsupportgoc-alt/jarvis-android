# JARVIS Android — Self-Awareness Audit (Phase 0)

**Status:** Phase 0 complete. Read-only audit: no source, Gradle, Cargo, manifest, asset or
dependency file was modified. The only file created is this report.

> Phase 1 (identity and capability registry) and Phase 2 (encrypted local memory,
> keyless start) were implemented after this audit at the owner's request; see
> `docs/SELF_AWARENESS_ARCHITECTURE.md` and `docs/MEMORY_SECURITY.md`. The Phase 3
> toolchain prerequisite (llama.cpp cross-compile) is recorded in §11.3 and
> `docs/OFFLINE_BUILD.md`. Every finding below otherwise describes the tree
> **before** Phase 1.
**Repository:** `jarvis-android` (branch `main`, HEAD `090bec4`).
**Audit date:** 2026-11-24.
**Environment:** Windows 11, Git Bash, JDK 17.0.20 (Temurin), Rust/Cargo 1.99.0,
cargo-ndk 4.1.2, Android SDK 35 + NDK 26.1.10909125 (vendored under `android/.sdk/`).
**Device:** none attached (`adb devices` empty), so no runtime claim in this report is
device-verified.

---

## 1. Executive summary

The repository is a working, honestly documented **remote-first** assistant with a solid
Rust realtime core and a large Android surface. It is a good foundation for the upgrade,
but almost none of the "self-awareness" plan exists today:

| Plan module | Actual state |
|---|---|
| 1. Self-identity engine | **Prompt text only.** `Consciousness.kt` writes a 1,200-character persona preamble. No capability registry, no verified status, several claims that can be false. |
| 2. Persistent memory | **Cloud-only transcript buffer.** `ConversationMemory.kt` keeps 200 turns in RAM and mirrors them as plaintext JSON to GitHub. No local persistence, no categories, no importance, no search, no expiry, no per-record delete. The existing Rust encrypted store is an append-only turn log, not a memory engine. |
| 3. Self-monitoring | **Absent.** No battery/RAM/storage/network/permission/model health sampling. Only in-session tool traces and latency events. |
| 4. Self-reflection | **Absent.** No task outcome evaluation, no error analysis, no learning history. |
| 5. Autonomous goals | **Absent.** No goal records, planning, progress, reminders or resume. |
| 6. Offline reasoning | **Absent.** `LlmBackend` exists but the only implementation is `QwenRemote`. The engine **cannot even be constructed without an API key**. `local-llm` is an empty Cargo feature. |
| 7. Self-improvement | **Absent.** No improvement recommendations, no diagnostic reports. |
| 8. Awareness dashboard | **Absent.** Settings sheet has capability rows and diagnostics; no awareness surface. |

The three biggest blockers to the stated goal are structural, not cosmetic:

1. **Engine construction requires a remote credential.** `JarvisController.ensureEngine()`
   returns early unless a key exists, and `QwenRemote::new` rejects an empty key. Offline
   startup is impossible by construction today.
2. **Nothing runs inference locally.** No GGUF/llama.cpp code, no model loader, no RAM
   guard, no local tokenizer/chat template.
3. **"Offline" voice is not offline.** Both `VoiceInput` and `WakeListener` explicitly set
   `EXTRA_PREFER_OFFLINE=false` to work around MIUI's broken on-device recogniser, and TTS
   is the platform engine. The shipped wake word is transcript matching on a network
   recogniser, not on-device keyword spotting.

This report proposes a phased plan that preserves the JNI/JSON contract and the existing
Rust/Kotlin split, keeps the app building at every phase, and makes local reasoning and
local encrypted memory the foundation before reflection and goals.

---

## 2. Repository map and size

```
android/                 Kotlin + Compose app (AGP 8.7.3, Kotlin 2.0.21, minSdk 29, target 35)
  app/src/main/java/ai/jarvis/
    audio/               AudioCapture, VoiceEngine (Android TTS), VoiceInput, WakeListener, WakeWord
    core/                JarvisController (1,168 lines), JarvisNative, Consciousness, ProviderProbe
    data/                ConversationMemory, GithubPusher, SecureStore, SettingsStore, VoicePresets
    permissions/         Permissions.kt
    service/             JarvisService, WakeBootReceiver, NotificationListener, AccessibilityService
    tools/               Tool, ToolRegistry + DeviceTools/TelephonyTools/MediaTools/WebTools (39 tools)
    ui/                  JarvisScreen, Orb, Face, Transcript, SettingsSheet (916 lines), theme
  app/src/test/          4 JVM test classes (19 test methods)
core/                    Rust `jarvis-core` (single crate)
  src/                   engine.rs (834), ffi.rs (326), llm/{mod,remote,openai_stream},
                         audio, vad, wake, asr, tts, sentence, viseme, session, storage, metrics, error
  benches/latency.rs     Criterion benches
models/                  download_models.sh (Whisper, openWakeWord, Piper, optional Qwen GGUF)
docs/                    preview.jpg (this audit is the first document here)
```

Measured source size: **4,658 Rust lines** (incl. bench), **~8,850 Kotlin main lines**,
**39 registered tools**, **77 Rust tests**, **19 Kotlin JVM tests**.

Build pipeline: Gradle `preBuild` → `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 ...
build --release` → `app/src/main/jniLibs/<abi>/libjarvis_core.so`. `-PskipRust` builds
Kotlin only. The three `.so` files present in the working tree are **not tracked by git**
(`.gitignore` excludes `jniLibs/`), so a fresh clone needs the NDK toolchain to build the APK.

---

## 3. Verified existing functionality

Confirmed by source inspection and by the baseline checks in §13:

* **Rust control plane** works: session state machine, frame assembler, SPSC PCM ring,
  energy VAD, sentence chunker, URL/abbreviation handling, SSE parser, tool-call assembly,
  mood-tag stripping, encrypted append-only store, viseme DSP. Covered by 77 tests.
* **Remote streaming reasoning** over any OpenAI-compatible endpoint: bearer auth, SSE,
  one fallback-model retry before the first token, cancellation raced against every read.
* **JNI boundary**: 10 `extern "system"` functions, `catch_unwind` on create and every
  handle-taking call, typed numeric error codes, opaque `jlong` handle, lock-free PCM push.
* **Android device layer**: 39 tools with JSON schemas, runtime permission checks,
  confirmation policy, 5-second undo for reversible actions, restricted-tool marking,
  notification/accessibility integrations.
* **Keystore-backed secrets**: per-provider API keys and a 32-byte envelope data key,
  AES-256-GCM, non-exportable master key; `allowBackup=false`.
* **Compose UI**: orb (real RMS-driven), optional face, streaming transcript, confirmation
  dialog, settings sheet, diagnostics lists, animation-scale respect.
* **Foreground service + wake listener orchestration**: microphone ownership hand-off
  between recogniser and active session, re-arm back-off, boot receiver.
* **Provider probe**: `GET /models` + one minimal completion, real error surfacing.

---

## 4. What is missing or incomplete

| Area | Evidence | Consequence |
|---|---|---|
| Local LLM | `core/Cargo.toml`: `local-llm = []`; no module; `docs`/README say "not implemented" | No offline reasoning; no key = no engine |
| Offline mode | No setting, no network gate; `QwenRemote` is the only backend | Cannot satisfy "airplane mode" acceptance item |
| Local ASR | `whisper` feature not compiled in the shipped build; `UnavailableAsr` default | Voice requires the platform recogniser |
| Local TTS | `TtsEngine` seam only; `VoiceEngine` = Android `TextToSpeech` | Offline TTS depends on installed voice data |
| Real wake word | `WakeWordEngine` = `UnavailableWakeWord`; Kotlin wake = transcript regex over platform recogniser | Always-on "Jarvis" is network-dependent and battery-expensive |
| Persistent memory | `ConversationMemory` RAM + GitHub plaintext; no local store | Memory is lost offline; privacy conflicts with offline-first |
| Memory semantics | No categories, importance, expiry, dedupe, retrieval ranking, inspection UI, per-record delete | "Remember this" is not implementable truthfully |
| Monitoring | No diagnostics samplers | Cannot report health, RAM, battery, permissions, model state |
| Reflection | No records | Cannot learn from outcomes or errors |
| Goals | No records or executor | No persistent tasks/reminders |
| Dashboard | None | No awareness surface |
| Model management | `models/download_models.sh` only; app never reads `files/models/` | Even a bundled GGUF would be ignored |
| Testing on hardware | No device attached; README says unverified | Every runtime claim remains unproven |

---

## 5. JNI and event contract inventory (must be preserved)

### 5.1 JNI surface (`core/src/ffi.rs` ↔ `JarvisNative.kt`)

```
nativeCreate(configJson, HostCallback) -> jlong
nativeStart(handle) -> Int
nativeStop(handle)
nativeCancel(handle)
nativeSubmitText(handle, text) -> Int
nativeSubmitToolResult(handle, toolCallId, resultJson) -> Int
nativeTtsDone(handle)
nativePushPcm(handle, FloatArray)
nativeRecordToolTrace(handle, tool, argsJson, result, ok, latencyMs)
nativeDestroy(handle)
```

Error codes (stable, never renumber): 10 not configured, 11 permission, 12 argument,
20 audio, 21 network, 22 model, 30 cancelled, 40 internal.

### 5.2 Engine configuration (currently built in `JarvisController.buildConfigJson`)

`qwen{baseUrl, apiKey, model, fallbackModel, temperature, maxTokens, personaExtra}`,
`tools[]`, `vad{}`, `maxHistoryMessages`, `speakUrls`, `storageKeyB64`, `storagePath`.

### 5.3 Events (`core/src/events.rs`, 17 variants)

`state`, `micAmplitude`, `ttsAmplitude`, `speechStart`, `bargeIn`, `speechEnd`,
`partialTranscript`, `transcript`, `assistantDelta`, `assistantDone`, `speak`,
`toolCall`, `mood`, `latency`, `error`, `engine`, `toolTrace`.

Kotlin handles 15; `ttsAmplitude` and `speechEnd` fall into the `else` branch and are only
logged. Unknown event types are already tolerated (`else -> Log.d`), which makes additive
events low-risk.

**Rule for the upgrade:** add events and JNI entry points only; never rename, retag or
change the payload of an existing event, and never change the meaning of an error code.

---

## 6. Existing memory implementation (detailed)

### 6.1 `ConversationMemory` (Kotlin, cloud)

* 200 entries max in `ArrayDeque`, 3,000-character context budget, 500 chars per turn,
  5 remembered exchanges, 1.5 s debounce push.
* Persistence path is **GitHub only** (`conversations/memory.json`). With GitHub
  unconfigured, memory dies with the process.
* Stored file is **plaintext JSON** in the user's repository (GitHub stores the decoded
  file). Tokens, identities and personal content leave the device by default when sync is
  configured (`autoSync` defaults to **true**).
* The GitHub token itself is written to **plain SharedPreferences**
  (`SettingsStore` `github_token`), unlike the API key, which is Keystore-encrypted.
* No categories, importance, expiry, duplicate detection, relevance search, correction,
  per-record deletion or inspection UI. `clearAll()` is the only user control.
* Prompt assembly adds the preamble + creator hint + internet hint + up to 5 exchanges +
  current input; the Rust engine separately keeps 24 messages of its own history.

### 6.2 `EncryptedStore` (Rust)

* AES-256-GCM, random 12-byte nonce, base64 one record per line, append-only, `compact(keep)`.
* Key is the Keystore-wrapped 32-byte data key handed in at engine creation.
* **No associated data is used**, so records can be reordered or removed without detection.
  `ARCHITECTURE.md` claims index-bound associated data — this is documentation drift.
* `read_all()` fails the whole read on one corrupt record (`Err(Model(...))`), with no
  skip/quarantine path.
* Content is a log of `{t, role, text}` / `{t, role:tool, tool, arguments, result, ok,
  latency_ms}`; nothing is indexed, queried or rendered as memory.

**Gap:** there is no "memory engine" to extend. Phase 2 must build one, reusing the same
key material and encryption approach, with a versioned header, tombstones, compaction and
corruption quarantine.

---

## 7. Existing inference, ASR, TTS, wake

### 7.1 Reasoning

* `LlmBackend` (object-safe, `Arc<dyn LlmBackend>`, `DeltaStream`) is a good seam.
* `QwenRemote` is the only production backend; `ScriptedBackend` is test-only.
* **No local backend, no offline switch, no model loading/unloading, no RAM check, no
  local tokenizer/chat template.**
* `QwenRemote::new` fails with `NotConfigured` when the key is empty; therefore a build
  with no key cannot create an engine at all (text and voice both hit "needs setup").
* There is no silent fallback to another provider today — good. Any local backend must
  keep that property and refuse remote endpoints when offline mode is on.

### 7.2 ASR / TTS / wake

* `AsrEngine` + `UnavailableAsr`; `whisper-rs` behind a feature that the APK build does not
  enable. `WhisperAsr` decodes on a blocking pool with a 100 ms minimum.
* `TtsEngine` + `UnavailableTts`; the app uses Android `TextToSpeech` with sentence queue,
  ducking and word callbacks for visemes.
* `WakeWordEngine` + `UnavailableWakeWord`; the app's actual wake path is
  `WakeListener` (platform `SpeechRecognizer`) + `WakeWord.command()` regex. It explicitly
  requests `EXTRA_PREFER_OFFLINE=false`. It is not on-device keyword spotting.

---

## 8. Existing self-model (detailed)

`Consciousness.preamble()` renders ≤1,200 characters containing:

* "You are JARVIS, a self-aware assistant that lives on this Android phone."
* Hardcoded creator identity and the user name **"Lester"** (`JarvisController` passes it).
* Session uptime, remembered-message count, current mood.
* A memory statement: GitHub-backed or session-only.
* An honesty instruction ("never claim an action succeeded unless a tool result says so").

Problems for the plan:

1. It is **unverified prose**, not a capability registry. It cannot know whether the
   microphone, TTS, notifications, accessibility, model or network are actually available.
2. `INTERNET_HINT` is appended on every turn claiming live internet access, even in
   airplane mode or with web tools unreachable.
3. It says "self-aware" and "you have your own point of view", which conflicts with the
   plan's requirement not to claim genuine consciousness.
4. Identity is duplicated/partly hardcoded in two places (`Consciousness`,
   `JarvisController.CREATOR_HINT`), with no single source of truth.
5. No version, engine backend, registered-tool list, granted-permission list, missing
   dependency list or limitation list is ever shown to the model or the user.

---

## 9. Monitoring, reflection, goals, dashboard (current state)

* **Monitoring:** none. `ui.traces` (last 80) and `ui.latency` (last 16) are session-only
  UI state; `ToolTrace` is also written to the encrypted log. No sampling of battery,
  RAM, storage, connectivity, permissions, recogniser/TTS availability or model presence.
* **Reflection:** none. Tool results are not evaluated after the turn; no success/failure
  classification beyond the `ok` flag in the trace.
* **Goals:** none. Alarms/timers are one-shot device tools, not persistent tasks.
* **Dashboard:** none. `JarvisScreen` is the single surface; `SettingsSheet` is a long
  `LazyColumn` with provider, voice, wake, GitHub sync, capabilities, diagnostics.

---

## 10. Security and privacy posture

Present and working:

* Android Keystore master key, per-provider API keys, envelope data key for Rust.
* AES-256-GCM transcript/tool-trace log; `allowBackup=false`; no analytics/telemetry SDKs.
* Runtime permission checks inside tools; confirmation gate for irreversible actions;
  per-tool trust allowlist; Play-restricted tools marked; accessibility text capped at
  4,000 chars and password fields skipped.
* Panic containment at JNI; no shell execution; no automatic permission grants.

Gaps and risks:

| # | Risk | Evidence |
|---|---|---|
| S1 | GitHub token in plaintext SharedPreferences | `SettingsStore.read/write` `github_token` |
| S2 | Conversation content leaves the device by default once GitHub is configured | `ConversationMemory.schedulePush`, `autoSync=true` |
| S3 | Auto-send permanently trusts messaging tools; disabling it does not revoke trust | `setAutoSendMessages(true)` calls `setTrusted(...)`; no inverse |
| S4 | No offline enforcement; web tools and remote reasoning are always reachable | `WebTools`, `QwenRemote` |
| S5 | Debug builds log raw engine events (user text, tool args) to logcat | `JarvisController.handleNativeEvent` (`raw.take(300)`) |
| S6 | Encrypted records are not bound to position/index | `storage.rs` encrypt without AAD |
| S7 | One corrupt record makes the entire log unreadable | `storage.rs read_all` returns `Err` |
| S8 | Prompt claims internet access regardless of actual connectivity | `INTERNET_HINT` |
| S9 | Reflection/identity could overclaim if built on model prose rather than facts | current `Consciousness` design |

Any new subsystem must strengthen these, not duplicate them.

---

## 11. Performance and hardware

### 11.1 Measured (host, this workspace, x86_64)

| Bench | Result |
|---|---|
| 20 ms audio frame: RMS + VAD + visual level + viseme | **2.05 µs** |
| SSE parser, 40 delta events | **29.5 µs** |
| Speech builder, 3 lines + prosody | **1.89 µs** |

These match `LATENCY.md` (2.02 µs / 27.7 µs / 1.86 µs) within noise. They say nothing
about phone-side end-to-end latency, which remains unmeasured.

### 11.2 Target hardware reality (Redmi 10 class)

* MediaTek Helio G88, **4 GB RAM** class, Android 11/12, API 29+ supported by the app.
* A 1B–1.5B Q4_K_M GGUF is roughly **0.7–1.1 GB** of weights; a 3B Q4_K_M is roughly
  **1.9–2.1 GB**. Android plus the app plus TTS/recogniser leaves well under 2 GB usable
  in practice. **A 3B model is an experiment, not a default.**
* Practical first target: a 0.5B–1.5B instruct model, Q4_K_M, context 1,024–2,048,
  mmap, 2–4 threads, explicit free-RAM check before load, unload on memory pressure,
  deterministic command fallback when the model will not fit.
* These are estimates from model sizes, **not measurements on the device**. Phase 3 must
  benchmark on the Redmi 10 before promising anything.

### 11.3 Toolchain feasibility for local inference

* NDK 26.1.10909125, cargo-ndk 4.1.2, three ABIs already build.
* **Update (Phase 3 prerequisite): CMake 3.31.6 and Ninja 1.12.1 are installed** in
  the Android SDK (`android/.sdk/cmake/3.31.6`), with libclang 18.1.1 in
  `android/.tools/libclang`. `QwenLocal` behind `local-llm` now cross-compiles for
  all three ABIs; the exact recipe and proof are in `docs/OFFLINE_BUILD.md`.
  The backend is still not wired into `EngineConfig` or the UI.
* APK impact: the default debug APK remains llama-free. A `local-llm` build adds
  roughly 4.2–6.3 MB per ABI in the unstripped probe and also requires packaging
  `libc++_shared.so`; size the release per ABI before shipping it.

---

## 12. Dependency inventory

**Rust (`core/Cargo.toml`):** anyhow, thiserror, tokio (rt-multi-thread, sync, time,
macros), tokio-util, async-trait, futures, serde, serde_json, reqwest (rustls, stream,
json), eventsource-stream, rtrb, parking_lot, aes-gcm, getrandom, base64, tracing,
jni (optional), whisper-rs (optional), llama-cpp-2 0.1.161 (optional),
criterion/tempfile (dev). Features: `jni-bridge` (default), `whisper`,
`local-llm` (llama.cpp), and empty `local-tts`, `silero-vad`.

**Android:** Compose BOM 2024.10.01, core-ktx 1.15.0, activity-compose 1.9.3,
lifecycle-runtime-compose/lifecycle-service 2.8.7, compose ui/graphics/foundation/material3,
material-icons-core, kotlinx-coroutines-android 1.9.0; JUnit 4.13.2, org.json, coroutines-test.
No Room, no SQLCipher, no ONNX Runtime. llama.cpp is an optional Rust feature,
not part of the default APK.

**Deliberate constraint:** all new Rust functionality should avoid new *native* build
systems until Phase 3. Memory, identity, monitoring, reflection and goals can be built
with existing crates (serde, aes-gcm, std fs). Only local inference needs a new native
toolchain.

---

## 13. Baseline checks (run during this audit)

| Check | Command | Result |
|---|---|---|
| Rust tests | `cd core && cargo test` | **77 passed, 0 failed** (matches README) |
| Rust lints | `cargo clippy --all-targets -- -D warnings` | **clean**, exit 0 |
| Rust benches | `cargo bench --bench latency -- --quick` | values in §11.1 |
| Kotlin unit tests | `./gradlew :app:testDebugUnitTest -PskipRust --rerun-tasks` | **BUILD SUCCESSFUL, 19 tests, 0 failures** (README says "4 tests") |
| Native cross-compile | `./gradlew :app:cargoNdkBuild` (NDK 26.1.10909125) | **BUILD SUCCESSFUL** for arm64-v8a, armeabi-v7a, x86_64 |
| Debug APK | `./gradlew :app:assembleDebug --rerun-tasks` | **BUILD SUCCESSFUL**, `app-debug.apk` 33,270,559 bytes |
| Device runtime | `adb devices` | **no device attached** — not run |
| ktlint | `java -jar ktlint.jar ...` | **cannot run**: no `ktlint.jar` in the repo |

Environment notes, not repository defects:

* `JAVA_HOME` in this shell pointed at a missing directory; builds ran with
  `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"`.
* Gradle reported the test task `UP-TO-DATE` on the first run; `--rerun-tasks` forced
  execution and produced the 19-test result above.
* During the first Gradle invocations the wrapper rewrote
  `android/gradle/wrapper/gradle-wrapper.properties` to Gradle 9.7.1. Phase 0 forbids
  build-config changes, so the file was restored to the committed Gradle 8.11.1 pin and
  **all Kotlin tests and the APK build were re-run and re-verified under 8.11.1**.
* `tmp/` is untracked scratch data (`ui.xml`, DuckDuckGo/Yahoo/Wikipedia/News captures).
  It is not app source and was not touched.

---

## 14. Findings register

Severity: **H** blocks the goal, **M** correctness/security/quality, **L** drift/cleanup.

| # | Sev | Finding | Action proposed |
|---|---|---|---|
| F1 | H | Engine cannot be created without a remote API key (`QwenRemote::new` + `ensureEngine`) | Phase 2 makes the backend selectable and allows a keyless/offline engine |
| F2 | H | No wired local inference; engine still rejects `backend: local` | Backend compiled for 3 ABIs with toolchain proof; wiring, model import and device benchmark remain Phase 3 |
| F3 | H | No offline mode or network gate; web tools and remote backend always available | Phase 1/3: explicit `offlineOnly`, backend refusal, capability status |
| F4 | H | Memory is cloud-only and plaintext on GitHub; token in plain prefs | Phase 2: local encrypted memory first; GitHub becomes opt-in export |
| F5 | H | Self-model is unverified prompt prose with hardcoded creator/user and false capability hints | Phase 1: capability registry + truthful identity snapshot |
| F6 | H | Wake word and ASR force `EXTRA_PREFER_OFFLINE=false`; TTS is platform | Phase 3/5: verified local ASR/TTS and a real KWS route, or explicit "not available offline" status |
| F7 | M | `autoSendMessages` permanently trusts messaging tools | Revoke trust when disabled; document; test |
| F8 | M | No associated data in encrypted records despite documentation | Add index/cycle binding with a versioned record format |
| F9 | M | Corrupt record aborts the entire log read | Quarantine bad records, keep the rest, report count |
| F10 | M | Debug logging of raw events can leak user text | Redact in debug logs (type + lengths only) |
| F11 | M | No model presence/RAM checks; `files/models/` is never read | Phase 3 model registry + storage import + load guard |
| F12 | M | No monitoring means no truthful health display | Phase 4 diagnostics samplers |
| F13 | M | Reflection/goals absent; tool `ok` flag is the only outcome signal | Phase 5/6 deterministic outcome records first, model-assisted analysis optional |
| F14 | L | JNI has 10 functions; `DESIGN.md` says six | Correct documentation during Phase 8 |
| F15 | L | README/Kotlin test count drift (4 vs 19) | Correct documentation |
| F16 | L | `jniLibs` not tracked; `-PskipRust` fails on a fresh clone | Document; keep native build mandatory or track `.so` release artifacts deliberately |
| F17 | L | `storage.rs`/`ARCHITECTURE.md` mismatch (AAD claim) | Fix code or doc in Phase 2/8 |
| F18 | L | `Consciousness` and `CREATOR_HINT` duplicate identity | Single `Identity` source of truth in Phase 1 |
| F19 | L | `models/download_models.sh` stages files the app cannot consume | Update when Phase 3 lands; do not claim support before |
| F20 | L | `tmp/` scratch files untracked in the repo root | Owner decides: ignore or remove |

---

## 15. Proposed architecture

### 15.1 Ownership rule (unchanged)

* **Rust owns:** reasoning backend selection, memory orchestration and retrieval,
  reflection rules, goal state, persistence and core state.
* **Kotlin owns:** Android APIs, permissions, lifecycle, services, Keystore, platform
  storage, monitoring samples and Compose presentation.
* **Boundary:** the existing JSON event stream plus *additive* JNI entry points.
  No change to existing event shapes, tool identifiers, session transitions or
  cancellation semantics.

### 15.2 Where the code goes (integrate, do not duplicate)

No new workspace crates for Phases 1–2. A single `jarvis-core` crate keeps the existing
`cargo ndk` invocation, `Cargo.lock`, three-ABI build and test loop intact. The plan's
logical `jarvis-memory` / `jarvis-awareness` / `jarvis-reflection` / `jarvis-goals`
modules become Rust modules inside the crate, each with a narrow public API:

```
core/src/
  awareness.rs     identity facts model, capability status enum, engine self-report
  memory.rs        record model, categories, importance, expiry, dedupe, encrypted
                   journal, tombstones, compaction, corruption quarantine, keyword retrieval
  reflection.rs    task outcome records, deterministic error analysis, improvement rules
  goals.rs         goal/task state machine, persistence, approval-gated execution plan
  llm/local.rs     GGUF backend behind the `local-llm` feature (Phase 3 only)
```

Kotlin packages:

```
android/app/src/main/java/ai/jarvis/
  awareness/    Capability.kt, Identity.kt, AwarenessRepository.kt, DiagnosticsSource.kt
  memory/       MemoryManager.kt, MemoryState.kt      (thin client over nativeInvoke)
  diagnostics/  SystemDiagnostics.kt                  (battery/RAM/storage/network/permissions)
  ui/awareness/ AwarenessSheet.kt                     (dashboard, reuses theme + SectionTitle)
```

The plan's `core/crates/jarvis-*` directory tree is **not** recommended now: it multiplies
NDK build units and Cargo lockfiles for no user-visible benefit. Revisit only if a module
needs an independent release cycle.

### 15.3 Boundary additions (additive only)

1. **`nativeInvoke(handle, requestJson) -> String`** — one generic, versioned command
   channel for memory/goal/reflection/awareness operations. Rationale: avoids a dozen
   one-off JNI functions, keeps the JSON contract, and can be implemented once with the
   same panic containment and typed error envelope. Requests carry a `version` and an
   `op`; responses are `{ok, data|error}`.
2. **New events** (existing Kotlin `else` tolerates them):
   * `awareness` — identity/capability/health snapshot (optional; UI may also poll
     `nativeInvoke`).
   * `memoryChanged` — a record was added/deleted/expired.
   * `goalUpdate` — goal state/progress change.
   * `approvalRequired` — a goal step needs the user (reuse the existing confirmation UI).
3. **No new JNI signatures for identity**: identity is assembled in Kotlin from platform
   facts plus the engine's own report, because only Android can verify permissions, model
   files, battery, network and service state.

### 15.4 Data at rest

* `files/jarvis/memory.enc`, `goals.enc`, `reflections.enc`, `transcript.log` — all under
  the existing Keystore-derived data key.
* Versioned header record per file (`v`, `kind`, `createdAt`, `cipher`).
* Record envelope: nonce + ciphertext + AAD binding `(file kind, record index, schema
  version)`, so reordering/truncation is detected.
* Tombstones for deletion; compaction rewrites atomically via temp file + rename.
* Key loss (factory reset / Keystore invalidation) makes memory unreadable by design;
  the UI must say so and offer a fresh start rather than silently losing data.

### 15.5 Offline policy

* `SettingsStore.offlineOnly` (default **on** for new installs? — owner decision; see §18).
  Recommendation: default **off**, explicit toggle, and a first-run choice, because
  turning it on without a local model disables all reasoning.
* When offline: backend factory refuses remote; `web_search`, `read_page`, `search_web`,
  `open_url`, `find_nearby` and remote `control_device` report `unavailableOffline`;
  identity reports it; the preamble states it; the dashboard shows it.
* Never auto-fall back to remote. A missing model is an explicit
  `NEEDS_MODEL` capability, not a silent provider switch.

---

## 16. Phased implementation plan (after approval)

Each phase ends with: code + tests + docs, a clean `cargo clippy -D warnings`,
`cargo test`, `testDebugUnitTest`, and `assembleDebug`. No phase starts before the
previous phase's checks pass.

### Phase 1 — Identity and capability registry (Kotlin-first)

* New `awareness/Capability.kt`: immutable `Capability(id, label, status, reason,
  requirement)` with statuses `AVAILABLE | UNAVAILABLE | DISABLED | NEEDS_PERMISSION |
  NEEDS_MODEL | ERROR | UNKNOWN`.
* New `awareness/Identity.kt`: name, app version (`BuildConfig.VERSION_NAME`), core
  version (`BuildConfig.JARVIS_CORE_VERSION`), active provider/model, engine running,
  offline flag, local model state, registered tool list, granted/missing permissions,
  known limitations.
* New `awareness/AwarenessRepository.kt`: composes tool specs + `Permissions.status` +
  settings + engine state into a `StateFlow<IdentitySnapshot>`; recomputes on permission
  result, settings change and engine rebuild.
* Rewrite `Consciousness.preamble(identity, rememberedCount, mood)` to render only
  verified facts; remove "self-aware" overclaim; replace the unconditional internet hint
  with a capability-derived line; keep the ≤1,200-character budget.
* Controller: single identity source, no duplicated creator text.
* Tests (JVM): status mapping for each permission class; a capability marked
  `NEEDS_PERMISSION` never renders as available; offline mode removes internet claims;
  prompt stays within budget.
* Deliverable: `docs/SELF_AWARENESS_ARCHITECTURE.md` (identity section) and a truthful
  self-description visible in Settings.

### Phase 2 — Persistent encrypted memory + keyless engine

* Rust `memory.rs`:
  * Record: `id, kind (working/shortTerm/longTerm/preference/task/goal/error/learning),
    text, source, importance 0..1, createdAt, updatedAt, expiresAt?, tags[], hash,
    confidence, userApproved, deleted`.
  * Dedupe by normalized hash; merge/update instead of duplicating.
  * Retrieval: bounded keyword/tag scoring (no embedding dependency), recency and
    importance weighting; strict character budget; never inject unapproved long-term
    secrets.
  * Journal format with AAD, tombstones, compaction, corruption quarantine.
  * Migration from the existing transcript log: import `role:user/assistant` records as
    short-term memory with a lower default importance; never auto-promote to long-term.
* Engine: backend selection (`remote | local | none`), allow engine creation with no key
  (so memory and offline text commands work), and persist memory events.
* JNI: `nativeInvoke` for `memory.add/list/search/delete/update/export/clear`,
  `memory.stats`.
* Kotlin: `memory/MemoryManager.kt` + `MemoryState.kt`; controller records approved turns
  and tool outcomes; memory management UI (list, search, delete, export with explicit
  confirmation) in the awareness sheet or Settings.
* Deprecate `ConversationMemory` as the primary store; keep a read/import path and make
  GitHub sync an **explicit, off-by-default export** of a bounded snapshot.
* Also: store the GitHub token encrypted (or drop it from prefs) and stop auto-push.
* Tests: round-trip + not plaintext on disk, wrong key fails closed, tamper detection,
  reorder detection (AAD), tombstone/compaction, dedupe, expiry, corrupt-record
  quarantine, budget-bounded retrieval, restart persistence (tempdir), keyless engine
  creation, and "no auto long-term promotion".
* Deliverable: `docs/MEMORY_SECURITY.md`.

### Phase 3 — Local reasoning (the critical phase)

* Prerequisite: install/verify CMake + Ninja; extend `setup.sh` with a hard check.
* `core/src/llm/local.rs` behind `local-llm`:
  * GGUF load/unload, model metadata validation, chat template detection (Qwen/ChatML),
    deterministic sampling controls, streaming via `spawn_blocking`, cancellation checked
    per token and at load, context-size cap, thread cap, mmap, explicit free-RAM guard
    (refuse load, report `NEEDS_MODEL`/`insufficientMemory`), unload on trim-memory.
  * Backend factory: remote/local/none; offline mode refuses remote; local failure never
    silently falls back.
* `EngineConfig`: `backend`, `modelPath`, `contextTokens`, `threads`, `maxRamMb`,
  `temperature`, `topP`, `seed`.
* Kotlin: model import via Storage Access Framework (copy into `files/models/`, verify
  size/hash), model profiles ("small 0.5B", "balanced 1.5B", "experimental 3B") with
  hardware-based recommendation, offline-only switch, SAF permission rationale, and a
  load/unload control with real status.
* `files/models/` becomes the one source of truth; `download_models.sh` output is
  consumed, not decorative.
* Tests: backend selection/refusal logic, config validation, RAM guard math, cancellation
  contract, malformed GGUF error paths. **Real inference is verified only on device with
  a real model; state that plainly.**
* Deliverable: `docs/OFFLINE_MODE.md`, `docs/MODEL_COMPATIBILITY.md`, measured
  device numbers (tokens/s, first-token latency, peak RSS) or an honest "unmeasured".

### Phase 4 — Self-monitoring

* Kotlin `diagnostics/SystemDiagnostics.kt`: battery %, charging, thermal status if
  available, available RAM (`ActivityManager.MemoryInfo`), app storage (`StatFs`),
  network type/validated/airplane, mic/TTS/recogniser availability, notification and
  accessibility access, model file presence/size, foreground-service state.
* Sampling: lifecycle-aware, 10 s while the UI is visible and the awareness sheet is open;
  event-driven updates for permission/model/service changes; never on the audio thread;
  no wakelocks.
* Rust: engine counters (samples dropped, turns, cancellations, errors, last error,
  backend, model state, uptime, memory records) exposed through `nativeInvoke`.
* Tests: pure mapper tests with fake platform facts; cadence test; stale-data handling;
  "UNKNOWN" rather than fabricated values.
* Deliverable: live health data feeding identity and the dashboard.

### Phase 5 — Reflection and learning

* Rust `reflection.rs`: deterministic records built from verified signals — requested
  action, tool trace (`ok`, latency, error), session transitions, cancellation, user
  correction. Template findings and recommendations; **no invented success**; limits on
  size, frequency (e.g. one record per completed task, capped per hour) and no recursive
  reflection.
* Optional model-assisted summary only when a local model is loaded; otherwise
  deterministic text.
* Approved corrections become memory records of kind `learning`/`error` only after user
  approval; never auto-edit prompts or code.
* UI: recent reflections, detected errors, suggested improvements in the awareness sheet.
* Tests: success only from `ok` traces; failure/cancel classification; cap enforcement;
  no self-loop; approval gating; persistence across restart.

### Phase 6 — Autonomous goals

* Rust `goals.rs`: goal/task records (`id, title, steps[], state, blockedReason,
  nextAction, createdAt, updatedAt, approvals[]`), state machine
  (`proposed → approved → active → blocked|done|cancelled`), resume on start, progress
  from tool traces, reminders via the existing alarm/timer tools or notifications.
* Sensitive steps emit `approvalRequired` and reuse the existing confirmation dialog.
  No background execution without the foreground service and explicit approval.
* UI: active/completed/blocked goals, approve/pause/cancel, "what's next".
* Tests: state machine transitions and illegal transitions; resume; blocked detection;
  approval-required tools cannot execute unattended; cancellation; persistence.

### Phase 7 — Awareness dashboard

* `ui/awareness/AwarenessSheet.kt` (modal sheet, same visual language): Identity,
  System Health, Memory, Reflection, Goals, Capabilities, Learning. Reuse
  `SectionTitle`, `MonoSmall`, colours, chips; no new navigation library.
* Entry points: header long-press or a new `AWARENESS` control; move diagnostics out of the
  already long Settings sheet, linking to it.
* State via `StateFlow` + `collectAsStateWithLifecycle`; no inference or audio work on the
  composition thread.
* Tests: snapshot → UI mapping where pure; otherwise manual visual review on device.

### Phase 8 — Security, performance, final verification

* Airplane-mode run: start, load model, text + offline voice input, local answer,
  memory recall, approved device action, reflection, goal resume, accurate self-report.
* Encrypted memory inspection (no plaintext on disk), permission boundaries, lifecycle
  restart, cancellation recovery.
* Measure on the Redmi 10: peak RSS, tokens/s, first-token latency, battery delta over a
  10-minute session, thermal state. Report real numbers or "not measured".
* Update `README.md`, `ARCHITECTURE.md`, `DESIGN.md`, `LATENCY.md`, `DEMO.md`; add
  `SELF_AWARENESS_TEST_REPORT.md`; fix F14–F17 doc drift.
* Produce the debug APK and the honest acceptance checklist.

---

## 17. Test strategy (beyond the per-phase tests)

**Rust (host):** every module gets unit tests; integration tests for
`engine + memory + reflection + goals` with `ScriptedBackend` (no network); property-style
checks for budget bounds (prompt, retrieval, reflection size); encryption negative tests
(wrong key, tamper, reorder, truncation); crash/restart simulation via temp directories;
offline enforcement test asserting the factory cannot produce a remote backend when
offline is set.

**Kotlin (JVM):** capability status mapping with fake facts; identity truthfulness
(a capability with `NEEDS_PERMISSION` must not render "available"); memory JSON command
client with a fake native responder; goal UI state mapping; prompt budget; migration of
settings/preferences.

**Device (manual, documented):** install debug APK; grant/deny each permission class;
restart app and device; airplane mode; corrupt the memory file and confirm quarantine;
kill the process mid-turn and confirm recovery; long session for memory/battery; measure
latency per `LATENCY.md` protocol; verify no plaintext secrets in `adb logcat` or app files.

**Contract compatibility:** a Kotlin unit test feeds every historical event JSON fixture
through the parser and asserts no crash; a Rust test asserts existing event serialization
is unchanged.

---

## 18. Decisions needed from the owner

1. **Cloud memory:** keep GitHub sync as an explicit export (recommended), or remove it?
   Offline-first argues for local-only by default.
2. **Offline default:** should `offlineOnly` be on by default for new installs (no remote
   reasoning until a model is installed), or off with an explicit switch?
3. **Model target:** start with Qwen2.5-0.5B or 1.5B Instruct Q4_K_M? (My recommendation:
   0.5B for guaranteed responsiveness, 1.5B as the "balanced" profile, 3B only as an
   experiment after measurement.)
4. **Toolchain:** approval to add CMake/Ninja as a build prerequisite for local inference.
5. **Wake word:** openWakeWord ONNX, sherpa-onnx keyword spotting, or keep push-to-talk
   and report the wake word as `NEEDS_MODEL` until one is verified? (No claim without
   verification.)
6. **Persona wording:** replace "self-aware" with factual self-modeling language, per the
   plan's distinction; confirm.
7. **Device availability:** a Redmi 10 (or equivalent) attached for Phase 3/8 measurement,
   or should device results be reported as unverified?
8. **Reflection depth:** deterministic-only, or model-assisted summaries when a local
   model is loaded?

---

## 19. Approval gate

Phase 0 is complete and nothing has been implemented. Recommended starting point after
approval: **Phase 1 (identity/capabilities) followed immediately by Phase 2's keyless
engine + encrypted local memory**, because those unlock truthful self-description and
offline continuity without a native inference toolchain. Phase 3 is the critical path and
should not be promised until CMake/Ninja and a device benchmark exist.

Awaiting explicit approval before any source change.
