# SEVEN

An offline-first voice assistant for Android: Rust engine, OpenAI-compatible
remote reasoning, device tools, and an arc-reactor UI that reacts to real audio.
The assistant is called **Seven**; say "Seven" (or "Hey Seven") to wake it.

![SEVEN interface — design mockup](docs/preview.jpg)

## Download

| Platform | File | Notes |
|---|---|---|
| **Windows 10/11** | **[SEVEN-Windows-0.1.0-portable.exe](https://github.com/itsupportgoc-alt/jarvis-android/releases/download/v0.4.2/SEVEN-Windows-0.1.0-portable.exe)** (80 MB) | Single portable executable, no installer. Unsigned: SmartScreen shows *Windows protected your PC* → **More info → Run anyway**. Needs a microphone. |
| **Android 10+** | **[seven-0.4.2-debug.apk](https://github.com/itsupportgoc-alt/jarvis-android/releases/download/v0.4.2/seven-0.4.2-debug.apk)** (33 MB) | Includes arm64-v8a, armeabi-v7a and x86_64 libraries. Debug-signed: enable "install unknown apps", then open the file. On Android 13+ also allow **restricted settings** in App info before the accessibility switch will stay on. |

All releases: <https://github.com/itsupportgoc-alt/jarvis-android/releases>.

The APK is **not** Play Store ready (restricted SMS/call-log/accessibility
permissions; see the policy notes below).

> The image above is a design mockup of the interface, not a screenshot from a
> device.

```
android/   Kotlin + Jetpack Compose app
core/      Rust engine compiled to libjarvis_core.so
desktop/   Electron app for Windows (SEVEN for Windows)
models/    model download scripts and local model storage (not committed)
```

## SEVEN for Windows

There is a desktop build in [`desktop/`](desktop/README.md): the same surface,
wake word, spoken "stop" and streaming replies, as a normal Windows
application. Recognition and speech use Windows' own engines (SAPI), so audio
never leaves the PC; only the chat request goes to the endpoint you configure.

```bash
cd desktop
npm install
npm start          # if npm blocks Electron's post-install: node node_modules/electron/install.js
```

Windows Settings, the voice picker, TEST CONNECTION, MUTE and the transcript
work as on the phone. The device tools (calls, SMS, apps, torch, screen
reading) are Android-only — the desktop build talks and listens, and types.

The Android package id is still `ai.jarvis`; only the assistant's name, the wake
word and every user-facing string changed to Seven.

## What's new

* **Renamed to Seven.** App name, notifications, transcript labels, prompts,
  Settings copy, the voice preset and the wake word are all "Seven". The old
  wake word no longer matches.
* **The microphone only closes on STOP.** A spoken reply reopens it, and a
  silent session re-arms instead of giving up, with a backoff that grows to 2 s
  so it does not spin the recogniser. MIC toggles it and STOP closes it; nothing
  else turns it off.
* **The wake word works with the app closed.** Wake-word listening is on by
  default and runs in a microphone foreground service that survives leaving or
  swiping away the app (an alarm brings it back), and reboots. STOP on the
  notification ends it.
* **Waking is one tap away, and the mic is never a mystery.** The main screen
  shows `MIC HELD` / `MIC OFF` next to the phase and a `WAKE ON` / `WAKE OFF`
  button in the header, and the ongoing notification carries the same switch
  (`Wake off` / `Wake on`) next to `Stop`, with the text saying the microphone
  is being held. Turning it off stops the service, removes the notification and
  releases the microphone — the status-bar mic indicator goes away with it.
* **The microphone works every session.** Each listening session binds a fresh
  `SpeechRecognizer` (a reused instance went deaf after one session on a Redmi
  10), with 4 s ready and 20 s stall watchdogs that rebind instead of hanging.
* **A bare "Seven" is not a command.** Hearing only the name keeps the session
  open for the real instruction instead of answering the name.
* **Tagalog.** Settings → VOICE → *Tagalog* listens with the Filipino recogniser
  (`fil-PH`) and speaks with the installed Filipino voice; the model is told to
  answer in whichever language you used. Unavailable voices fall back to English
  rather than going silent.
* **The voice is chosen, not left to the engine.** SEVEN speaks with an
  installed British male system voice when there is one (`en-gb-x-gbd`, `rjs`,
  `gbb` — the 112-150 Hz set measured on a Redmi 10), preferring a local voice
  over a network one and a male voice in another region over a female voice in
  the right one; plain names such as *Daniel* or *Arthur* from other engines are
  recognised as male, and *female* names never are. The **Seven** preset runs at
  pitch 0.80 / rate 0.97: clipped and measured, the JARVIS register. A phone with
  no male voice installed falls back shallowly (pitch ×0.92, floored at 0.70) so
  it sounds low rather than slowed down. Unit-tested in `VoiceSelectionTest`.
  A preset brings its own rate and pitch; an install that never saved the
  sliders is migrated once to them, because older builds had written their old
  defaults into prefs and those quietly overrode every retune.
* **Speech tuned for misheard words.** The recogniser keeps the microphone open
  for the first 1.2 s of speech and 2 s of trailing silence, waits 3.5 s for a
  real final result, and treats "Play Seven" / "Play 7" (what this phone returns
  for "Hey Seven") as the name rather than a command. The model is told the
  transcript can be misheard and now asks a short clarifying question instead of
  guessing.
* **Faster replies.** The NVIDIA preset now uses
  `nvidia/nemotron-3.5-lightning-30b-a3b`; the previous 120B vision model took
  27–34 s per turn on a Redmi 10, this one answers in about 2 s.

## Status at a glance

Honesty first — this is a complete source tree, not a store-ready product. What
is built, and what was verified:

| Piece | State | Verified |
|---|---|---|
| Rust control plane (session machine, sentence chunker, SSE parser, tool-call assembly, mood tags, VAD, ring buffer, encrypted store, encrypted memory, viseme DSP) | Implemented | `cargo test` (94 tests), `cargo clippy -D warnings`, `cargo bench`; cross-compiled to all 3 ABIs |
| Remote reasoning (OpenAI-compatible SSE, model fallback, cancellation) | Implemented | Live calls against NVIDIA NIM from a Redmi 10; first token ~0.5–2.3 s |
| Local memory + keyless start (AES-256-GCM journal, approval policy, Settings review/export) | Implemented | Rust + JVM tests; no device run |
| JNI boundary (`catch_unwind`, typed codes, lock-free PCM push) | Implemented | Compiles on host; only runs in a JVM |
| Kotlin device layer (~38 tools), Keystore, permissions, services | Implemented | `ktlint` clean; JVM tests |
| Compose UI: orb, character face, transcript, confirmation, undo, settings, diagnostics | Implemented | `./gradlew assembleDebug`; built and installed on a Redmi 10 |
| Voice input | Android `SpeechRecognizer`, one recogniser per session, English and Tagalog | **Verified on device**: transcripts captured across consecutive sessions |
| Android TTS | Implemented, sentence-streamed, ducking, real word timings for visemes | **Verified on device**: `en-gb-x-gbd-local` and `fil-ph-x-fic-local` |
| Wake word ("Seven") | Implemented with the system recogniser inside the foreground service | **Verified on device**: wake, listen, reply, re-arm |
| **Local Qwen (GGUF)** | **Opt-in `-PlocalLlm` build.** Engine selection, Settings model import and a RAM guard are wired | Cross-compiled for arm64/armv7/x86_64; no model loaded or inference run |
| **Piper / sherpa-onnx TTS** | **Not compiled.** `TtsEngine` seam + Piper model download only | — |
| Device latency budget (wake/ASR/TTS/end-to-end) | Instrumented | First token and full-turn timings measured on a Redmi 10; no ASR RTF benchmark |

If you need a working "speak → answer → speak" demo today, it works through the
system recogniser and system TTS with a compatible endpoint key. The Rust Whisper
build is the route to a guaranteed-offline ASR; see below.

## What it feels like

One full-screen surface, no app bar.

* **Orb** — concentric arcs, 8 s/revolution at idle, 2 s while thinking, real
  mic RMS blooming outward as radial bars while listening, gold pulse driven by
  TTS word envelopes while speaking, red flicker on fault. Drawn with Canvas;
  geometry and colours are remembered, so the draw path allocates nothing.
* **Character face** — optional: layered vector head, brows/eyes/pupils/mouth,
  random blinks every 3–7 s, slow sway, mood-driven expression, visemes from the
  word the TTS engine is currently speaking.
* **Say "stop"** — while SEVEN is thinking or reading a reply, saying *stop*,
  *stop it*, *cancel*, *quiet*, *enough* or *shut up* cuts the reply off at once
  and drops the turn. SEVEN stops talking on its own instead of you waiting it
  out. The wake word stays armed afterwards, so *"Seven …"* starts again. While
  the reply is running, wake matches are ignored (the microphone can hear SEVEN
  itself); only the stop words act.
* **Transcript** — slides up, user in cyan, SEVEN in gold, tokens appear while
  the model streams. It stays pinned to the newest line, so a reply is visible
  without scrolling; dragging up pauses the follow, dragging back resumes it.
* **Bottom controls** — MIC/STOP (long-press force-stops), MUTE, INTERRUPT
  (kills the LLM stream and playback), TRACE/SETTINGS, plus a text field.
* **Confirmation sheet** — "SEVEN wants to call Maria. Confirm / Cancel." with
  a five-second UNDO affordance for reversible actions.
* **Animation scale** respected: with animations disabled the orb becomes a
  static state ring and the face holds still.

## Requirements

* Android 10 (API 29) minimum, target/compile API 35
* JDK 17, Gradle 8.11.1 (wrapper committed), AGP 8.7.3, Kotlin 2.0.21
* Android SDK platform 35 + build-tools 35.0.0
* NDK r26+ (`26.1.10909125` pinned), Rust 1.80+ (built with 1.99), cargo-ndk 4.1.2
* A device with a microphone; arm64-v8a recommended

## Setup and build

```bash
./setup.sh                  # targets, cargo-ndk, SDK/NDK checks, local.properties
./models/download_models.sh # optional on-device models
./android/gradlew -p android assembleDebug
```

Gradle invokes, via `preBuild`:

```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o app/src/main/jniLibs build --release
```

Rust only (fast loop):

```bash
cd core
cargo test
cargo clippy --all-targets -- -D warnings
cargo bench --bench latency
```

Kotlin only, without the NDK (still needs SDK 35):

```bash
./android/gradlew -p android assembleDebug -PskipRust
```

Install:

```bash
android/.sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Configure the reasoning endpoint

Open **SETTINGS → REASONING ENGINE**, pick a provider, and paste that provider's
key. Every preset fills the endpoint and two model IDs, and **GET A KEY ↗** opens
the provider's console. The default endpoint is the **Freebuff API**
(`https://freebuff.com/api/v1/paid`, model `deepseek-v4.1-flash`): create
and fund a key at <https://freebuff.com/account/api> and paste it in.

The **NVIDIA NIM** preset (`https://integrate.api.nvidia.com/v1`) defaults to
`nvidia/nemotron-3.5-lightning-30b-a3b` with `z-ai/glm-5.3-flash` as the
fallback. The Llama 3.x NIM endpoints were retired in August 2026 and now answer
`410 Gone`; the 120B vision models work but are far too slow for voice.

### Start a build already connected

The repository ships no key — a key inside a published APK is readable by
anyone who unpacks it, and it spends the owner's balance. To get a **personal**
build with the Freebuff key already sealed in, provide it at build time from one
of these (all gitignored / not in the repo):

```bash
# android/local.properties  (recommended; not committed)
freebuff.api.key=sk-…

# or
FREEBUFF_API_KEY=sk-… ./android/gradlew -p android assembleDebug
# or
./android/gradlew -p android assembleDebug -PfreebuffApiKey=sk-…
```

On first launch that key is encrypted into the Android Keystore and the app
starts configured — no paste, no setup screen. Builds released here carry no key
and open on the same preset, asking for one.

Free tiers (`FREE TIER` row):

| Preset | Base URL | Default model |
|---|---|---|
| Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| Cerebras | `https://api.cerebras.ai/v1` | `llama-3.3-70b` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` | `gemini-2.0-flash` |
| Mistral | `https://api.mistral.ai/v1` | `mistral-small-latest` |
| GitHub Models | `https://models.github.ai/inference` | `openai/gpt-4o-mini` |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | `nvidia/nemotron-3.5-lightning-30b-a3b` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-7B-Instruct` |
| SambaNova | `https://api.sambanova.ai/v1` | `Meta-Llama-3.3-70B-Instruct` |
| Hugging Face | `https://router.huggingface.co/v1` | `meta-llama/Llama-3.1-8B-Instruct` |
| Zhipu GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| DashScope (Qwen) | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `qwen3-30b-a3b` |

Paid / own key (`PAID / OWN KEY` row): OpenAI, Anthropic, xAI Grok, DeepSeek,
OpenRouter, Together, Fireworks, Perplexity, Cohere, Moonshot Kimi, Novita,
Hyperbolic, DeepInfra, Nebius, Vercel AI and the BlackPig relay. Exact base
URLs, defaults and console links live in `PROVIDER_PRESETS`
(`android/app/src/main/java/ai/jarvis/data/SettingsStore.kt`).

All of them speak OpenAI Chat Completions — `POST {base_url}/chat/completions`
with `Authorization: Bearer <key>` — so any other compatible host works too: type
its base URL and two model IDs. Model IDs must exist for your key; check the
provider's console or `GET {base_url}/models`.

**Test before you talk to it.** `TEST CONNECTION` calls `GET {base_url}/models`
with the key in the field (or the stored one), then sends one minimal
completion, and reports the real answer:
`Key accepted. N models available.` plus tappable model chips, or the exact
failure — `HTTP 401: invalid api key`, `HTTP 403: recharge_required`, a timeout,
or a DNS error.

Keys are entered on the phone and kept per provider — switching from Groq to
OpenAI and back keeps both — each encrypted with a non-exportable AES-256-GCM
key in the Android Keystore. A key is never written to plain preferences, logs
or the transcript store, and none is baked into the APK or this repository; only
the configured host receives it. `Apply & restart engine` rebuilds the native
engine with the new endpoint. If the primary model is unknown to the account,
the Rust backend retries once with the fallback **before** emitting any token.

## Languages

SEVEN understands and answers in **English** and **Tagalog (Filipino)**.
Settings → VOICE → spoken language:

| Choice | Recogniser | Voice |
|---|---|---|
| Phone language (default) | the phone's own locale | the voice preset's |
| English | `en-US` | the voice preset's (British by default) |
| Tagalog | `fil-PH` | `fil-PH`, falling back to the preset's English if no Filipino voice is installed |

The wake listener uses the same recogniser language, and the model is told to
answer in whichever language you just used. Tagalog is written by the model, so
the accuracy of the spoken reply depends on the endpoint you pick.

Knowing Filipino is a property of the speech service, not of this app. On a
Redmi 10 (Android 13) the bundled `AiAiSpeechRecognitionService` rejects both
`fil-PH` and `tl-PH` with `ERROR_LANGUAGE_NOT_SUPPORTED`, so speaking Tagalog is
not understood there — the app notices the refusal, records the tag, and keeps
listening in the phone's language instead of going deaf. Typing Tagalog still
works, and the Filipino voice still speaks the answer. On phones whose Google
speech service does carry Filipino, the same setting listens in it directly.

## Permission matrix

Nothing is requested at launch. Each capability is requested the first time a
tool needs it, after a plain-English rationale.

| Capability | Tools | Android permission | Requested |
|---|---|---|---|
| Microphone | voice input, wake word, VAD | `RECORD_AUDIO` (+ `FOREGROUND_SERVICE_MICROPHONE`) | first MIC tap |
| Notifications | ongoing service notice | `POST_NOTIFICATIONS` (API 33+) | after mic grant |
| Calls | `call_contact`, `end_call`, `answer_call` | `CALL_PHONE`, `ANSWER_PHONE_CALLS` | first call tool |
| Call log | `read_call_log` | `READ_CALL_LOG` | first use |
| SMS | `send_sms`, `read_sms` | `SEND_SMS`, `READ_SMS` | first use |
| Contacts | `search_contacts`, `get_contact_number`, number resolution | `READ_CONTACTS` | first use |
| Calendar | `create_event`, `read_today_events` | `WRITE_CALENDAR`, `READ_CALENDAR` | first use |
| Apps | `open_app`, `close_app`, `list_installed_apps` | `QUERY_ALL_PACKAGES` | install-time normal |
| Brightness | `set_brightness` | `WRITE_SETTINGS` (special) | Settings deep link |
| Torch | `toggle_flashlight` | `CAMERA` | first use |
| DND | `set_dnd` | `ACCESS_NOTIFICATION_POLICY` (special) | Settings deep link |
| Notifications read | `read_notifications`, `dismiss_notification` | Notification access (special) | Settings deep link |
| Screen control | `read_screen_text`, `tap_element`, `scroll` (up to 30 repeats), `auto_tap` (up to 100 repeats), `close_app` | Accessibility service (special, opt-in) | Settings deep link |
| Network | reasoning, web, navigation, smart home | `INTERNET` | install-time normal |

Alarms, timers, maps, media keys, battery and network status need no runtime
permission.

### Google Play policy notes (important)

* `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG` are **restricted permissions**. A Play
  listing requires the core-functionality declaration and Google's approval;
  most assistant apps are rejected. Sideload for personal use.
* `QUERY_ALL_PACKAGES` needs a declaration; prefer the `<queries>` element if you
  narrow the app list.
* Accessibility is for users who explicitly enable it. This app reads screen text
  only when a tool is called, skips password fields, caps output at 4,000 chars,
  and never sends screen text anywhere except as a tool result the model asked
  for. Google Play restricts accessibility use for non-accessibility purposes.
* `WRITE_SETTINGS`, Notification access and DND access are special access: the
  app can only deep-link to the system screen.

## Voice pipeline (as shipped)

```
"Seven" (wake listener, foreground service, fresh recogniser per session)
        → no command after the name → open a listening session
MIC tap → foreground service (microphone type)
        → Android SpeechRecognizer (English or fil-PH, EXTRA_PREFER_OFFLINE=false)
             partials → transcript ghost line, real onRmsChanged → orb waveform
             final    → Rust submit_text
        → model SSE → token delta → sentence chunk (Rust)
        → Speak event → Android TTS queue (QUEUE_ADD), ducked focus
             UtteranceProgressListener word ranges → viseme + mouth envelope
        → tts_done → session idle → microphone reopens for the next turn
```

A transcript that is only the name ("Seven", "Hey Seven", "hoy Seven") does not
reach the model; the session stays open and waits for the instruction.

Barge-in: with the Rust VAD path (on-device mode), detected speech during
Speaking cancels the `CancellationToken`, emits `BargeIn`, Kotlin stops TTS on
the same frame and the session returns to Listening. With the system recogniser
the wake listener keeps the microphone during Thinking and Speaking and matches
short stop phrases ("stop", "stop it", "cancel", "quiet", "enough", "shut up"),
which cancel the turn; INTERRUPT (or long-press MIC) does the same from the UI,
and STOP closes the conversation.

Rust owns capture framing, VAD, wake scheduling, ASR (feature), streaming,
tool-call assembly, sentence chunking, prosody, session state and the encrypted
log. Kotlin owns permissions, services and device APIs — the Rust core never
waits on a permission dialog.

## On-device engines

### Whisper (optional, feature-gated)

```bash
cd core
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../android/app/src/main/jniLibs build --release --features whisper
```

Then place `ggml-base.en.bin` in the app's `files/models/` and select
**VOICE INPUT → On-device (Rust)**. Without the feature the engine reports a
`notConfigured` error that names the missing build step; it never pretends to
transcribe. The partial-hypothesis strategy is documented in `core/src/asr.rs`.

### Wake word

The shipped wake word runs inside the foreground service on the platform
recogniser: it holds the microphone while SEVEN is idle, gives it up only for a
listening session, and takes it back 600 ms after the session ends. It also
stays armed while SEVEN is thinking or speaking, which is what lets a spoken
"stop" interrupt a reply. The openWakeWord pipeline
(`core/src/wake.rs`, `models/download_models.sh`) remains the route to a fully
offline detector; ONNX Runtime still has to be cross-compiled with the NDK.

### Local Qwen, Piper / sherpa-onnx

`LlmBackend` and `TtsEngine` are the seams. `QwenLocal` (`llama-cpp-2 0.1.161`)
is wired behind the opt-in `local-llm` feature: build with `-PlocalLlm`, import
a GGUF in Settings, and the engine selects it instead of the remote endpoint.
Its native llama.cpp dependency has been cross-compiled and packaged with
`libc++_shared.so` for arm64-v8a, armeabi-v7a and x86_64. A RAM guard refuses a
model that would exceed 2 GB or Android's available memory. No model has been
loaded and no inference has been benchmarked on a device. See
[docs/OFFLINE_BUILD.md](docs/OFFLINE_BUILD.md) for the exact CMake/Ninja/libclang
recipe, Gradle proof task and limits. Piper (`sherpa-onnx`) remains not compiled.

## Testing

```bash
core:   cargo test        # 94 unit/integration tests
        cargo clippy --all-targets -- -D warnings
        cargo bench --bench latency
        # optional local-llm proof: ./gradlew :app:cargoNdkBuildLocalLlm
        # local APK: ./gradlew :app:assembleDebug -PlocalLlm
android: ./android/gradlew -p android testDebugUnitTest   # JVM tests: tools, identity, self-model prompt, memory JSON, wake-word rules
        java -jar ktlint.jar "app/src/main/java/**/*.kt"
```

Measured host numbers and the device measurement procedure are in
[LATENCY.md](LATENCY.md). Architecture: [ARCHITECTURE.md](ARCHITECTURE.md).
Tradeoffs and rejected alternatives: [DESIGN.md](DESIGN.md).
Demo script: [DEMO.md](DEMO.md).

## Security and privacy

* API key: Keystore-encrypted; never logged. It is optional: the engine starts
  keyless and reports reasoning as not configured.
* Local memory: AES-256-GCM journal with per-line AAD, app-private storage,
  user-approved permanent records, and no automatic secret capture. See
  [docs/MEMORY_SECURITY.md](docs/MEMORY_SECURITY.md).
* Transcript and tool traces: AES-256-GCM records, one per line, key wrapped by
  the Keystore; tampering fails authentication (tested).
* Tool traces are shown in Settings and capped at 80 in memory.
* Accessibility text stays in the tool result; no background scraping.
* `android:allowBackup="false"` and data-extraction rules exclude app data.
* No analytics, no telemetry, no third-party SDKs.

## Limitations worth stating plainly

* Piper and the Rust Whisper ASR are not in this build; local Qwen requires an
  opt-in `-PlocalLlm` build and an imported GGUF, with no device benchmark yet.
* The system recogniser may use the network on some devices; the offline Whisper
  build is the remedy. Tagalog needs the Google speech service and a Filipino
  TTS voice to be installed for speech output.
* The wake word runs on the platform recogniser, not openWakeWord; it needs the
  foreground service to stay alive. Holding the microphone all day also costs
  battery — that is the trade for always listening.
* MIUI can still kill the service on its own schedule. On a Redmi, enable
  **Settings → Apps → Manage apps → SEVEN → Autostart**, set **Battery saver →
  No restrictions**, and lock SEVEN in the recents screen. The app schedules a
  wake-alarm restart when its task is swiped away, retries up to four times if
  Android or MIUI refuses the background start, and logs every attempt under the
  `JarvisWakeBoot` tag, so `adb logcat -s JarvisWakeBoot JarvisService` says
  exactly what happened.
* Controlling other apps — scrolling a feed, tapping on screen, reading the
  screen — runs through Android's accessibility service. **No app can grant that
  to itself and SEVEN does not try to**: the system only accepts the switch from
  the person holding the phone. Settings → CAPABILITIES has one
  `ENABLE PHONE CONTROL` button (and a GRANT button per capability); with USB
  debugging, `adb shell settings put secure enabled_accessibility_services
  ai.jarvis/ai.jarvis.service.JarvisAccessibilityService` plus `accessibility_enabled 1`
  does the same thing. Everything else — calls, SMS, apps, timers, torch — uses
  ordinary runtime permissions that the app does request itself.
* `close_app` returns to the home screen: Android has no API to force-stop
  another app.
* Automation is bounded on purpose. "Scroll the Facebook feed" opens the app and
  scrolls a fixed number of screens; "play this game for me" opens it and taps
  one named control (Play, Continue, Claim) repeatedly. SEVEN cannot play a game
  that needs decisions — it never reads the game state and picks moves, and it
  stops as soon as the button it was tapping disappears.
* Verified on one device (Redmi 10, Android 13). Other OEMs' recognition and TTS
  services may behave differently.
* Recognition accuracy is the platform speech service's, not the app's: with a
  strong accent the transcript can still be wrong. The app compensates by asking
  for clarification rather than acting on a garbled command; it cannot improve
  the acoustic model. Typing the command always works.
