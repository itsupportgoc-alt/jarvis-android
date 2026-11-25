# SEVEN for Windows

The desktop build of SEVEN: same arc-reactor surface, same wake word, same
"say stop" behaviour, running as a normal Windows application.

```bash
cd desktop
npm install
npm start
```

If `npm` blocked Electron's post-install download (npm 11 ask-scripts policy),
fetch the runtime once:

```bash
node node_modules/electron/install.js
npm start
```

## What it does

* **Wake word** — Windows' own speech recogniser (SAPI) listens continuously, so
  saying "Seven, …" or "Hey Seven, …" works even when the window is not focused.
  A bare "Seven" opens a listening turn instead of answering the name.
* **Say "stop"** — while SEVEN is thinking or reading a reply, *stop*, *stop it*,
  *cancel*, *quiet*, *enough* or *shut up* cancels the turn at once. The
  microphone stays open for the next command.
* **Streaming replies** — tokens appear in the transcript as they arrive, each
  finished sentence is spoken immediately, and the panel is pinned to the newest
  line.
* **Any OpenAI-compatible endpoint** — the same presets as the phone (Freebuff by
  default, plus NVIDIA NIM, Groq, OpenAI, OpenRouter and any other compatible
  host). Settings stores the base URL, key and two model IDs; the fallback model
  is tried automatically when the first one fails.
* **Windows voices** — the reply is read by SAPI/Windows TTS; pick a voice and a
  speaking rate in Settings. MUTE silences it without losing the transcript.

Everything local — recognition and speech synthesis never leave the machine. Only
the chat request goes to the endpoint you configure.

## Keys

None are included. Open **SETTINGS** in the app, paste the base URL and key of
your provider, then **TEST CONNECTION** (it calls `GET {base_url}/models` and
reports the real answer, e.g. `HTTP 401: invalid api key`). Settings are written
to `%APPDATA%/SEVEN/settings.json`.

## Package a folder you can double-click

```bash
npm install --save-dev electron-builder
npm run dist        # electron-builder --win --dir  → dist/win-unpacked/SEVEN.exe
npm run portable    # single portable .exe
```

## Limits worth stating

* Recognition is Windows' dictation engine, so accuracy depends on the installed
  speech language pack and the microphone. It is good for commands and short
  sentences, not transcription.
* The desktop build talks and listens only: the Android device tools (calls, SMS,
  apps, torch, screen reading) stay on the phone. Windows has no equivalent of
  those APIs under these permissions.
* Wake-word listening holds the microphone while the app is running. It does not
  start with Windows by itself — put a shortcut in `shell:startup` if you want
  that.
* SAPI has no partial results, so the transcript shows the recognised sentence
  once it is complete; the "listening" orb is an animation, not a live level
  meter.
