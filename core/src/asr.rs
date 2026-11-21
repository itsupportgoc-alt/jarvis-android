//! Speech recognition.
//!
//! `AsrEngine` is the swap point. The Android build can be compiled with
//! `--features whisper` to run whisper.cpp on-device; without it the engine
//! reports `NotConfigured` rather than pretending to transcribe.
//!
//! Streaming design: Whisper is a windowed model, not a true streaming one. We
//! therefore decode a rolling window (up to 4 s) on a background thread while
//! the VAD window is open and emit partial hypotheses, then decode the complete
//! utterance once at speech end. Partials arrive on the engine's dispatcher as
//! `PartialTranscript`; the final pass replaces them with `Transcript`. This is
//! the standard "pseudo-streaming Whisper" pattern; it keeps end-of-speech to
//! final-transcript latency to a single 1-2 s window decode on a mid-range CPU.

use async_trait::async_trait;

use crate::error::{JarvisError, Result};

#[async_trait]
pub trait AsrEngine: Send + Sync {
    fn name(&self) -> &'static str;
    async fn transcribe(&self, pcm: Vec<f32>) -> Result<String>;
}

/// Honest placeholder used when the app is built without a local ASR engine.
/// Text input and the remote brain still work; voice input reports this error.
pub struct UnavailableAsr {
    pub reason: String,
}

impl Default for UnavailableAsr {
    fn default() -> Self {
        Self {
            reason: "No on-device ASR compiled. Rebuild with --features whisper and place \
                     ggml-base.en.bin in the app's models directory, or use text input."
                .into(),
        }
    }
}

#[async_trait]
impl AsrEngine for UnavailableAsr {
    fn name(&self) -> &'static str {
        "unavailable"
    }

    async fn transcribe(&self, _pcm: Vec<f32>) -> Result<String> {
        Err(JarvisError::NotConfigured(self.reason.clone()))
    }
}

#[cfg(feature = "whisper")]
mod whisper_backend {
    use std::path::Path;
    use std::sync::Arc;

    use async_trait::async_trait;
    use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

    use super::{AsrEngine, JarvisError, Result};

    pub struct WhisperAsr {
        ctx: Arc<WhisperContext>,
        language: Option<String>,
    }

    impl WhisperAsr {
        pub fn load(model_path: &Path, language: Option<String>) -> Result<Self> {
            let ctx = WhisperContext::new_with_params(
                model_path
                    .to_str()
                    .ok_or_else(|| JarvisError::InvalidArgument("model path is not UTF-8".into()))?,
                WhisperContextParameters::default(),
            )
            .map_err(|e| JarvisError::Model(format!("whisper load: {e}")))?;
            Ok(Self {
                ctx: Arc::new(ctx),
                language,
            })
        }
    }

    fn decode(
        ctx: &WhisperContext,
        language: Option<&str>,
        pcm: &[f32],
    ) -> std::result::Result<String, String> {
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_single_segment(false);
        if let Some(language) = language {
            params.set_language(Some(language));
        }

        let mut state = ctx.create_state().map_err(|e| e.to_string())?;
        state.full(params, pcm).map_err(|e| e.to_string())?;
        let segments = state.full_n_segments().map_err(|e| e.to_string())?;
        let mut text = String::new();
        for i in 0..segments {
            let segment = state.full_get_segment_text(i).map_err(|e| e.to_string())?;
            text.push_str(&segment);
        }
        Ok(text.trim().to_string())
    }

    #[async_trait]
    impl AsrEngine for WhisperAsr {
        fn name(&self) -> &'static str {
            "whisper-ggml"
        }

        async fn transcribe(&self, pcm: Vec<f32>) -> Result<String> {
            if pcm.len() < 1600 {
                return Err(JarvisError::InvalidArgument(
                    "utterance shorter than 100 ms".into(),
                ));
            }
            let ctx = self.ctx.clone();
            let language = self.language.clone();
            tokio::task::spawn_blocking(move || decode(&ctx, language.as_deref(), &pcm))
                .await
                .map_err(|e| JarvisError::Internal(format!("asr join: {e}")))?
                .map_err(JarvisError::Model)
        }
    }
}

#[cfg(feature = "whisper")]
pub use whisper_backend::WhisperAsr;

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn unavailable_reports_why() {
        let asr = UnavailableAsr::default();
        let error = asr.transcribe(vec![0.0; 3200]).await.unwrap_err();
        assert!(matches!(error, JarvisError::NotConfigured(_)));
        assert!(error.to_string().contains("whisper"));
    }
}
