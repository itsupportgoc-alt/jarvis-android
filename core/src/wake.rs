//! Wake word.
//!
//! The engine consumes `WakeWordEngine`, which runs on 16 kHz 20 ms frames and
//! returns a score when "Jarvis" is heard. The production target is
//! openWakeWord's `hey_jarvis_v0.1.onnx`, which is a real, freely licensed
//! three-stage ONNX pipeline:
//!
//! ```text
//! audio 16 kHz -> melspectrogram.onnx -> embedding_model.onnx -> hey_jarvis_v0.1.onnx
//!                                                  (16 x 96)            score 0..1
//! ```
//!
//! It is deliberately *not* compiled into this tree: it requires ONNX Runtime
//! for Android (`ort`), which must be cross-compiled for aarch64/armv7 and
//! cannot be produced on the authoring machine (no NDK / SDK). Rather than
//! pretend, the engine uses `UnavailableWakeWord`, which reports a precise
//! configuration error, and the app ships push-to-talk plus an always-listening
//! VAD button. See README "Engine status".
//!
//! Model files are fetched by `models/download_models.sh` and are not committed.

use crate::error::{JarvisError, Result};

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WakeHit {
    /// 0..1 confidence from the detector.
    pub score: f32,
    /// Milliseconds since the start of the feed, for latency measurement.
    pub at_ms: u64,
}

pub trait WakeWordEngine: Send {
    /// Feed one 20 ms frame. `Ok(Some(hit))` fires exactly once per activation.
    fn push_frame(&mut self, frame: &[f32]) -> Result<Option<WakeHit>>;
    fn reset(&mut self);
    fn name(&self) -> &'static str;
}

#[derive(Debug, Clone)]
pub struct WakeConfig {
    pub phrase: String,
    pub threshold: f32,
    /// Extra frames required above threshold, to reject a single noisy frame.
    pub confirm_frames: u32,
}

impl Default for WakeConfig {
    fn default() -> Self {
        Self {
            phrase: "hey jarvis".into(),
            threshold: 0.6,
            confirm_frames: 2,
        }
    }
}

/// Honest placeholder. Every call reports how to enable the real detector.
pub struct UnavailableWakeWord {
    pub reason: String,
}

impl Default for UnavailableWakeWord {
    fn default() -> Self {
        Self {
            reason: "Wake word model not present. Build with the openWakeWord ONNX pipeline \
                     (melspectrogram.onnx + embedding_model.onnx + hey_jarvis_v0.1.onnx) and \
                     enable the `silero-vad`/ONNX runtime feature; see README."
                .into(),
        }
    }
}

impl WakeWordEngine for UnavailableWakeWord {
    fn push_frame(&mut self, _frame: &[f32]) -> Result<Option<WakeHit>> {
        Err(JarvisError::NotConfigured(self.reason.clone()))
    }

    fn reset(&mut self) {}

    fn name(&self) -> &'static str {
        "unavailable"
    }
}

/// Deterministic detector used only by tests and the bench harness: fires when
/// a caller-supplied predicate sees the frame. Not shipped in the app.
pub struct TestWakeWord {
    confirm: u32,
    seen: u32,
    fired: bool,
    elapsed_ms: u64,
}

impl Default for TestWakeWord {
    fn default() -> Self {
        Self {
            confirm: 1,
            seen: 0,
            fired: false,
            elapsed_ms: 0,
        }
    }
}

impl WakeWordEngine for TestWakeWord {
    fn push_frame(&mut self, frame: &[f32]) -> Result<Option<WakeHit>> {
        self.elapsed_ms += 20;
        let level = crate::audio::rms(frame);
        if level > 0.5 {
            self.seen += 1;
            if self.seen >= self.confirm && !self.fired {
                self.fired = true;
                return Ok(Some(WakeHit {
                    score: 0.99,
                    at_ms: self.elapsed_ms,
                }));
            }
        } else {
            self.seen = 0;
        }
        Ok(None)
    }

    fn reset(&mut self) {
        self.seen = 0;
        self.fired = false;
    }

    fn name(&self) -> &'static str {
        "test-wake"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio::FRAME_SAMPLES;

    #[test]
    fn unavailable_is_explicit() {
        let error = UnavailableWakeWord::default()
            .push_frame(&[0.0; FRAME_SAMPLES])
            .unwrap_err();
        assert!(matches!(error, JarvisError::NotConfigured(_)));
    }

    #[test]
    fn test_detector_fires_once() {
        let mut w = TestWakeWord::default();
        let loud = [0.9f32; FRAME_SAMPLES];
        assert!(w.push_frame(&loud).unwrap().is_some());
        assert!(w.push_frame(&loud).unwrap().is_none());
    }
}
