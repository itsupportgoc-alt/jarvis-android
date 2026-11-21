# Design decisions and tradeoffs

## 1. Rust for the realtime path, Kotlin for the platform

**Decision:** audio framing, VAD, wake scheduling, ASR glue, LLM streaming, tool
assembly, sentence splitting, prosody and session state live in Rust. Kotlin owns
the Android surface.

**Why:** the realtime loop needs predictable memory and no GC pauses; the
platform APIs need a `Context` and a permission model. The split also makes the
engine testable on a laptop, which is how the 77 Rust tests run without a device.

**Cost:** a JNI boundary, a duplicated event model, and two build systems. A
pure-Kotlin implementation would have been faster to write and easier to debug,
but the 20 ms audio path and cancellation semantics are exactly where the JVM is
weakest.

## 2. Hand-written JNI instead of UniFFI

**Decision:** six `extern "system"` functions, a JSON event string, and an opaque
`jlong` handle.

**Why:** UniFFI would generate a cleaner API but adds a code-generation step to
the Android build and a second schema to keep in sync. The surface here is tiny
(create/start/stop/cancel/submit/push/destroy + one callback), and the JSON
events are already the UI contract.

**Cost:** manual signature discipline. `JarvisNative.kt` and `ffi.rs` must agree;
a mismatch fails at runtime, not compile time. Mitigation: all entry points are
null-safe and panic-contained, and null-handle/panic paths are unit-tested.

## 3. Boxed `DeltaStream` rather than `impl Stream`

**Decision:** `LlmBackend::stream` returns `BoxStream<'static, Result<LlmDelta>>`.

**Why:** it keeps `dyn LlmBackend` object-safe, so remote and (future) local
backends are interchangeable at runtime, selected by config.

**Cost:** one allocation per turn. Irrelevant beside an HTTP request.

## 4. Sentence-level TTS, not token-level

**Decision:** chunk streamed tokens into sentences, speak them as they complete,
keep the remaining partial text buffered.

**Why:** per-token TTS sounds robotic and floods the engine; per-reply TTS adds
seconds of silence. Sentence granularity gives natural prosody with immediate
first audio. A boundary is emitted only when the following whitespace has already
arrived, so a token split like `"Dr"` + `". Smith"` cannot be misread as a
sentence end. End of stream is an explicit `flush()`.

**Cost:** a small lookahead and stateful abbreviation handling (`Dr.`, `e.g.`,
decimals). Both are tested; the tradeoff is a few milliseconds of delay for
correctness.

## 5. Energy VAD now, Silero behind the same trait

**Decision:** ship an adaptive RMS/zero-crossing VAD with hangover; keep `Vad`
as a trait and document Silero as the upgrade.

**Why:** it is deterministic, allocation-free, testable without models, and good
enough for push-to-talk and barge-in. Shipping a half-integrated ONNX model that
could not be cross-compiled here would have been worse than an honest seam.

**Cost:** more false triggers in sustained noise than a neural VAD. The threshold,
hangover and minimum utterance length are configurable in `VadConfig`.

## 6. Platform speech in, platform TTS out — with seams

**Decision:** the shipped voice path uses Android `SpeechRecognizer` with
`EXTRA_PREFER_OFFLINE`, and Android `TextToSpeech` for output. Whisper is
feature-gated; Piper/sherpa-onnx is an unimplemented `TtsEngine` seam.

**Why:** it produces a genuinely usable app today, on any device, with no 100 MB
model. The Rust capture/VAD path is still there (`VoiceInputMode.ON_DEVICE`) and
shares the same session machine, so the offline build is an incremental step, not
a rewrite.

**Cost:** the system recogniser may use the network on some OEMs, which conflicts
with "offline-first". This is stated in the README; the Whisper feature is the
remedy. Also, system TTS word timings vary by engine, so the face falls back to a
synthesised envelope when `onRangeStart` is not delivered.

## 7. Streaming everywhere, buffering nowhere

**Decision:** `assistantDelta` is emitted per token; TTS is enqueued per sentence;
the transcript updates in place; the LLM request is cancelled by a token raced
against every read.

**Why:** time-to-first-audio dominates perceived speed. It also makes
INTERRUPT honest: it aborts the HTTP body and clears the TTS queue, rather than
muting a reply that keeps generating.

**Cost:** partial output must be handled everywhere (markdown fences, mood tags,
tool calls). Markdown is stripped at character level with a backtick state
machine; mood tags use a stateful filter so a tag split across tokens cannot leak
into speech. Both have tests.

## 8. Tool confirmation as a first-class UI state

**Decision:** risky tools pause in a confirmation sheet; the tool result is only
submitted after the user decides. "Trusted mode" is a per-tool allowlist, not a
global switch.

**Why:** the brief demands an explicit gate on irreversible actions, and a global
"trust everything" switch is how assistants become dangerous. Per-tool trust lets
someone auto-approve torch and volume while still confirming calls and SMS.

**Cost:** one extra round trip in the conversation and more UI state. The engine
keeps the pending tool call id so ordering cannot drift.

## 9. 5-second undo for reversible actions

**Decision:** tools that can be reversed return an `UndoAction` with a closure;
the UI shows UNDO for five seconds. Irreversible actions (calls, SMS) do not get
an undo they cannot honour.

**Why:** a confirmation dialog is a guess about intent; an undo is a correction
after the fact. Five seconds covers the "oh no" window without leaving stale
state.

**Cost:** each reversible tool must implement its inverse, and the closure must
capture the exact previous state (volume, brightness, DND, timer).

## 10. Accessibility by user action, not background scraping

**Decision:** `read_screen_text` and `tap_element` are explicit tools with
restricted status, password fields are skipped, and output is capped.

**Why:** accessibility APIs are powerful and Play-restricted. Reading the screen
only when the user's request requires it, and saying so in the tool trace, keeps
the behaviour auditable. The service is opt-in via system settings.

**Cost:** some requests ("what's on my screen?") need two steps: ask, then
grant. That is the correct tradeoff for a permission this sensitive.

## 11. Keystore-wrapped AES-GCM for the transcript

**Decision:** the data key is random, stored encrypted by a non-exportable
Keystore key, and records are AES-256-GCM with index-bound associated data.
Without a key, Rust keeps the transcript in memory only.

**Why:** transcripts contain everything said in the room. Plain files are
unacceptable; encrypting each record with the Keystore directly would be slow and
would hit key-usage limits. The envelope gives fast authenticated encryption with
hardware-backed key protection.

**Cost:** losing the Keystore key (factory reset, lock change on some devices)
makes old records unreadable. That is intentional and documented in Settings.

## 12. One Canvas, no per-frame allocation

**Decision:** orb and face are custom `Canvas` composables; colours are
`remember`ed; geometry is computed from a small number of `animateFloat`s; the
draw scope allocates nothing per frame. Animations collapse to a static ring when
`ANIMATOR_DURATION_SCALE` is zero.

**Why:** the orb is on screen during audio work; GC pauses there would show up as
waveform stutter. A static ring also respects users who disable animations
(vestibular safety).

**Cost:** more code than Lottie or a video, and no designer-friendly authoring.
The visual language is deliberately simple: arcs, particles, a vector face.

## Rejected alternatives

| Option | Why not |
|---|---|
| Oboe/AAudio capture in C++ | more native code and a second audio stack; `AudioRecord` + lock-free push is sufficient and easier to review |
| UniFFI | codegen step and schema duplication for a six-function surface |
| Full-reply TTS | seconds of silence before the first word |
| Token-level TTS | robotic, engine flooding |
| Global "trust all tools" | unsafe default; per-tool allowlist is the smallest safe scope |
| Silero/ONNX wake word in this build | could not cross-compile ONNX Runtime here; shipping an unbuilt path would be dishonest |
| Room for transcripts | overkill for append-only encrypted records; SQLCipher adds a native dependency |
| WebView UI | no 60 fps canvas control, no Keystore/Compose integration |
