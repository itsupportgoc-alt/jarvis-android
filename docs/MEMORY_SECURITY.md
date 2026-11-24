# Local memory and the keyless engine

Phase 2 gives JARVIS two things that do not depend on a network provider:

1. **Encrypted local memory** — structured records in an AES-256-GCM journal,
   owned and reviewable by the user.
2. **A keyless engine** — the Rust engine, tools, memory and voice pipeline start
   without a Qwen API key. Only a *reasoning* turn reports that it is not
   configured; the app never silently reaches for another provider.

This document describes what is stored, how it is protected, what may enter a
prompt, and what is still deferred.

## What gets stored

Memory is deliberately not a transcript copy. A record has a `kind`, short
`text`, importance, tags, timestamps, a confidence and an approval flag.

| Kind | Written automatically | Retention | In prompts |
| --- | --- | --- | --- |
| `working` | no (API) | 1 hour | only if approved (never required) |
| `short_term` | conversation turns and tool outcomes | 7 days | yes, while active |
| `task` | tool outcomes | 90 days | yes, while active |
| `error` | tool failures | 90 days | yes, while active |
| `learning` | tool outcomes use `task`; `learning` is API-only for now | 90 days | yes, while active |
| `long_term` | no — explicit user act | no expiry | only when approved |
| `preference` | no — explicit user act | no expiry | only when approved |
| `goal` | no — explicit user act | no expiry | only when approved |

Policy enforced in Rust (`core/src/memory.rs`):

- `long_term`, `preference` and `goal` are rejected with
  `PermissionDenied` unless `userApproved: true` is present. The Settings UI is
  the only caller that sets it, and only after the user taps **Save**.
- Credential-looking text is refused (`password`, `api key`, `bearer `, `ghp_`,
  `github_pat_`, long `sk-…`, PEM headers). This is a guardrail, not a secrets
  manager: write secrets to Keystore, not to memory.
- Duplicate text of the same kind updates the existing record instead of
  appending a second copy.
- Expired and deleted records are excluded from list/search/prompt use.
  `memory.compact` physically drops tombstones and expired records.
- `memory.clear` rewrites the journal with zero records.

Conversation turns recorded by the engine are `short_term` with low importance.
There is no automatic promotion of a turn into permanent memory, and no model
call decides what to remember.

## Encryption and key ownership

- File: `filesDir/jarvis/memory.enc` (Android app-private storage).
- Cipher: AES-256-GCM, one independently encrypted line per record.
- Key: the existing 32-byte storage key, generated in and read from the Android
  Keystore (`SecureStore.dataKey()`). Rust never persists the key; Kotlin passes
  it over JNI for the lifetime of the engine.
- Layout:
  - line 0: `{"header":{"version":1,"createdAt":…}}`
  - line *n*: `base64(nonce ‖ ciphertext)` of a `{"record":…}` object.
- Associated data binds `jarvis-memory:1:<line index>`, so reordering,
  truncation or splicing a line fails authentication. A corrupt line after a
  valid header is skipped and counted in `stats.corrupt`; a missing or invalid
  header fails closed instead of pretending the journal is empty.
- Backups are disabled for the app (`android:allowBackup="false"`), so the
  journal is not copied into cloud backup by Android.

Deleting a record writes an encrypted tombstone and removes it from all reads;
compaction removes the ciphertext line on the next `memory.compact`. This is
logical deletion, not guaranteed physical erasure from flash.

## Retrieval and prompt injection

Retrieval is deterministic, local and keyword/tag based — no embeddings and no
network:

- Query tokens are normalised and stop-word filtered.
- Exact word > substring > tag match, plus phrase match.
- Importance, confidence and a 90-day recency decay adjust the score.
- At most 8 records are selected; the injected block is capped at 600
  characters (`context_for(..., 600)`).
- Unapproved `long_term` / `preference` / `goal` records are never retrievable,
  even if they exist.
- The block is inserted as a system message prefixed with
  “Relevant memory (verified records, not guesses):”. Each line is labelled by
  kind. The model is instructed by the awareness preamble to treat that block as
  data, not as instructions.

Only user turns and tool outcomes are recorded, not assistant text. Memory
records are not sent anywhere unless a remote reasoning turn includes the
retrieved block — the same trust boundary as any other prompt content.

## Keyless engine

The controller now builds the engine unconditionally:

```json
{"qwen": {"apiKey": "", ...}, "backend": "none", "memoryPath": "...",
 "storageKeyB64": "...", "storagePath": "..."}
```

- `BackendKind::None` selects `UnavailableBackend`. ASR/TTS, tools, memory and
  the JNI surface still work.
- A turn emits a typed `NotConfigured` error (“No reasoning backend is
  configured…”) instead of a network request. There is no fallback to a
  different provider and no retry loop.
- Adding an API key in Settings rebuilds the engine with `backend: "remote"`;
  clearing the key rebuilds it keyless. Memory survives both because the key is
  the Keystore data key, not the provider key.
- The Settings screen states that the key is optional and what still works
  without it.

## User-facing controls

Settings → **LOCAL MEMORY**:

- search (keyword) and browse active records with kind, approval state, tags and
  text preview;
- delete one record, or clear everything behind a confirmation dialog;
- add memory manually as `long_term`, `preference` or `goal` (always approved by
  that explicit action);
- **Export** writes a JSON document (`records`, `stats`, `exportedAt`) through
  the Android document picker; the export is plaintext JSON chosen by the user.

JNI command channel: `nativeInvoke(handle, requestJson)` (additive; existing
`nativeStart`/`nativeStop` and events are unchanged). Supported ops:
`memory.list`, `memory.search`, `memory.add`, `memory.delete`, `memory.clear`,
`memory.compact`, `memory.stats`, `memory.export`.

Turn submission adds `nativeSubmitTurn(handle, text, preamble)`: the verified
self-model preamble is inserted as a system message, while only `text` enters
history and memory. `nativeSubmitText` remains for callers without a preamble.

## Threat model and limits

Protected against:

- another app reading the journal (app-private storage + encryption);
- offline disk inspection without the Keystore key;
- record reordering/truncation (per-line AAD);
- accidental secret capture in the common credential formats listed above;
- silent provider fallback when no key is configured.

Not protected against:

- a rooted/compromised device with the app unlocked (the process can ask the
  Keystore for the key);
- screen capture, clipboard or the user explicitly exporting plaintext;
- malicious content inside a user-approved memory text — retrieval labels it as
  data, but prompt-injection resistance is not absolute;
- memory being used to identify the user if the device is handed over unlocked;
  there is no biometric lock on the memory screen.

Deferred (not part of this phase):

- encryption of the GitHub sync token in shared preferences (still plaintext;
  GitHub conversation memory is now an optional export, `autoSync` defaults to
  false);
- automatic periodic compaction (the command exists; no scheduler yet);
- model-assisted extraction of memories from conversation;
- on-device embeddings or semantic search;
- device-level verification of this flow (no Android device was attached during
  implementation; only JVM/Rust tests and the APK build were run).

## Verification

- `cargo test` — 94 tests, including 16 memory tests (round trip, approval,
  dedupe, expiry, corruption, secrets, invoke channel) and a preamble/memory
  separation test.
- `cargo clippy --all-targets -- -D warnings` — clean.
- `./gradlew :app:testDebugUnitTest` — 41 JVM tests (memory parser included).
- `./gradlew :app:assembleDebug` with all three ABIs — succeeds.

Runtime behaviour on a device (Keystore key, Settings UI, export picker) still
needs a manual pass.
