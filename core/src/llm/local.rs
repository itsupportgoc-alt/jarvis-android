//! On-device GGUF reasoning through llama.cpp.
//!
//! This backend is compiled only with the `local-llm` feature. It loads one
//! model at construction and runs each turn on a blocking worker: llama.cpp is
//! synchronous and CPU-bound, so it must never run on the async dispatcher.
//!
//! The backend is intentionally not wired into `EngineConfig` yet. The first
//! step is a reproducible cross-compile for all three Android ABIs (see
//! `docs/OFFLINE_BUILD.md`); device loading, RAM guards, model download and the
//! Kotlin model picker are the rest of Phase 3.

use std::num::NonZeroU32;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use futures::Stream;
use llama_cpp_2::context::params::LlamaContextParams;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::params::LlamaModelParams;
use llama_cpp_2::model::{LlamaChatMessage, LlamaModel};
use llama_cpp_2::sampling::LlamaSampler;
use llama_cpp_2::ChatTemplateError;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::error::{JarvisError, Result};
use crate::llm::{ChatMessage, DeltaStream, LlmBackend, LlmDelta, Role};

/// Tunables for one local model. Values are validated before the model is
/// loaded so a bad configuration fails without allocating weights.
#[derive(Debug, Clone)]
pub struct LocalLlmConfig {
    pub model_path: PathBuf,
    pub context_tokens: u32,
    /// llama.cpp worker threads. Kept explicit because Android big.LITTLE
    /// scheduling makes "all cores" a poor default.
    pub threads: i32,
    pub max_tokens: u32,
    pub temperature: f32,
    pub top_p: f32,
    pub seed: u32,
    /// Hard ceiling for the model file plus a fixed runtime reserve. The guard
    /// refuses to load rather than relying on the Android OOM killer.
    pub max_ram_mb: u64,
}

impl Default for LocalLlmConfig {
    fn default() -> Self {
        Self {
            model_path: PathBuf::new(),
            context_tokens: 2_048,
            threads: 3,
            max_tokens: 256,
            temperature: 0.4,
            top_p: 0.9,
            seed: 0x4a41_5256,
            max_ram_mb: 2_048,
        }
    }
}

impl LocalLlmConfig {
    pub fn validate(&self) -> Result<()> {
        if self.model_path.as_os_str().is_empty() {
            return Err(JarvisError::NotConfigured(
                "no GGUF model path is configured".into(),
            ));
        }
        if !self.model_path.is_file() {
            return Err(JarvisError::NotConfigured(format!(
                "GGUF model not found: {}",
                self.model_path.display()
            )));
        }
        if self.context_tokens < 256 {
            return Err(JarvisError::InvalidArgument(
                "local context must be at least 256 tokens".into(),
            ));
        }
        if self.threads < 1 || self.threads > 16 {
            return Err(JarvisError::InvalidArgument(
                "local thread count must be between 1 and 16".into(),
            ));
        }
        if self.max_tokens == 0 || self.max_tokens >= self.context_tokens {
            return Err(JarvisError::InvalidArgument(
                "local max_tokens must be positive and smaller than the context".into(),
            ));
        }
        if !(0.0..=2.0).contains(&self.temperature) {
            return Err(JarvisError::InvalidArgument(
                "local temperature must be between 0 and 2".into(),
            ));
        }
        if !(0.0..=1.0).contains(&self.top_p) || self.top_p == 0.0 {
            return Err(JarvisError::InvalidArgument(
                "local top_p must be in (0, 1]".into(),
            ));
        }
        if self.max_ram_mb < 512 {
            return Err(JarvisError::InvalidArgument(
                "local RAM ceiling must be at least 512 MB".into(),
            ));
        }
        let model_bytes = std::fs::metadata(&self.model_path)
            .map_err(|error| JarvisError::Model(format!("inspect GGUF: {error}")))?
            .len();
        ram_guard(model_bytes, available_ram_mb(), self.max_ram_mb, 512)
    }
}

/// Refuse a model that cannot fit with a fixed 512 MB reserve for the app,
/// Android and the KV cache. `available_mb` is `None` on hosts where the value
/// cannot be read; the static ceiling still applies. Pure so it is testable
/// without loading weights.
pub fn ram_guard(
    model_bytes: u64,
    available_mb: Option<u64>,
    max_ram_mb: u64,
    reserve_mb: u64,
) -> Result<()> {
    if model_bytes == 0 {
        return Err(JarvisError::Model("GGUF model is empty".into()));
    }
    let model_mb = model_bytes.div_ceil(1024 * 1024);
    let required_mb = model_mb.saturating_add(reserve_mb);
    if required_mb > max_ram_mb {
        return Err(JarvisError::Model(format!(
            "insufficientMemory: model needs {required_mb} MB including reserve, ceiling is {max_ram_mb} MB"
        )));
    }
    if let Some(available) = available_mb {
        if required_mb > available {
            return Err(JarvisError::Model(format!(
                "insufficientMemory: model needs {required_mb} MB including reserve, only {available} MB available"
            )));
        }
    }
    Ok(())
}

/// Android/Linux expose `MemAvailable`; on other hosts the static ceiling is
/// still enforced but the dynamic check is skipped.
fn available_ram_mb() -> Option<u64> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let info = std::fs::read_to_string("/proc/meminfo").ok()?;
        for line in info.lines() {
            if let Some(rest) = line.strip_prefix("MemAvailable:") {
                let kb = rest.split_whitespace().next()?.parse::<u64>().ok()?;
                return Some(kb / 1024);
            }
        }
        None
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        None
    }
}

struct LoadedModel {
    backend: LlamaBackend,
    model: LlamaModel,
    cfg: LocalLlmConfig,
    load_ms: f64,
}

/// A loaded llama.cpp model exposed through the same trait as the remote
/// backend. Cloneable handle; the weights are shared, contexts are per-turn.
#[derive(Clone)]
pub struct QwenLocal {
    inner: Arc<LoadedModel>,
}

impl QwenLocal {
    /// Load a GGUF file. This blocks on disk I/O and llama.cpp initialization;
    /// call it from a worker, not from the UI thread.
    pub fn load(cfg: LocalLlmConfig) -> Result<Self> {
        let started = std::time::Instant::now();
        cfg.validate()?;
        let backend = LlamaBackend::init()
            .map_err(|error| JarvisError::Model(format!("llama.cpp backend: {error}")))?;
        let params = LlamaModelParams::default().with_n_gpu_layers(0);
        let model = LlamaModel::load_from_file(&backend, &cfg.model_path, &params)
            .map_err(|error| JarvisError::Model(format!("load GGUF: {error}")))?;
        let load_ms = started.elapsed().as_secs_f64() * 1000.0;
        tracing::info!(
            load_ms,
            path = %cfg.model_path.display(),
            "local GGUF loaded"
        );
        Ok(Self {
            inner: Arc::new(LoadedModel {
                backend,
                model,
                cfg,
                load_ms,
            }),
        })
    }

    pub fn model_path(&self) -> &Path {
        &self.inner.cfg.model_path
    }
}

#[async_trait]
impl LlmBackend for QwenLocal {
    async fn stream_chat(
        &self,
        messages: Vec<ChatMessage>,
        cancel: CancellationToken,
    ) -> Result<DeltaStream> {
        if messages.is_empty() {
            return Err(JarvisError::InvalidArgument(
                "local turn has no messages".into(),
            ));
        }
        let (tx, rx) = mpsc::channel::<Result<LlmDelta>>(32);
        let inner = Arc::clone(&self.inner);
        tokio::task::spawn_blocking(move || {
            if let Err(error) = inner.generate(&messages, &cancel, &tx) {
                let _ = tx.blocking_send(Err(error));
            }
        });
        Ok(Box::pin(stream_from_channel(rx)))
    }

    fn name(&self) -> &'static str {
        "llama.cpp (GGUF)"
    }

    fn load_ms(&self) -> Option<f64> {
        Some(self.inner.load_ms)
    }
}

fn stream_from_channel(
    mut rx: mpsc::Receiver<Result<LlmDelta>>,
) -> impl Stream<Item = Result<LlmDelta>> + Send {
    futures::stream::poll_fn(move |cx| rx.poll_recv(cx))
}

impl LoadedModel {
    fn generate(
        &self,
        messages: &[ChatMessage],
        cancel: &CancellationToken,
        tx: &mpsc::Sender<Result<LlmDelta>>,
    ) -> Result<()> {
        let prompt = self.prompt(messages)?;
        let vocab = self.model.vocab();
        let tokens = vocab.tokenize(prompt.as_bytes(), true, true);
        if tokens.is_empty() {
            return Err(JarvisError::Model(
                "the chat prompt tokenized to nothing".into(),
            ));
        }
        let n_ctx = self.cfg.context_tokens;
        if tokens.len() >= n_ctx as usize {
            return Err(JarvisError::InvalidArgument(format!(
                "prompt is {} tokens, larger than the {n_ctx}-token local context",
                tokens.len()
            )));
        }

        let mut ctx = self
            .model
            .new_context(
                &self.backend,
                LlamaContextParams::default()
                    .with_n_ctx(NonZeroU32::new(n_ctx))
                    .with_n_batch(n_ctx)
                    .with_n_threads(self.cfg.threads)
                    .with_n_threads_batch(self.cfg.threads),
            )
            .map_err(|error| JarvisError::Model(format!("create llama context: {error}")))?;

        let mut batch = LlamaBatch::new(n_ctx as usize, 1);
        let mut n_past = 0i32;
        for (index, token) in tokens.iter().enumerate() {
            if cancel.is_cancelled() {
                return Err(JarvisError::Cancelled);
            }
            let position = i32::try_from(index)
                .map_err(|_| JarvisError::Model("prompt position overflow".into()))?;
            let last = index == tokens.len() - 1;
            batch
                .add(*token, position, &[0], last)
                .map_err(|error| JarvisError::Model(format!("prompt batch: {error}")))?;
            n_past = position + 1;
        }
        ctx.decode(&mut batch)
            .map_err(|error| JarvisError::Model(format!("prompt decode: {error}")))?;

        let mut sampler = LlamaSampler::chain_simple([
            LlamaSampler::temp(self.cfg.temperature),
            LlamaSampler::top_p(self.cfg.top_p, 1),
            LlamaSampler::dist(self.cfg.seed),
        ]);

        let mut generated = 0u32;
        loop {
            if cancel.is_cancelled() {
                return Err(JarvisError::Cancelled);
            }
            if generated >= self.cfg.max_tokens || n_past >= n_ctx as i32 {
                let _ = tx.blocking_send(Ok(LlmDelta::Done {
                    finish_reason: Some("length".into()),
                }));
                return Ok(());
            }

            let token = sampler.sample(&ctx, -1);
            sampler.accept(token);
            if vocab.is_eog(token) {
                let _ = tx.blocking_send(Ok(LlmDelta::Done {
                    finish_reason: Some("stop".into()),
                }));
                return Ok(());
            }

            let piece = vocab.token_to_piece(token, false, None);
            if !piece.is_empty() {
                let text = String::from_utf8_lossy(&piece).into_owned();
                if tx.blocking_send(Ok(LlmDelta::Content(text))).is_err() {
                    return Ok(());
                }
            }

            batch.clear();
            batch
                .add(token, n_past, &[0], true)
                .map_err(|error| JarvisError::Model(format!("token batch: {error}")))?;
            ctx.decode(&mut batch)
                .map_err(|error| JarvisError::Model(format!("token decode: {error}")))?;
            n_past += 1;
            generated += 1;
        }
    }

    fn prompt(&self, messages: &[ChatMessage]) -> Result<String> {
        let mut chat = Vec::with_capacity(messages.len());
        for message in messages {
            let role = match message.role {
                Role::System => "system",
                Role::User => "user",
                Role::Assistant => "assistant",
                Role::Tool => "tool",
            };
            chat.push(
                LlamaChatMessage::new(role.to_string(), message.content.clone())
                    .map_err(|error| JarvisError::Model(format!("chat message: {error}")))?,
            );
        }
        match self.model.chat_template(None) {
            Ok(template) => self
                .model
                .apply_chat_template(&template, &chat, true)
                .map_err(|error| JarvisError::Model(format!("apply chat template: {error}"))),
            Err(ChatTemplateError::MissingTemplate) => Ok(chatml_fallback(messages)),
            Err(error) => Err(JarvisError::Model(format!("model chat template: {error}"))),
        }
    }
}

/// Qwen-family models normally ship a ChatML template. This is only a fallback
/// for a GGUF that omitted it; an incompatible model still fails at load or
/// produces visibly wrong text rather than being silently treated as remote.
fn chatml_fallback(messages: &[ChatMessage]) -> String {
    let mut out = String::new();
    for message in messages {
        let role = match message.role {
            Role::System => "system",
            Role::User => "user",
            Role::Assistant => "assistant",
            Role::Tool => "tool",
        };
        out.push_str("<|im_start|>");
        out.push_str(role);
        out.push('\n');
        out.push_str(&message.content);
        out.push_str("<|im_end|>\n");
    }
    out.push_str("<|im_start|>assistant\n");
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_model_path_is_not_configured() {
        let error = LocalLlmConfig::default().validate().unwrap_err();
        assert!(matches!(error, JarvisError::NotConfigured(_)));
    }

    #[test]
    fn missing_model_file_is_not_configured() {
        let cfg = LocalLlmConfig {
            model_path: PathBuf::from("definitely-not-a-model.gguf"),
            ..LocalLlmConfig::default()
        };
        assert!(matches!(
            cfg.validate().unwrap_err(),
            JarvisError::NotConfigured(_)
        ));
    }

    #[test]
    fn ram_guard_refuses_models_that_cannot_fit() {
        assert!(ram_guard(1024 * 1024 * 900, Some(3_000), 2_048, 512).is_ok());
        let error = ram_guard(1024 * 1024 * 1_700, Some(4_000), 2_048, 512).unwrap_err();
        assert!(
            matches!(error, JarvisError::Model(ref detail) if detail.contains("insufficientMemory"))
        );
        let low = ram_guard(1024 * 1024 * 900, Some(1_200), 2_048, 512).unwrap_err();
        assert!(matches!(low, JarvisError::Model(ref detail) if detail.contains("available")));
    }

    #[test]
    fn empty_model_is_rejected_by_ram_guard() {
        assert!(matches!(
            ram_guard(0, Some(8_000), 2_048, 512).unwrap_err(),
            JarvisError::Model(_)
        ));
    }

    #[test]
    fn invalid_context_and_threads_are_rejected() {
        let base = LocalLlmConfig {
            model_path: PathBuf::from("model.gguf"),
            ..LocalLlmConfig::default()
        };
        assert!(matches!(
            LocalLlmConfig {
                context_tokens: 128,
                ..base.clone()
            }
            .validate()
            .unwrap_err(),
            JarvisError::InvalidArgument(_)
        ));
        assert!(matches!(
            LocalLlmConfig { threads: 0, ..base }
                .validate()
                .unwrap_err(),
            JarvisError::InvalidArgument(_)
        ));
    }
}
