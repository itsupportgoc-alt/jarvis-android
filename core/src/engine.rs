//! The engine: one object that owns audio ingress, the session machine, the
//! Qwen stream and the speech plan.
//!
//! Threading:
//! * **Capture thread** (Kotlin `AudioRecord`) calls `push_pcm` → lock-free SPSC.
//! * **DSP thread** drains the ring, assembles 20 ms frames, runs VAD and emits
//!   amplitude/speech events. It never allocates per frame and never awaits.
//! * **Dispatcher task** (Tokio) receives audio events and owns the session
//!   transitions, ASR and the LLM turn.
//! * **LLM pump** streams deltas into the same dispatcher's channel; cancellation
//!   is a `CancellationToken` raced against every `next()`.
//!
//! Everything the UI needs is emitted as an `EngineEvent`; no state is polled.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures::StreamExt;
use parking_lot::Mutex;
use serde::Deserialize;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::asr::{AsrEngine, UnavailableAsr};
use crate::audio::{peak, visual_level, AudioRing, FrameAssembler, PcmIngress, FRAME_MS, SAMPLE_RATE};
use crate::error::{JarvisError, Result};
use crate::events::EngineEvent;
use crate::llm::remote::QwenRemoteConfig;
use crate::llm::{
    ChatMessage, DeltaStream, LlmBackend, LlmDelta, QwenRemote, ToolSchema, UnavailableBackend,
};
#[cfg(feature = "local-llm")]
use crate::llm::{LocalLlmConfig, QwenLocal};
use crate::memory::{MemoryKind, MemoryStore, RememberRequest};
use crate::metrics::LatencyTrace;
use crate::session::{SessionEvent, SessionMachine, SessionState};
use crate::storage::EncryptedStore;
use crate::tts::{SpeechBuilder, SpokenLine};
use crate::vad::{EnergyVad, Vad, VadConfig};

/// Host callback surface. Implemented by JNI on Android and by a Vec in tests.
pub trait HostSink: Send + Sync {
    fn emit(&self, event: EngineEvent);
}

fn default_history() -> usize {
    24
}

/// Which reasoning backend the engine builds.
///
/// `none` is not a broken configuration: it lets memory, tools and voice run
/// before an API key exists. A turn then fails with a typed error instead of
/// silently reaching the network.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BackendKind {
    #[default]
    Remote,
    Local,
    None,
}

fn default_context_tokens() -> u32 {
    2_048
}

fn default_local_threads() -> i32 {
    3
}

fn default_max_ram_mb() -> u64 {
    2_048
}

fn default_local_max_tokens() -> u32 {
    256
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineConfig {
    pub qwen: QwenRemoteConfig,
    #[serde(default)]
    pub backend: BackendKind,
    /// GGUF model for `backend: local`.
    #[serde(default)]
    pub model_path: Option<String>,
    #[serde(default = "default_context_tokens")]
    pub context_tokens: u32,
    #[serde(default = "default_local_threads")]
    pub threads: i32,
    #[serde(default = "default_local_max_tokens")]
    pub local_max_tokens: u32,
    #[serde(default = "default_max_ram_mb")]
    pub max_ram_mb: u64,
    /// Path to the encrypted local memory journal. Absent disables memory.
    #[serde(default)]
    pub memory_path: Option<String>,
    #[serde(default)]
    pub tools: Vec<ToolSchema>,
    #[serde(default)]
    pub vad: VadConfig,
    #[serde(default = "default_history")]
    pub max_history_messages: usize,
    /// Read URLs aloud instead of saying "I've put that on screen instead."
    #[serde(default)]
    pub speak_urls: bool,
    /// Base64 of the 32-byte AES key from the Android Keystore. When absent,
    /// transcripts are kept in memory only.
    #[serde(default)]
    pub storage_key_b64: Option<String>,
    #[serde(default)]
    pub storage_path: Option<String>,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            qwen: QwenRemoteConfig::default(),
            backend: BackendKind::default(),
            model_path: None,
            context_tokens: default_context_tokens(),
            threads: default_local_threads(),
            local_max_tokens: default_local_max_tokens(),
            max_ram_mb: default_max_ram_mb(),
            memory_path: None,
            tools: Vec::new(),
            vad: VadConfig::default(),
            max_history_messages: default_history(),
            speak_urls: false,
            storage_key_b64: None,
            storage_path: None,
        }
    }
}

enum AudioEvent {
    Utterance {
        pcm: Vec<f32>,
        duration_ms: u64,
        peak: f32,
    },
}

struct Inner {
    cfg: EngineConfig,
    sink: Arc<dyn HostSink>,
    llm: Arc<dyn LlmBackend>,
    asr: Arc<dyn AsrEngine>,
    session: Mutex<SessionMachine>,
    history: Mutex<Vec<ChatMessage>>,
    cancel: Mutex<CancellationToken>,
    ingress: Arc<PcmIngress>,
    consumer: Mutex<Option<rtrb::Consumer<f32>>>,
    running: AtomicBool,
    audio_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
    dispatcher: Mutex<Option<tokio::task::JoinHandle<()>>>,
    audio_tx: Mutex<Option<mpsc::UnboundedSender<AudioEvent>>>,
    storage: Option<Arc<EncryptedStore>>,
    memory: Option<Arc<MemoryStore>>,
    last_reply: Mutex<String>,
}

impl Inner {
    fn emit(&self, event: EngineEvent) {
        self.sink.emit(event);
    }

    fn apply(&self, event: SessionEvent) {
        let transition = {
            let mut machine = self.session.lock();
            match machine.apply(event) {
                Ok(t) => t,
                Err(invalid) => {
                    tracing::debug!(?invalid, "ignored invalid session event");
                    None
                }
            }
        };
        if let Some(t) = transition {
            self.emit(EngineEvent::State {
                state: t.to.as_str().to_string(),
            });
        }
    }

    fn state(&self) -> SessionState {
        self.session.lock().state()
    }

    fn fail(&self, error: JarvisError) {
        self.emit(EngineEvent::Error { error });
        self.apply(SessionEvent::Failure);
    }

    fn begin_turn(&self) -> CancellationToken {
        let token = {
            let mut current = self.cancel.lock();
            current.cancel();
            let fresh = CancellationToken::new();
            *current = fresh.clone();
            fresh
        };
        match self.state() {
            SessionState::Idle => {
                self.apply(SessionEvent::Wake);
                self.apply(SessionEvent::SpeechEnd);
            }
            SessionState::Listening => self.apply(SessionEvent::SpeechEnd),
            SessionState::Error => {
                self.apply(SessionEvent::Reset);
                self.apply(SessionEvent::Wake);
                self.apply(SessionEvent::SpeechEnd);
            }
            _ => {}
        }
        token
    }

    fn trim_history(&self, max: usize) {
        let mut history = self.history.lock();
        while history.len() > max {
            history.remove(0);
        }
        // Never start with a tool result whose assistant call was trimmed.
        while matches!(history.first().map(|m| m.role), Some(crate::llm::Role::Tool)) {
            history.remove(0);
        }
    }

    fn remember(&self, request: RememberRequest) {
        if let Some(memory) = &self.memory {
            if let Err(error) = memory.remember(request) {
                tracing::debug!(%error, "memory record skipped");
            }
        }
    }

    fn log_record(&self, value: serde_json::Value) {
        if let Some(store) = &self.storage {
            if let Err(error) = store.append(&value) {
                tracing::warn!(%error, "encrypted store append failed");
            }
        }
    }

    fn emit_line(&self, line: SpokenLine, speaking: &mut bool) {
        if !*speaking {
            self.apply(SessionEvent::ReplyStarted);
            *speaking = true;
        }
        self.emit(EngineEvent::Speak {
            text: line.spoken,
            question: line.question,
            index: line.index,
        });
    }

    async fn stream_and_speak(self: &Arc<Self>, mut stream: DeltaStream, token: CancellationToken) {
        let mut builder = SpeechBuilder::new(180, self.cfg.speak_urls, 1.0);
        let mut trace = LatencyTrace::new();
        let mut assistant = String::new();
        let mut mood = "neutral".to_string();
        let mut tool_calls = Vec::new();
        let mut first_token = true;
        let mut speaking = false;
        let mut lines = 0u32;

        loop {
            let item = tokio::select! {
                _ = token.cancelled() => return,
                item = stream.next() => item,
            };
            let Some(item) = item else { break };
            match item {
                Ok(LlmDelta::Content(text)) => {
                    if text.is_empty() {
                        continue;
                    }
                    if first_token {
                        first_token = false;
                        let ms = trace.mark("first_token");
                        self.emit(EngineEvent::Latency {
                            stage: "qwen_first_token".into(),
                            ms,
                        });
                    }
                    assistant.push_str(&text);
                    self.emit(EngineEvent::AssistantDelta { text: text.clone() });
                    for line in builder.push(&text) {
                        self.emit_line(line, &mut speaking);
                        lines += 1;
                        if lines == 1 {
                            self.emit(EngineEvent::Latency {
                                stage: "first_sentence".into(),
                                ms: trace.mark_from_start("first_sentence"),
                            });
                        }
                    }
                }
                Ok(LlmDelta::Mood(next)) => {
                    mood = next.clone();
                    self.emit(EngineEvent::Mood { mood: next });
                }
                Ok(LlmDelta::ToolCall(call)) => {
                    tool_calls.push(call);
                }
                Ok(LlmDelta::Done { .. }) => break,
                Err(JarvisError::Cancelled) => return,
                Err(error) => {
                    self.fail(error);
                    return;
                }
            }
        }

        if token.is_cancelled() {
            return;
        }

        if let Some(line) = builder.finish() {
            self.emit_line(line, &mut speaking);
            lines += 1;
        }

        let trimmed = assistant.trim().to_string();
        // Assistant output is not memory: history keeps it for this session, but
        // generated text must not later be injected as a verified record.
        self.history.lock().push(ChatMessage::assistant(
            trimmed.clone(),
            tool_calls.clone(),
        ));
        self.trim_history(self.cfg.max_history_messages);
        *self.last_reply.lock() = trimmed.clone();

        self.log_record(serde_json::json!({
            "t": unix_time(),
            "role": "assistant",
            "text": trimmed,
            "mood": mood,
        }));

        if !tool_calls.is_empty() {
            // Kotlin executes each call behind the confirmation gate, then calls
            // submit_tool_result; the follow-up stream continues the turn.
            for call in tool_calls {
                self.emit(EngineEvent::ToolCall {
                    id: call.id,
                    name: call.name,
                    arguments: call.arguments,
                });
            }
            return;
        }

        self.emit(EngineEvent::AssistantDone {
            text: trimmed,
            mood,
        });

        if lines == 0 || !speaking {
            // Nothing to say (or muted UI): close the session immediately.
            self.apply(SessionEvent::ReplyFinished);
        }
        // Otherwise the host calls `tts_done()` once its speech queue drains.
    }

    async fn run_turn(self: Arc<Self>, user_text: String, preamble: Option<String>) {
        let token = self.begin_turn();
        // Retrieve before recording this turn so the prompt does not repeat the
        // input back to the model as a "remembered" fact.
        let memory_context = self
            .memory
            .as_ref()
            .map(|memory| memory.context_for(&user_text, 600))
            .unwrap_or_default();
        self.remember(RememberRequest::conversation(
            MemoryKind::ShortTerm,
            user_text.clone(),
        ));
        self.log_record(serde_json::json!({
            "t": unix_time(),
            "role": "user",
            "text": user_text,
        }));
        self.history.lock().push(ChatMessage::user(user_text));
        self.trim_history(self.cfg.max_history_messages);

        let mut messages = self.history.lock().clone();
        if !memory_context.is_empty() {
            messages.insert(0, ChatMessage::system(memory_context));
        }
        // The host supplies its verified self-model separately so it never
        // becomes part of the user's turn (history, memory, or transcript).
        if let Some(preamble) = preamble.filter(|text| !text.trim().is_empty()) {
            messages.insert(0, ChatMessage::system(preamble));
        }
        let stream = match self.llm.stream_chat(messages, token.clone()).await {
            Ok(stream) => stream,
            Err(JarvisError::Cancelled) => return,
            Err(error) => {
                self.fail(error);
                return;
            }
        };
        self.stream_and_speak(stream, token).await;
    }

    async fn on_utterance(self: &Arc<Self>, pcm: Vec<f32>, duration_ms: u64, peak_rms: f32) {
        self.emit(EngineEvent::SpeechEnd {
            duration_ms,
            peak_rms,
        });
        if self.state() == SessionState::Listening {
            self.apply(SessionEvent::SpeechEnd);
        }
        let started = std::time::Instant::now();
        match self.asr.transcribe(pcm).await {
            Ok(text) if !text.trim().is_empty() => {
                self.emit(EngineEvent::Latency {
                    stage: "asr_final".into(),
                    ms: started.elapsed().as_secs_f64() * 1000.0,
                });
                self.emit(EngineEvent::Transcript { text: text.clone() });
                Arc::clone(self).run_turn(text, None).await;
            }
            Ok(_) => {
                self.emit(EngineEvent::Transcript {
                    text: String::new(),
                });
                self.apply(SessionEvent::Reset);
            }
            Err(JarvisError::Cancelled) => {}
            Err(error) => self.fail(error),
        }
    }

    fn on_speech_start(&self) {
        let state = self.state();
        if state == SessionState::Speaking || state == SessionState::Thinking {
            // Barge-in: kill the LLM stream and stop playback on the host.
            self.cancel.lock().cancel();
            self.emit(EngineEvent::BargeIn);
            if state == SessionState::Speaking {
                self.apply(SessionEvent::SpeechStart);
            }
            return;
        }
        if state == SessionState::Idle {
            self.apply(SessionEvent::Wake);
        }
        self.emit(EngineEvent::SpeechStart);
    }
}

fn unix_time() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Build the optional llama.cpp backend. A missing feature is a typed error,
/// never a silent remote fallback.
fn local_backend(config: &EngineConfig) -> Result<Arc<dyn LlmBackend>> {
    #[cfg(feature = "local-llm")]
    {
        let path = config.model_path.as_deref().ok_or_else(|| {
            JarvisError::NotConfigured("local reasoning needs a GGUF model path".into())
        })?;
        let local = QwenLocal::load(LocalLlmConfig {
            model_path: PathBuf::from(path),
            context_tokens: config.context_tokens,
            threads: config.threads,
            max_tokens: config.local_max_tokens,
            max_ram_mb: config.max_ram_mb,
            ..LocalLlmConfig::default()
        })?;
        Ok(Arc::new(local))
    }
    #[cfg(not(feature = "local-llm"))]
    {
        let _ = config;
        Err(JarvisError::NotConfigured(
            "This build was compiled without the local-llm feature; use a build with -PlocalLlm."
                .into(),
        ))
    }
}

/// The public engine handle. Cheap to clone through `Arc` on the Kotlin side;
/// the JNI layer owns exactly one boxed instance.
pub struct JarvisEngine {
    inner: Arc<Inner>,
    runtime: tokio::runtime::Runtime,
}

impl JarvisEngine {
    pub fn new(config: EngineConfig, sink: Arc<dyn HostSink>) -> Result<Self> {
        let llm: Arc<dyn LlmBackend> = match config.backend {
            BackendKind::Remote if !config.qwen.api_key.trim().is_empty() => {
                Arc::new(QwenRemote::new(config.qwen.clone(), config.tools.clone())?)
            }
            BackendKind::Remote => Arc::new(UnavailableBackend::new(
                "No API key is configured. Add one in Settings to generate replies; memory and device tools still work.",
            )),
            BackendKind::Local => local_backend(&config)?,
            BackendKind::None => Arc::new(UnavailableBackend::new(
                "No reasoning backend is configured. Add an API key or import a local GGUF model in Settings.",
            )),
        };
        let asr: Arc<dyn AsrEngine> = Arc::new(UnavailableAsr::default());
        let load_ms = llm.load_ms();
        let event_sink = Arc::clone(&sink);
        let engine = Self::with_components(config, sink, llm, asr)?;
        if let Some(ms) = load_ms {
            event_sink.emit(EngineEvent::Latency {
                stage: "local_model_load".into(),
                ms,
            });
        }
        Ok(engine)
    }


    /// Used by tests and by feature builds that inject Whisper / a fake ASR.
    pub fn with_components(
        config: EngineConfig,
        sink: Arc<dyn HostSink>,
        llm: Arc<dyn LlmBackend>,
        asr: Arc<dyn AsrEngine>,
    ) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("jarvis-async")
            .enable_all()
            .build()
            .map_err(|e| JarvisError::Internal(format!("tokio runtime: {e}")))?;

        let storage = match (&config.storage_key_b64, &config.storage_path) {
            (Some(encoded), Some(path)) => {
                use base64::Engine as _;
                let key = base64::engine::general_purpose::STANDARD
                    .decode(encoded)
                    .map_err(|e| JarvisError::InvalidArgument(format!("storage key: {e}")))?;
                let store = EncryptedStore::new(&key, PathBuf::from(path))?;
                Some(Arc::new(store))
            }
            _ => None,
        };

        let memory = match (&config.storage_key_b64, &config.memory_path) {
            (Some(encoded), Some(path)) => {
                use base64::Engine as _;
                match base64::engine::general_purpose::STANDARD.decode(encoded) {
                    Ok(key) => match MemoryStore::open(&key, PathBuf::from(path)) {
                        Ok(store) => Some(Arc::new(store)),
                        Err(error) => {
                            tracing::warn!(%error, "local memory disabled: cannot open the journal");
                            None
                        }
                    },
                    Err(error) => {
                        tracing::warn!(%error, "local memory disabled: invalid storage key");
                        None
                    }
                }
            }
            _ => None,
        };

        let mut ring = AudioRing::new(crate::audio::RING_CAPACITY);
        let ingress = ring.ingress();
        let consumer = ring
            .take_consumer()
            .ok_or_else(|| JarvisError::Internal("audio ring consumer missing".into()))?;

        let inner = Arc::new(Inner {
            cfg: config,
            sink,
            llm,
            asr,
            session: Mutex::new(SessionMachine::new()),
            history: Mutex::new(Vec::new()),
            cancel: Mutex::new(CancellationToken::new()),
            ingress,
            consumer: Mutex::new(Some(consumer)),
            running: AtomicBool::new(false),
            audio_thread: Mutex::new(None),
            dispatcher: Mutex::new(None),
            audio_tx: Mutex::new(None),
            storage,
            memory,
            last_reply: Mutex::new(String::new()),
        });

        Ok(Self { inner, runtime })
    }

    /// Push mono 16 kHz f32 PCM from the Android capture thread. Never blocks;
    /// returns how many samples the ring accepted.
    pub fn push_pcm(&self, samples: &[f32]) -> usize {
        self.inner.ingress.push(samples)
    }

    pub fn start(&self) -> Result<()> {
        if self.inner.running.swap(true, Ordering::SeqCst) {
            return Ok(());
        }
        let (tx, mut rx) = mpsc::unbounded_channel();
        *self.inner.audio_tx.lock() = Some(tx);

        let consumer = self
            .inner
            .consumer
            .lock()
            .take()
            .ok_or_else(|| JarvisError::Internal("engine already started".into()))?;

        let audio_inner = self.inner.clone();
        let thread = std::thread::Builder::new()
            .name("jarvis-dsp".into())
            .spawn(move || audio_loop(audio_inner, consumer))
            .map_err(|e| JarvisError::Audio(format!("spawn dsp: {e}")))?;
        *self.inner.audio_thread.lock() = Some(thread);

        let dispatch_inner = self.inner.clone();
        let dispatcher = self.runtime.spawn(async move {
            while let Some(event) = rx.recv().await {
                match event {
                    AudioEvent::Utterance {
                        pcm,
                        duration_ms,
                        peak,
                    } => {
                        dispatch_inner.on_utterance(pcm, duration_ms, peak).await;
                    }
                }
            }
        });
        *self.inner.dispatcher.lock() = Some(dispatcher);

        self.inner.emit(EngineEvent::Engine { running: true });
        self.inner.emit(EngineEvent::state(SessionState::Idle));
        Ok(())
    }

    pub fn stop(&self) {
        if !self.inner.running.swap(false, Ordering::SeqCst) {
            return;
        }
        self.inner.cancel.lock().cancel();
        if let Some(handle) = self.inner.audio_thread.lock().take() {
            let _ = handle.join();
        }
        if let Some(dispatcher) = self.inner.dispatcher.lock().take() {
            dispatcher.abort();
        }
        self.inner.emit(EngineEvent::Engine { running: false });
    }

    /// Interrupt: cancels the LLM stream and moves out of speaking/thinking.
    pub fn cancel(&self) {
        self.inner.cancel.lock().cancel();
        self.inner.emit(EngineEvent::BargeIn);
        match self.inner.state() {
            SessionState::Speaking => self.inner.apply(SessionEvent::Interrupt),
            SessionState::Thinking | SessionState::Listening | SessionState::Error => {
                self.inner.apply(SessionEvent::Reset)
            }
            SessionState::Idle => {}
        }
    }

    pub fn submit_text(&self, text: &str) -> Result<()> {
        self.submit(text, None)
    }

    /// Submit a user turn with the host's verified self-model as a system
    /// message. Unlike the old prompt string, the preamble is not stored as part
    /// of the turn or local memory.
    pub fn submit_turn(&self, text: &str, preamble: &str) -> Result<()> {
        self.submit(text, Some(preamble))
    }

    fn submit(&self, text: &str, preamble: Option<&str>) -> Result<()> {
        let text = text.trim().to_string();
        if text.is_empty() {
            return Err(JarvisError::InvalidArgument("empty prompt".into()));
        }
        if !self.inner.running.load(Ordering::SeqCst) {
            return Err(JarvisError::NotConfigured("engine not started".into()));
        }
        let preamble = preamble
            .map(str::trim)
            .filter(|text| !text.is_empty())
            .map(str::to_string);
        let inner = self.inner.clone();
        self.runtime.spawn(async move {
            inner.run_turn(text, preamble).await;
        });
        Ok(())
    }

    /// Continue the conversation after Kotlin executed a tool.
    pub fn submit_tool_result(&self, tool_call_id: &str, result_json: &str) -> Result<()> {
        if tool_call_id.trim().is_empty() {
            return Err(JarvisError::InvalidArgument(
                "tool_call_id is required".into(),
            ));
        }
        self.inner
            .history
            .lock()
            .push(ChatMessage::tool_result(tool_call_id, result_json));
        self.inner.trim_history(self.inner.cfg.max_history_messages);

        let token = self.inner.begin_turn();
        let inner = self.inner.clone();
        self.runtime.spawn(async move {
            let messages = inner.history.lock().clone();
            match inner.llm.stream_chat(messages, token.clone()).await {
                Ok(stream) => inner.stream_and_speak(stream, token).await,
                Err(JarvisError::Cancelled) => {}
                Err(error) => inner.fail(error),
            }
        });
        Ok(())
    }

    /// Called by the host when its TTS queue has fully drained.
    pub fn tts_done(&self) {
        self.inner.apply(SessionEvent::ReplyFinished);
    }

    /// Record a tool invocation for the debug view (latency is measured by the
    /// host and echoed back here so it lands in the encrypted trace).
    pub fn record_tool_trace(
        &self,
        tool: &str,
        arguments: serde_json::Value,
        result: &str,
        ok: bool,
        latency_ms: f64,
    ) {
        self.inner.emit(EngineEvent::ToolTrace {
            tool: tool.to_string(),
            arguments: arguments.clone(),
            result: result.chars().take(2000).collect(),
            ok,
            latency_ms,
        });
        let memory_text = if ok {
            format!("{tool} succeeded")
        } else {
            format!("{tool} failed: {}", result.chars().take(200).collect::<String>())
        };
        self.inner
            .remember(RememberRequest::tool(tool, memory_text, ok));
        self.inner.log_record(serde_json::json!({
            "t": unix_time(),
            "role": "tool",
            "tool": tool,
            "arguments": arguments,
            "result": result.chars().take(2000).collect::<String>(),
            "ok": ok,
            "latency_ms": latency_ms,
        }));
    }

    /// Versioned JSON command channel for local memory (and later goals).
    pub fn invoke(&self, request: &str) -> String {
        match &self.inner.memory {
            Some(memory) => memory.invoke(request),
            None => serde_json::json!({
                "ok": false,
                "error": {
                    "kind": "notConfigured",
                    "detail": "Local memory is not enabled for this engine."
                }
            })
            .to_string(),
        }
    }

    pub fn last_reply(&self) -> String {
        self.inner.last_reply.lock().clone()
    }

    pub fn state(&self) -> SessionState {
        self.inner.state()
    }

    pub fn is_running(&self) -> bool {
        self.inner.running.load(Ordering::SeqCst)
    }

    pub fn storage_records(&self) -> Result<Vec<serde_json::Value>> {
        match &self.inner.storage {
            Some(store) => store.read_all(),
            None => Ok(Vec::new()),
        }
    }
}

impl Drop for JarvisEngine {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Dedicated DSP thread. No async, no locks on the hot path, no per-frame
/// allocation. Utterance audio uses a reused, pre-sized buffer.
fn audio_loop(inner: Arc<Inner>, mut consumer: rtrb::Consumer<f32>) {
    let mut assembler = FrameAssembler::default();
    let mut vad = EnergyVad::new(inner.cfg.vad);
    let capacity = (SAMPLE_RATE as usize * 15_000) / 1000;
    let mut utterance: Vec<f32> = Vec::with_capacity(capacity);
    let mut peak_level = 0.0f32;
    let mut last_level: Option<std::time::Instant> = None;

    while inner.running.load(Ordering::Relaxed) {
        let mut progressed = false;
        while let Ok(sample) = consumer.pop() {
            progressed = true;
            if !assembler.push(sample) {
                continue;
            }
            let out = vad.push_frame(assembler.frame());

            let due = last_level.map_or(true, |t| t.elapsed() >= Duration::from_millis(16));
            if due {
                last_level = Some(std::time::Instant::now());
                inner.emit(EngineEvent::MicAmplitude {
                    rms: visual_level(out.rms),
                });
            }

            if out.started {
                utterance.clear();
                peak_level = 0.0;
                // Routes through the session machine: emits SpeechStart, and
                // performs barge-in if JARVIS is currently speaking.
                inner.on_speech_start();
            }
            if out.started || vad.is_speaking() || out.ended {
                let frame = assembler.frame();
                peak_level = peak_level.max(peak(frame));
                if utterance.len() < capacity {
                    utterance.extend_from_slice(frame);
                }
            }
            if out.ended {
                let pcm = std::mem::replace(&mut utterance, Vec::with_capacity(capacity));
                let duration_ms = (pcm.len() as u64 * 1000) / SAMPLE_RATE as u64;
                if let Some(tx) = inner.audio_tx.lock().as_ref() {
                    let _ = tx.send(AudioEvent::Utterance {
                        pcm,
                        duration_ms,
                        peak: peak_level,
                    });
                }
            }
        }

        if !progressed {
            std::thread::sleep(Duration::from_millis(4));
        }
    }
    tracing::debug!("dsp thread stopped; frame={FRAME_MS}ms");
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio::FRAME_SAMPLES;
    use crate::llm::{LlmBackend, Role, ScriptedBackend};
    use crate::session::SessionState;
    use async_trait::async_trait;
    use tokio_util::sync::CancellationToken;

    struct Capture {
        events: Mutex<Vec<EngineEvent>>,
    }

    impl HostSink for Capture {
        fn emit(&self, event: EngineEvent) {
            self.events.lock().push(event);
        }
    }

    fn engine(tokens: Vec<&str>) -> (JarvisEngine, Arc<Capture>) {
        let capture = Arc::new(Capture {
            events: Mutex::new(Vec::new()),
        });
        let backend = Arc::new(ScriptedBackend {
            tokens: tokens.into_iter().map(str::to_string).collect(),
            fail_after: None,
        });
        let engine = JarvisEngine::with_components(
            EngineConfig::default(),
            capture.clone(),
            backend,
            Arc::new(UnavailableAsr::default()),
        )
        .unwrap();
        engine.start().unwrap();
        (engine, capture)
    }

    fn wait_for(capture: &Capture, predicate: impl Fn(&EngineEvent) -> bool) -> bool {
        for _ in 0..200 {
            if capture.events.lock().iter().any(&predicate) {
                return true;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        false
    }

    fn speaks(capture: &Capture) -> Vec<String> {
        capture
            .events
            .lock()
            .iter()
            .filter_map(|e| match e {
                EngineEvent::Speak { text, .. } => Some(text.clone()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn streams_two_sentences_then_speaks_then_goes_idle() {
        let (engine, capture) = engine(vec![
            "Good evening, sir. ",
            "The reactor is stable.",
        ]);
        engine.submit_text("status").unwrap();
        assert!(wait_for(&capture, |e| matches!(e, EngineEvent::AssistantDone { .. })));
        let lines = speaks(&capture);
        assert_eq!(lines.len(), 2, "{lines:?}");
        assert_eq!(engine.state(), SessionState::Speaking);
        engine.tts_done();
        assert_eq!(engine.state(), SessionState::Idle);
    }

    #[test]
    fn cancel_mid_thinking_returns_to_idle() {
        let (engine, _capture) = engine(vec!["one two three"]);
        engine.submit_text("hello").unwrap();
        std::thread::sleep(Duration::from_millis(30));
        engine.cancel();
        assert!(matches!(
            engine.state(),
            SessionState::Idle | SessionState::Listening
        ));
    }

    #[test]
    fn microphone_energy_opens_the_session_then_reports_unavailable_asr() {
        let (engine, capture) = engine(vec!["unused"]);
        let loud = [0.25f32; FRAME_SAMPLES];
        engine.push_pcm(&loud);
        engine.push_pcm(&loud);
        assert!(wait_for(&capture, |e| matches!(e, EngineEvent::SpeechStart)));
        assert!(wait_for(&capture, |e| matches!(e, EngineEvent::MicAmplitude { .. })));

        let silence = [0.0f32; FRAME_SAMPLES];
        for _ in 0..40 {
            engine.push_pcm(&silence);
        }
        assert!(
            wait_for(&capture, |e| matches!(e, EngineEvent::SpeechEnd { .. })),
            "no speech end"
        );
        let error = wait_for(&capture, |e| matches!(e, EngineEvent::Error { .. }));
        assert!(error, "ASR-unavailable error was not surfaced");
        assert_eq!(engine.state(), SessionState::Error);
        engine.cancel();
    }

    #[test]
    fn empty_prompt_is_rejected() {
        let (engine, _) = engine(vec![]);
        assert!(engine.submit_text("   ").is_err());
    }

    #[test]
    fn latency_and_tool_events_are_forwarded() {
        let (engine, capture) = engine(vec!["Done."]);
        engine.submit_text("hi").unwrap();
        assert!(wait_for(&capture, |e| matches!(
            e,
            EngineEvent::AssistantDone { .. }
        )));
        engine.record_tool_trace(
            "get_battery",
            serde_json::json!({}),
            "42%",
            true,
            3.5,
        );
        assert!(capture
            .events
            .lock()
            .iter()
            .any(|e| matches!(e, EngineEvent::Latency { stage, .. } if stage == "qwen_first_token")));
        assert!(capture
            .events
            .lock()
            .iter()
            .any(|e| matches!(e, EngineEvent::ToolTrace { tool, .. } if tool == "get_battery")));
    }

    #[test]
    fn engine_starts_and_stops_cleanly() {
        let (engine, capture) = engine(vec![]);
        engine.stop();
        assert!(!engine.is_running());
        assert!(capture
            .events
            .lock()
            .iter()
            .any(|e| matches!(e, EngineEvent::Engine { running: false })));
        assert!(engine.submit_text("hello").is_err());
    }

    fn memory_key() -> String {
        use base64::Engine as _;
        base64::engine::general_purpose::STANDARD.encode([7u8; 32])
    }

    fn memory_config(dir: &tempfile::TempDir) -> EngineConfig {
        EngineConfig {
            backend: BackendKind::None,
            storage_key_b64: Some(memory_key()),
            memory_path: Some(dir.path().join("memory.enc").to_string_lossy().into_owned()),
            ..EngineConfig::default()
        }
    }

    struct RecordingBackend {
        seen: Arc<Mutex<Vec<Vec<ChatMessage>>>>,
    }

    #[async_trait]
    impl LlmBackend for RecordingBackend {
        async fn stream_chat(
            &self,
            messages: Vec<ChatMessage>,
            _cancel: CancellationToken,
        ) -> Result<DeltaStream> {
            self.seen.lock().push(messages);
            let stream = futures::stream::once(async {
                let item: Result<LlmDelta> = Ok(LlmDelta::Done {
                    finish_reason: Some("stop".into()),
                });
                item
            });
            Ok(Box::pin(stream))
        }

        fn name(&self) -> &'static str {
            "recording"
        }
    }

    #[test]
    fn keyless_engine_starts_and_reports_a_typed_error() {
        let capture = Arc::new(Capture {
            events: Mutex::new(Vec::new()),
        });
        let engine = JarvisEngine::new(
            EngineConfig {
                backend: BackendKind::None,
                ..EngineConfig::default()
            },
            capture.clone(),
        )
        .unwrap();
        engine.start().unwrap();
        engine.submit_text("hello").unwrap();
        assert!(wait_for(&capture, |e| matches!(
            e,
            EngineEvent::Error { error } if matches!(error, JarvisError::NotConfigured(_))
        )));
        engine.stop();
    }

    #[test]
    fn local_backend_without_a_model_path_refuses_to_start() {
        let capture = Arc::new(Capture {
            events: Mutex::new(Vec::new()),
        });
        let error = JarvisEngine::new(
            EngineConfig {
                backend: BackendKind::Local,
                ..EngineConfig::default()
            },
            capture,
        )
        .err()
        .expect("local backend without a model must fail");
        assert!(matches!(error, JarvisError::NotConfigured(_)));
    }

    #[test]
    fn keyless_engine_records_a_turn_in_local_memory() {
        let dir = tempfile::tempdir().unwrap();
        let capture = Arc::new(Capture {
            events: Mutex::new(Vec::new()),
        });
        let engine = JarvisEngine::new(memory_config(&dir), capture.clone()).unwrap();
        engine.start().unwrap();
        engine
            .submit_text("the reactor needs a coolant check")
            .unwrap();
        assert!(wait_for(&capture, |e| matches!(e, EngineEvent::Error { .. })));
        let listed = engine.invoke(r#"{"op":"memory.list"}"#);
        assert!(listed.contains("\"ok\":true"), "{listed}");
        assert!(listed.contains("coolant check"), "{listed}");
        engine.stop();
    }

    #[test]
    fn relevant_memory_is_injected_into_the_turn() {
        let dir = tempfile::tempdir().unwrap();
        let capture = Arc::new(Capture {
            events: Mutex::new(Vec::new()),
        });
        let seen = Arc::new(Mutex::new(Vec::new()));
        let backend = Arc::new(RecordingBackend { seen: seen.clone() });
        let engine = JarvisEngine::with_components(
            memory_config(&dir),
            capture.clone(),
            backend,
            Arc::new(UnavailableAsr::default()),
        )
        .unwrap();
        engine.start().unwrap();
        let added = engine.invoke(
            r#"{"op":"memory.add","kind":"preference","text":"Lester prefers brief answers","userApproved":true}"#,
        );
        assert!(added.contains("\"ok\":true"), "{added}");
        engine
            .submit_text("Lester, how long should the answer be?")
            .unwrap();
        assert!(wait_for(&capture, |e| matches!(
            e,
            EngineEvent::AssistantDone { .. }
        )));
        let seen = seen.lock();
        let messages = seen.first().expect("backend was not called");
        assert_eq!(messages[0].role, Role::System);
        assert!(
            messages[0].content.contains("brief answers"),
            "{:?}",
            messages[0].content
        );
        engine.stop();
    }

    #[test]
    fn host_preamble_is_a_system_message_not_memory() {
        let dir = tempfile::tempdir().unwrap();
        let capture = Arc::new(Capture {
            events: Mutex::new(Vec::new()),
        });
        let seen = Arc::new(Mutex::new(Vec::new()));
        let backend = Arc::new(RecordingBackend { seen: seen.clone() });
        let engine = JarvisEngine::with_components(
            memory_config(&dir),
            capture.clone(),
            backend,
            Arc::new(UnavailableAsr::default()),
        )
        .unwrap();
        engine.start().unwrap();
        engine
            .submit_turn("check the coolant", "VERIFIED SELF MODEL")
            .unwrap();
        assert!(wait_for(&capture, |e| matches!(
            e,
            EngineEvent::AssistantDone { .. }
        )));
        let seen = seen.lock();
        let messages = seen.first().expect("backend was not called");
        assert_eq!(messages[0].role, Role::System);
        assert!(messages[0].content.contains("VERIFIED SELF MODEL"));
        let user = messages
            .iter()
            .find(|message| message.role == Role::User)
            .expect("user turn missing");
        assert_eq!(user.content, "check the coolant");
        drop(seen);
        let listed = engine.invoke(r#"{"op":"memory.list"}"#);
        assert!(listed.contains("check the coolant"), "{listed}");
        assert!(!listed.contains("VERIFIED SELF MODEL"), "{listed}");
        engine.stop();
    }
}
