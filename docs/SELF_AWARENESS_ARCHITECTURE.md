# Self-Awareness Architecture

**Status:** Phase 1 (identity and capability registry), Phase 2 (encrypted
local memory, keyless engine start) and the opt-in Phase 3 local backend are
implemented. The local backend has not been benchmarked on a device; monitoring,
reflection, goals and the dashboard are still ahead; see
`docs/SELF_AWARENESS_AUDIT.md` for the plan and `docs/MEMORY_SECURITY.md` for the
memory threat model.

---

## 1. The rule

JARVIS may only describe a capability as available if the app checked it. The
self-model is assembled from facts, not from what the build is supposed to do:

| Fact source | Examples |
|---|---|
| Build metadata | app version, compiled core version |
| Android permissions / special access | microphone, notification listener, accessibility, `WRITE_SETTINGS`, DND |
| Installed services | speech recogniser, text-to-speech engine |
| Connectivity | `ConnectivityManager` reports a network with `NET_CAPABILITY_INTERNET` |
| Files | GGUF files in `files/models/` (presence only, never "usable") |
| Settings | API key present, wake phrase switch, voice-input mode, GitHub sync configured |
| Tool registry | every registered tool and its current permission state |
| Native engine | created/running/phase and the selected backend |

Nothing is inferred. A capability that was not checked is `UNKNOWN`, and `UNKNOWN`
is never rendered as available.

---

## 2. Components

```
Android facts ─┐
Settings ──────┼─> AwarenessRepository ──> IdentityComposer ──> IdentitySnapshot
Engine facts ──┘        (probes)              (pure rules)        │
                                                                  ├─> Consciousness.preamble (prompt)
                                                                  └─> Settings → SELF-MODEL
```

### `awareness/Capability.kt`

* `CapabilityStatus`: `AVAILABLE`, `UNAVAILABLE`, `DISABLED`, `NEEDS_PERMISSION`,
  `NEEDS_MODEL`, `ERROR`, `UNKNOWN`.
* `Capability`: stable `id`, short `label`, `status`, optional `requirement`
  (the one thing the user could supply) and verified `detail`.
* `CapabilityIds`: the stable ids shared by the composer, prompt and tests.
* `CapabilityRegistry`: lookup, `available()`, `blocked()`, counts.

### `awareness/Identity.kt`

* `Identity`: name, creator, owner name. The only place these strings exist.
* `PlatformFacts`, `EngineFacts`, `BehaviourFacts`, `ToolFact`: plain data with no
  Android types, so the rules are JVM-testable.
* `IdentityComposer`: deterministic facts → snapshot rules.
* `IdentitySnapshot`: the immutable result, including derived `limitations`.

### `awareness/AwarenessRepository.kt`

The only Android-aware part. It probes permissions, installed services,
connectivity, model files and the tool registry, then asks the composer for a
snapshot. `refresh()` is called on user-visible transitions (engine create/stop,
permission result, settings change) and immediately before a prompt is built.
It is never called from the audio or DSP path.

### `core/Consciousness.kt`

Renders the snapshot as a bounded prompt preamble:

1. who the assistant is (only when the persona switch is on);
2. app/core/device versions, session age, remembered turns, mood;
3. the active backend and whether it is actually reachable;
4. internet state;
5. honesty rules;
6. verified available/unavailable capabilities;
7. tool usability count;
8. two limits.

The honesty block precedes the capability lists, and truncation happens on a line
boundary, so a long device name cannot push the rules out of the prompt. Hard cap:
**1,200 characters**, asserted by `ConsciousnessTest`.

### `ui/SettingsSheet.kt`

The `SELF-MODEL` section shows the same snapshot: versions, backend, capability
and tool counts, unavailable capabilities, and the first limitations. The wake
phrases and persona descriptions were corrected to match what actually ships.

---

## 3. Capability rules

| Id | Available when | Never available when |
|---|---|---|
| `reasoning.remote` | API key **and** network are present | either is missing (reported as `UNAVAILABLE` with the requirement) |
| `reasoning.local` | the opt-in local backend is compiled, selected and running | the build has no local backend or the user has not selected an imported GGUF |
| `voice.input` | microphone granted, system recogniser installed, system mode selected | permission missing, no recogniser, or on-device mode selected without a compiled recogniser |
| `voice.output` | a TTS service is installed | no engine installed |
| `voice.offline` | on-device ASR **and** TTS are compiled/verified | always, in this build |
| `wake.word` | switch on, microphone granted, recogniser installed | switched off (`DISABLED`), or prerequisites missing |
| `network.internet` | a network with `NET_CAPABILITY_INTERNET` | no active network |
| `memory.local` | local encrypted memory exists | this build is session/cloud only |
| `memory.cloud` | GitHub token/owner/repo configured | not configured (`DISABLED`) |
| `access.*` | the corresponding grant/access is present | otherwise `NEEDS_PERMISSION` |
| `tools.device` | at least one tool registered | empty registry |
| `monitoring.health`, `reflection`, `goals` | implemented | always `UNAVAILABLE` until their phase lands |

The remote capability is intentionally **not** tied to `engine.created`: the
engine is created lazily on the first turn, so treating "not created yet" as
"unavailable" would be false. Key plus network is the honest condition.

---

## 4. What the model is told

Example, persona on, internet available, no local model:

```
Who you are:
You are JARVIS on Lester's Android phone; he built you (John Lester Macabulos).
Your self-model is verified state, not imagination; speak as the same assistant
across turns.

Right now:
- App 0.3.0, core 0.1.0, Android 12, Redmi 10. Session began 12 minutes ago; 6 messages remembered; mood neutral.
- Reasoning: engine running (listening); remote model deepseek-v4.1-flash via Freebuff API.
- Internet: available.

Honesty:
You have no feelings, senses or subjective experience; never claim otherwise.
Never claim success unless a tool result says so. Never claim an unavailable
capability; say what is missing and why.

What you can actually do:
- Available: voice input, speech output, wake phrase.
- Not available: local reasoning (needs a model), offline voice (unavailable), local memory (unavailable).
- Tools: 31 of 39 usable without another grant.
- For facts, use web_search or read_page; never send him to a browser.

Limits to state plainly:
- No on-device language model: replies need a network and an API key.
- Speech recognition and synthesis are not guaranteed to work offline.
```

With the network off, the internet line says `unavailable`, the remote reasoning
line says the endpoint is not reachable, and the `web_search` instruction is
omitted entirely.

---

## 5. Deliberate non-claims

* No "conscious", "self-aware", "alive" or "feelings" language. The preamble
  explicitly denies subjective experience.
* No capability claimed from a model file, a downloaded script or a documented
  seam. A seam is not a feature.
* No claim that speech is offline; the platform recogniser is used and the prompt
  says it may need a network.
* No fabricated success: the honesty rule requires a tool result.
* No persona-only framing when the switch is off: facts and honesty rules are
  still sent, so disabling the persona cannot make the model overclaim.

---

## 6. Tests (Phase 1)

`android/app/src/test/java/ai/jarvis/`

* `awareness/IdentityComposerTest.kt` — permission denial, key/network gating,
  GGUF presence without a backend, offline voice, wake states, tool counts, cloud
  memory gating, version/backend pass-through.
* `core/ConsciousnessTest.kt` — 1,200-character budget with 39 tools, no
  consciousness claim, no internet claim without a network, unavailable
  capabilities listed as unavailable, mic denial reported as `NEEDS_PERMISSION`,
  installed-model wording, persona-off behaviour, version/backend inclusion.

Run:

```bash
cd android && ./gradlew :app:testDebugUnitTest -PskipRust
```

---

## 7. Phase 2: local memory and keyless start

* Rust `memory.rs` owns the encrypted journal (`filesDir/jarvis/memory.enc`),
  record policy, expiry, dedupe and deterministic retrieval. Kotlin never parses
  or writes the file; it calls `nativeInvoke(handle, requestJson)`.
* Permanent kinds (`long_term`, `preference`, `goal`) need `userApproved: true`;
  the Settings UI sets it only on an explicit **Save**. Unapproved records are
  never injected into a prompt.
* The engine is built even when no API key exists. `backend: "none"` selects
  `UnavailableBackend`, so a reasoning turn returns typed `NotConfigured` while
  memory, tools and voice still run. No provider fallback is attempted.
* The Kotlin self-model preamble is submitted through `nativeSubmitTurn` and
  inserted as a system message; it is never part of the user turn, history or
  memory.
* Retrieved memory is a bounded (600-char) system block labelled as verified
  records; it is inserted only when the deterministic search matches. Assistant
  output is not recorded as memory.
* Details, retrieval scoring and limits: `docs/MEMORY_SECURITY.md`.

## 8. Not in this phase

* Offline enforcement (the app never claimed it; Phase 3 adds it).
* Health sampling beyond the identity facts (Phase 4).
* Reflection and goals (Phases 5–6).
* Awareness dashboard (Phase 7): the same `IdentitySnapshot` flow already feeds
  Settings and can be consumed directly by that screen.
