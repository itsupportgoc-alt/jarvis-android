# Demo checklist

A 5–8 minute walkthrough. Two columns: what to do, and what proves it worked.

## Before the room

- [ ] Build with the Rust core on a machine with NDK: `./setup.sh`, then
      `./android/gradlew -p android assembleDebug` (not `-PskipRust`).
- [ ] `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
- [ ] Qwen key ready; device online for the first run.
- [ ] Turn animations **on** (orb/face), volume at ~70%, DND off.
- [ ] Optional: `adb logcat -c` for a clean log.

## The demo

| # | Action | Expected |
|---|---|---|
| 1 | Launch. | Black screen, orb idle at 8 s/revolution, one line of status text. No permission dialogs. |
| 2 | Tap **MIC**. | System mic prompt. Grant. Orb switches to listening, waveform moves with your voice — cover the mic and it flattens. |
| 3 | Say "What time is it?" | Cyan user line, gold streaming reply, orb goes thinking then speaking, TTS answers with the correct time. Face mouth moves with the words. |
| 4 | While JARVIS is speaking, tap **INTERRUPT**. | Audio stops immediately, orb returns to idle, transcript keeps what was said so far. |
| 5 | Type "Open the calculator" and send. | Tool trace shows `open_app`, calculator opens, JARVIS says it did it. |
| 6 | Say "Call Maria." (contact exists) | Confirmation sheet: "JARVIS wants to call Maria." Nothing happens until **Confirm**. Cancel → no call, JARVIS says it was cancelled. |
| 7 | Say "Set volume to 20 percent." | Confirmation (or trusted), volume changes, UNDO appears for 5 s. Tap UNDO → volume restored. |
| 8 | Say "Turn on the torch." | Torch on; capability light green in Settings; trace line with latency. |
| 9 | SETTINGS → DIAGNOSTICS. | Per-tool trace with arguments, ok/fail, ms; latency samples; no API key visible. |
| 10 | Revoke microphone in system settings, return, tap MIC. | Typed error, red orb flicker, a single rationale and a button to re-grant. No crash. |

## Offline / degraded-path demo (no key)

1. Settings → erase the key, apply. Type a message.
2. The reply is a typed `notConfigured` error naming the missing key; the app
   stays usable, tools can still be invoked by text.
3. Turn on airplane mode with a key set: the first token fails with a network
   error, the orb flashes red, and the next turn works when the network returns.

## What to point out while it runs

* The waveform is real RMS from the microphone, not an animation.
* The reply is spoken sentence by sentence while Qwen is still generating.
* The face's mouth uses the TTS engine's actual word timing where available.
* Every tool call is visible, gated, and undoable where it can be reversed.
* Nothing leaves the device except the Qwen request itself; the transcript file
  is encrypted.

## Known gaps to state, not hide

* Wake word ("hey jarvis") is not in this build: use MIC.
* On-device Whisper needs the `whisper` feature build + model; the default
  recogniser is the system one.
* Local Qwen and Piper are not implemented; remote Qwen + system TTS are.
* Device latency figures are unmeasured; the instrumentation is in Settings and
  logcat.

## If something fails

| Symptom | Check |
|---|---|
| "Engine unavailable" at launch | native library missing: build without `-PskipRust`, verify `lib/arm64-v8a/libjarvis_core.so` in the APK (`unzip -l`) |
| No transcript after speech | mic permission, foreground service notification present, recogniser available |
| 401/403 from Qwen | key, base URL, model name in Settings → diagnostics |
| No audio | media volume, TTS engine installed (Settings → Accessibility → TTS), audio focus not held by another app |
| Tool says permission missing | Settings → capability lights; grant the exact permission |
| Call tool does nothing | `CALL_PHONE` granted, default dialer present, contact number unambiguous |
