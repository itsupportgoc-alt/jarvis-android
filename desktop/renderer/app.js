/* SEVEN desktop renderer: wake word, stop phrases, streaming replies and TTS. */

const $ = (id) => document.getElementById(id);

const SYSTEM_PROMPT = [
  'You are SEVEN, a voice assistant running on the user\'s Windows PC.',
  'You are speaking out loud, so answer in one to three short sentences unless asked for detail.',
  'Never use markdown, lists, code fences or emoji: the reply is read by a speech engine.',
  'The transcript you receive comes from speech recognition and can be misheard; if a request',
  'is ambiguous, ask one short clarifying question instead of guessing.',
  'Answer in whichever language the user just used.',
].join(' ');

const STOP_WORDS = ['stop', 'cancel', 'quiet', 'enough', 'halt'];
const FILLER = ['play', 'seven', 'it', 'now', 'please'];

const state = {
  settings: null,
  messages: [],
  phase: 'IDLE',
  micHeld: false,
  muted: false,
  awaiting: false,
  wake: true,
  turnId: 0,
  stream: '',
  spoken: 0,
  utterances: 0,
  started: false,
};

/* ---------- phrase matching (same rules as the phone app) ---------- */

function words(text) {
  return text
    .toLowerCase()
    .replace(/[^a-z ]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);
}

function isStopPhrase(text) {
  const list = words(text);
  if (!list.length || list.length > 4) return false;
  if (list.join(' ') === 'shut up') return true;
  const meaningful = list.filter((word) => !FILLER.includes(word));
  return meaningful.length === 1 && STOP_WORDS.includes(meaningful[0]);
}

function wakeCommand(text) {
  const match = text.match(/\b(?:hey\s+)?seven\b/i);
  if (!match) return null;
  return text
    .slice(match.index + match[0].length)
    .replace(/^[\s,.!?]+/, '')
    .trim();
}

/* ---------- status and transcript ---------- */

function setPhase(phase) {
  state.phase = phase;
  $('phase').textContent = phase;
  const dot = $('phaseDot');
  dot.className = 'dot' + (phase === 'LISTENING' ? ' listening' : phase === 'THINKING' ? ' thinking' : phase === 'SPEAKING' ? ' speaking' : phase === 'ERROR' ? ' error' : '');
}

function setMic(held) {
  state.micHeld = held;
  $('micState').textContent = held ? 'MIC HELD' : 'MIC OFF';
  $('micBtn').classList.toggle('active', held);
}

function pushLine(role, text, kind) {
  const row = document.createElement('div');
  row.className = 'row';
  const label = document.createElement('span');
  label.className = 'label';
  label.textContent = role === 'YOU' ? 'YOU' : 'SEVEN';
  const body = document.createElement('span');
  body.className = 'text ' + (kind || (role === 'YOU' ? 'user' : 'assistant'));
  body.textContent = text;
  row.append(label, body);
  $('lines').appendChild(row);
  $('transcript').classList.remove('empty');
  scrollTranscript();
  return body;
}

function scrollTranscript() {
  const panel = $('transcript');
  panel.scrollTop = panel.scrollHeight;
}

function trace(message) {
  const log = $('traceLog');
  const stamp = new Date().toLocaleTimeString();
  log.textContent += `${stamp}  ${message}\n`;
  if (log.textContent.length > 20000) log.textContent = log.textContent.slice(-16000);
  log.scrollTop = log.scrollHeight;
}

/* ---------- speech output ---------- */

function speak(text) {
  if (state.muted || !text.trim()) return;
  const utterance = new SpeechSynthesisUtterance(text);
  const voice = speechSynthesis.getVoices().find((item) => item.name === state.settings.voice);
  if (voice) utterance.voice = voice;
  utterance.rate = Number(state.settings.rate) || 1;
  utterance.onstart = () => setPhase('SPEAKING');
  state.utterances += 1;
  utterance.onend = utterance.onerror = () => {
    state.utterances = Math.max(0, state.utterances - 1);
    if (state.utterances === 0) finishTurn();
  };
  speechSynthesis.speak(utterance);
}

/** Speaks whole sentences as they stream in, like the phone app. */
function flushSentences(final) {
  const pending = state.stream.slice(state.spoken);
  if (!pending.trim()) return;
  let cut = pending.length;
  if (!final) {
    const match = pending.match(/[.!?…]\s|\n/);
    if (!match) return;
    cut = match.index + match[1].length;
  }
  const chunk = pending.slice(0, cut).trim();
  state.spoken += cut;
  if (chunk) speak(chunk);
}

function finishTurn() {
  if (state.utterances > 0) return;
  setPhase(state.awaiting && state.micHeld ? 'LISTENING' : 'IDLE');
  $('streaming').classList.add('hidden');
}

/* ---------- conversation ---------- */

async function sendTurn(text) {
  const id = ++state.turnId;
  state.awaiting = false;
  state.stream = '';
  state.spoken = 0;
  pushLine('YOU', text);
  state.messages.push({ role: 'user', content: text });
  setPhase('THINKING');
  $('streamText').textContent = '';
  $('streaming').classList.remove('hidden');
  trace(`turn ${id}: ${text}`);
  const result = await window.seven.sendTurn({
    id,
    messages: [{ role: 'system', content: SYSTEM_PROMPT }, ...state.messages],
    settings: state.settings,
  });
  if (!result.ok && !result.cancelled) {
    $('streaming').classList.add('hidden');
  }
}

function stopTurn() {
  trace('stop: cancelling the turn');
  window.seven.cancelTurn();
  speechSynthesis.cancel();
  state.utterances = 0;
  state.stream = '';
  state.spoken = 0;
  $('streaming').classList.add('hidden');
  state.awaiting = true;
  setPhase(state.micHeld ? 'LISTENING' : 'IDLE');
}

function handleHeard(text) {
  const heard = text.trim();
  if (!heard) return;
  trace(`heard: "${heard}"`);
  if (isStopPhrase(heard)) {
    if (state.phase === 'THINKING' || state.phase === 'SPEAKING') stopTurn();
    else {
      state.awaiting = false;
      setPhase('IDLE');
    }
    return;
  }
  // The microphone can hear SEVEN's own voice; nothing but a stop phrase acts mid-reply.
  if (state.phase === 'THINKING' || state.phase === 'SPEAKING') return;
  const command = wakeCommand(heard);
  if (command !== null) {
    if (!command) {
      state.awaiting = true;
      setPhase('LISTENING');
      return;
    }
    sendTurn(command);
    return;
  }
  if (state.awaiting) sendTurn(heard);
}

/* ---------- speech input ---------- */

async function startMic() {
  const result = await window.seven.startSpeech();
  if (result && result.running) trace('microphone opening');
}

function stopMic() {
  window.seven.stopSpeech();
  setMic(false);
  setPhase('IDLE');
}

function onSpeech(event) {
  if (event.type === 'ready') {
    setMic(true);
    setPhase(state.awaiting ? 'LISTENING' : 'IDLE');
    trace('recogniser ready');
    return;
  }
  if (event.type === 'final') {
    handleHeard(event.text);
    return;
  }
  if (event.type === 'error') {
    trace(`speech error: ${event.message}`);
    setPhase('ERROR');
    pushLine('SEVEN', event.message, 'error');
    return;
  }
  if (event.type === 'stopped') {
    setMic(false);
    if (state.wake && state.settings && state.settings.wake) {
      setTimeout(() => {
        if (!state.micHeld) startMic();
      }, 1200);
    }
  }
}

function onLlm(event) {
  if (event.type === 'delta') {
    state.stream += event.delta;
    $('streamText').textContent = state.stream;
    scrollTranscript();
    flushSentences(false);
    return;
  }
  if (event.type === 'done') {
    flushSentences(true);
    pushLine('SEVEN', event.text);
    state.messages.push({ role: 'assistant', content: event.text });
    $('streaming').classList.add('hidden');
    state.awaiting = true;
    if (state.utterances === 0) finishTurn();
    return;
  }
  if (event.type === 'error') {
    $('streaming').classList.add('hidden');
    pushLine('SEVEN', event.message, 'error');
    trace(`reasoning error: ${event.message}`);
    state.awaiting = true;
    setPhase('ERROR');
    return;
  }
  if (event.type === 'cancelled') {
    $('streaming').classList.add('hidden');
  }
}

/* ---------- settings ---------- */

function fillVoices() {
  const select = $('setVoice');
  const voices = speechSynthesis.getVoices();
  if (!voices.length) return;
  select.innerHTML = '';
  const auto = document.createElement('option');
  auto.value = '';
  auto.textContent = 'System default';
  select.appendChild(auto);
  for (const voice of voices) {
    const option = document.createElement('option');
    option.value = voice.name;
    option.textContent = `${voice.name} — ${voice.lang}`;
    select.appendChild(option);
  }
  select.value = state.settings.voice || '';
}

function openSettings() {
  $('setBaseUrl').value = state.settings.baseUrl;
  $('setKey').value = state.settings.apiKey;
  $('setModel').value = state.settings.model;
  $('setFallback').value = state.settings.fallbackModel;
  $('setRate').value = state.settings.rate;
  $('setWake').checked = !!state.settings.wake;
  fillVoices();
  $('settings').classList.remove('hidden');
}

async function saveSettings() {
  state.settings = await window.seven.saveSettings({
    ...state.settings,
    baseUrl: $('setBaseUrl').value.trim(),
    apiKey: $('setKey').value.trim(),
    model: $('setModel').value.trim(),
    fallbackModel: $('setFallback').value.trim(),
    voice: $('setVoice').value,
    rate: Number($('setRate').value) || 1,
    wake: $('setWake').checked,
  });
  applyWake(state.settings.wake);
  $('settings').classList.add('hidden');
  trace('settings saved');
}

async function testConnection() {
  const target = $('testResult');
  target.className = 'test-result';
  target.textContent = 'Testing…';
  const result = await window.seven.testConnection({
    baseUrl: $('setBaseUrl').value.trim(),
    apiKey: $('setKey').value.trim(),
  });
  target.className = 'test-result ' + (result.ok ? 'ok' : 'bad');
  target.textContent = result.detail;
}

function applyWake(enabled) {
  state.wake = !!enabled;
  $('wakeBtn').textContent = state.wake ? 'WAKE ON' : 'WAKE OFF';
  if (state.wake) {
    if (!state.started) {
      state.started = true;
      startMic();
    }
  } else {
    stopMic();
  }
}

/* ---------- orb ---------- */

function drawOrb() {
  const canvas = $('orb');
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (canvas.width !== width * ratio || canvas.height !== height * ratio) {
    canvas.width = width * ratio;
    canvas.height = height * ratio;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const cx = width / 2;
  const cy = height / 2;
  const radius = Math.min(width, height) * 0.30;
  const time = performance.now() / 1000;
  const phase = state.phase;

  let spin = 0.15;
  let tint = '95, 230, 255';
  let glow = 0.25;
  if (phase === 'THINKING') {
    spin = 0.9;
    tint = '255, 166, 61';
    glow = 0.45;
  } else if (phase === 'SPEAKING') {
    spin = 0.4;
    tint = '255, 200, 112';
    glow = 0.75 + Math.sin(time * 7) * 0.15;
  } else if (phase === 'LISTENING') {
    spin = 0.25;
    glow = 0.4 + Math.sin(time * 2.5) * 0.1;
  } else if (phase === 'ERROR') {
    tint = '255, 93, 93';
    glow = 0.5;
  }

  const core = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius * 0.5);
  core.addColorStop(0, `rgba(${tint}, ${0.55 + glow * 0.25})`);
  core.addColorStop(0.55, `rgba(${tint}, 0.18)`);
  core.addColorStop(1, 'rgba(0, 0, 0, 0)');
  ctx.fillStyle = core;
  ctx.beginPath();
  ctx.arc(cx, cy, radius * 0.62, 0, Math.PI * 2);
  ctx.fill();

  ctx.strokeStyle = `rgba(${tint}, ${0.28 + glow * 0.3})`;
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.arc(cx, cy, radius * 0.35, 0, Math.PI * 2);
  ctx.stroke();

  const rings = 3;
  for (let ring = 0; ring < rings; ring += 1) {
    const r = radius * (0.7 + ring * 0.22);
    const offset = time * spin * (ring % 2 === 0 ? 1 : -0.6) + ring;
    const arc = Math.PI * (1.1 + ring * 0.15);
    ctx.strokeStyle = `rgba(${tint}, ${0.5 - ring * 0.12})`;
    ctx.lineWidth = ring === 0 ? 1.6 : 1;
    ctx.beginPath();
    ctx.arc(cx, cy, r, offset, offset + arc);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(cx, cy, r, offset + Math.PI, offset + Math.PI + arc * 0.35);
    ctx.stroke();
  }

  if (phase === 'LISTENING') {
    const bars = 28;
    for (let bar = 0; bar < bars; bar += 1) {
      const angle = (bar / bars) * Math.PI * 2 + time * 0.3;
      const level = 0.5 + Math.sin(time * 3 + bar) * 0.5;
      const inner = radius * 0.55;
      const outer = inner + level * radius * 0.14;
      ctx.strokeStyle = `rgba(${tint}, 0.55)`;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(angle) * inner, cy + Math.sin(angle) * inner);
      ctx.lineTo(cx + Math.cos(angle) * outer, cy + Math.sin(angle) * outer);
      ctx.stroke();
    }
  }

  requestAnimationFrame(drawOrb);
}

/* ---------- wiring ---------- */

async function init() {
  state.settings = await window.seven.loadSettings();
  state.wake = !!state.settings.wake;
  $('wakeBtn').textContent = state.wake ? 'WAKE ON' : 'WAKE OFF';
  $('transcript').classList.add('empty');

  window.seven.onSpeech(onSpeech);
  window.seven.onLlm(onLlm);

  $('micBtn').addEventListener('click', () => {
    if (state.micHeld) {
      stopMic();
    } else {
      startMic();
    }
  });
  $('muteBtn').addEventListener('click', () => {
    state.muted = !state.muted;
    $('muteBtn').classList.toggle('active', state.muted);
    $('muteBtn').textContent = state.muted ? 'MUTED' : 'MUTE';
    if (state.muted) {
      speechSynthesis.cancel();
      state.utterances = 0;
    }
  });
  $('stopBtn').addEventListener('click', () => {
    stopTurn();
    state.awaiting = false;
    setPhase('IDLE');
  });
  $('settingsBtn').addEventListener('click', openSettings);
  $('closeSettings').addEventListener('click', () => $('settings').classList.add('hidden'));
  $('saveBtn').addEventListener('click', saveSettings);
  $('testBtn').addEventListener('click', testConnection);
  $('traceBtn').addEventListener('click', () => $('trace').classList.remove('hidden'));
  $('closeTrace').addEventListener('click', () => $('trace').classList.add('hidden'));
  $('clearTrace').addEventListener('click', () => {
    $('traceLog').textContent = '';
  });
  $('wakeBtn').addEventListener('click', async () => {
    state.settings.wake = !state.settings.wake;
    state.settings = await window.seven.saveSettings(state.settings);
    applyWake(state.settings.wake);
  });
  $('sendBtn').addEventListener('click', submitTyped);
  $('input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') submitTyped();
  });

  function submitTyped() {
    const text = $('input').value.trim();
    if (!text) return;
    $('input').value = '';
    state.awaiting = false;
    sendTurn(text);
  }

  speechSynthesis.addEventListener('voiceschanged', fillVoices);
  fillVoices();

  setMic(false);
  setPhase('IDLE');
  $('input').focus();
  if (state.wake) {
    state.started = true;
    startMic();
  }
  requestAnimationFrame(drawOrb);
}

init();
