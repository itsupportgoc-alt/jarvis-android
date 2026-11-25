const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

const settingsPath = () => path.join(app.getPath('userData'), 'settings.json');

const DEFAULT_SETTINGS = {
  baseUrl: 'https://freebuff.com/api/v1/paid',
  apiKey: '',
  model: 'deepseek-v4.1-flash',
  fallbackModel: 'minimax-m3',
  voice: '',
  rate: 1,
  wake: true,
};

let win = null;
let speech = null;
let inFlight = null;

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function loadSettings() {
  try {
    return { ...DEFAULT_SETTINGS, ...JSON.parse(fs.readFileSync(settingsPath(), 'utf8')) };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

function saveSettings(settings) {
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true });
  fs.writeFileSync(settingsPath(), JSON.stringify(settings, null, 2));
  return settings;
}

function createWindow() {
  win = new BrowserWindow({
    width: 430,
    height: 830,
    minWidth: 360,
    minHeight: 620,
    backgroundColor: '#04070c',
    title: 'SEVEN',
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());
  win.on('closed', () => {
    win = null;
  });
}

/** Continuous dictation through Windows' own SAPI recogniser. */
function startSpeech() {
  if (speech) return { running: true };
  const script = path.join(__dirname, 'speech', 'listen.ps1');
  speech = spawn('powershell.exe', [
    '-NoProfile',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    script,
  ]);
  let buffer = '';
  speech.stdout.on('data', (chunk) => {
    buffer += chunk.toString();
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      handleSpeechLine(line);
    }
  });
  speech.stderr.on('data', (chunk) => {
    const text = chunk.toString().trim();
    if (text) console.log('[speech]', text);
  });
  speech.on('error', (error) => {
    send('speech:event', { type: 'error', message: error.message });
  });
  speech.on('exit', (code) => {
    console.log('[speech] exited', code);
    speech = null;
    send('speech:event', { type: 'stopped' });
  });
  return { running: true };
}

function handleSpeechLine(line) {
  if (!line) return;
  if (line === 'READY') return send('speech:event', { type: 'ready' });
  if (line.startsWith('FINAL ')) return send('speech:event', { type: 'final', text: line.slice(6) });
  if (line.startsWith('ERROR ')) return send('speech:event', { type: 'error', message: line.slice(6) });
  console.log('[speech]', line);
}

function stopSpeech() {
  if (!speech) return;
  const child = speech;
  speech = null;
  try {
    child.kill();
  } catch {
    /* already gone */
  }
}

/** One streaming completion; resolves with the full text. */
async function streamCompletion(id, settings, messages, signal) {
  const url = `${settings.baseUrl.replace(/\/+$/, '')}/chat/completions`;
  const response = await fetch(url, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${settings.apiKey}`,
    },
    body: JSON.stringify({ model: settings.model, messages, stream: true }),
  });
  if (!response.ok) {
    const body = (await response.text()).slice(0, 300);
    throw new Error(`HTTP ${response.status}: ${body.replace(/\s+/g, ' ').trim()}`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let full = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') return full;
      try {
        const delta = JSON.parse(data).choices?.[0]?.delta?.content ?? '';
        if (delta) {
          full += delta;
          send('llm:event', { type: 'delta', id, delta });
        }
      } catch {
        /* keep-alive or partial frame */
      }
    }
  }
  return full;
}

ipcMain.handle('settings:load', () => loadSettings());
ipcMain.handle('settings:save', (_event, settings) => saveSettings(settings));

ipcMain.handle('speech:start', () => startSpeech());
ipcMain.handle('speech:stop', () => {
  stopSpeech();
  return { running: false };
});

ipcMain.handle('llm:send', async (_event, { id, messages, settings }) => {
  inFlight?.abort();
  const controller = new AbortController();
  inFlight = controller;
  const models = [settings.model, settings.fallbackModel].filter(Boolean);
  let failure = 'no model configured';
  for (const model of models) {
    try {
      const text = await streamCompletion(
        id,
        { ...settings, model },
        messages,
        controller.signal,
      );
      send('llm:event', { type: 'done', id, text, model });
      return { ok: true, model };
    } catch (error) {
      if (controller.signal.aborted) {
        send('llm:event', { type: 'cancelled', id });
        return { ok: false, cancelled: true };
      }
      failure = error.message || String(error);
      console.log('[llm]', model, 'failed:', failure);
    }
  }
  send('llm:event', { type: 'error', id, message: failure });
  return { ok: false, error: failure };
});

ipcMain.handle('llm:test', async (_event, { baseUrl, apiKey }) => {
  const url = `${String(baseUrl || '').replace(/\/+$/, '')}/models`;
  try {
    const response = await fetch(url, { headers: { Authorization: `Bearer ${apiKey}` } });
    if (!response.ok) {
      const body = (await response.text()).slice(0, 160).replace(/\s+/g, ' ').trim();
      return { ok: false, detail: `HTTP ${response.status}: ${body}` };
    }
    const catalog = await response.json();
    const count = Array.isArray(catalog.data) ? catalog.data.length : 0;
    return { ok: true, detail: `Key accepted. ${count} models available.` };
  } catch (error) {
    return { ok: false, detail: error.message || String(error) };
  }
});

ipcMain.handle('llm:cancel', () => {
  inFlight?.abort();
  inFlight = null;
  return true;
});

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  stopSpeech();
  app.quit();
});

app.on('before-quit', stopSpeech);
