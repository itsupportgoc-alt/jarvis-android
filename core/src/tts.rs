//! Speech synthesis orchestration.
//!
//! Rust owns *what* is spoken and *when*: tokens are chunked into sentences the
//! moment they complete and each line is handed to the host immediately. The
//! host (Kotlin) owns *how*: it picks the voice engine and the audio session.
//!
//! Voice engine priority implemented by the app:
//! 1. Piper/VITS ONNX via the `local-tts` integration (not compiled here — see
//!    README). Produces PCM, so the face gets true amplitude + spectral visemes.
//! 2. Android `TextToSpeech`, the shipped default. Gives word-range callbacks
//!    but no PCM, so the face uses the documented syllable envelope below.
//!
//! The `TtsEngine` trait is the seam for (1).

use async_trait::async_trait;

use crate::error::{JarvisError, Result};
use crate::sentence::{spoken_form, SentenceChunker, SpeechChunk};
use crate::viseme::syllable_weight;

#[derive(Debug, Clone, PartialEq)]
pub struct SpokenLine {
    /// Clean text for the transcript panel.
    pub text: String,
    /// Text handed to the synthesizer (URLs replaced with a spoken notice).
    pub spoken: String,
    pub question: bool,
    /// Multiplier applied to the voice's base pitch. Questions lift slightly.
    pub pitch_scale: f32,
    pub index: u32,
}

pub struct SpeechBuilder {
    chunker: SentenceChunker,
    speak_urls: bool,
    base_pitch: f32,
    next_index: u32,
}

impl SpeechBuilder {
    pub fn new(max_chars: usize, speak_urls: bool, base_pitch: f32) -> Self {
        Self {
            chunker: SentenceChunker::new(crate::sentence::ChunkerConfig {
                max_chars,
                min_chars: 1,
            }),
            speak_urls,
            base_pitch,
            next_index: 0,
        }
    }

    pub fn push(&mut self, tokens: &str) -> Vec<SpokenLine> {
        self.chunker
            .push_str(tokens)
            .into_iter()
            .map(|chunk| self.line(chunk))
            .collect()
    }

    pub fn finish(&mut self) -> Option<SpokenLine> {
        self.chunker.flush().map(|chunk| self.line(chunk))
    }

    fn line(&mut self, chunk: SpeechChunk) -> SpokenLine {
        let spoken = if self.speak_urls || !chunk.contains_url {
            chunk.text.clone()
        } else {
            spoken_form(&chunk.text)
        };
        let line = SpokenLine {
            text: chunk.text,
            spoken,
            question: chunk.question,
            pitch_scale: prosody(chunk.question, self.base_pitch),
            index: self.next_index,
        };
        self.next_index += 1;
        line
    }
}

/// A question gets a small upward pitch bias; a statement keeps the base.
pub fn prosody(question: bool, base_pitch: f32) -> f32 {
    if question {
        base_pitch * 1.08
    } else {
        base_pitch
    }
}

/// Envelope estimate for platform TTS, which exposes word ranges but no PCM.
///
/// `phase` is 0..1 across the current word's time span. The result is shaped
/// like speech (attack, body, release) and scaled by syllable weight. It is used
/// only to animate the mouth; the orb's speaking glow is driven by the same
/// timeline, and both are labelled "estimated" in settings. Never used for the
/// microphone waveform, which is always measured.
pub fn syllable_envelope(word: &str, phase: f32) -> f32 {
    let phase = phase.clamp(0.0, 1.0);
    let attack = (phase / 0.18).min(1.0);
    let release = ((1.0 - phase) / 0.25).min(1.0);
    let shape = attack.min(release).max(0.0);
    (shape * syllable_weight(word)).clamp(0.0, 1.0)
}

#[async_trait]
pub trait TtsEngine: Send + Sync {
    fn name(&self) -> &'static str;
    /// Synthesize to 16 kHz mono f32 PCM. Used by the Piper path.
    async fn synthesize(&self, text: &str) -> Result<Vec<f32>>;
}

pub struct UnavailableTts {
    pub reason: String,
}

impl Default for UnavailableTts {
    fn default() -> Self {
        Self {
            reason: "No local TTS engine compiled; Android TextToSpeech is used by the app."
                .into(),
        }
    }
}

#[async_trait]
impl TtsEngine for UnavailableTts {
    fn name(&self) -> &'static str {
        "unavailable"
    }

    async fn synthesize(&self, _text: &str) -> Result<Vec<f32>> {
        Err(JarvisError::NotConfigured(self.reason.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emits_lines_in_order_with_indices() {
        let mut b = SpeechBuilder::new(180, false, 1.0);
        let lines = b.push("Good evening. Shall I call her? ");
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].index, 0);
        assert_eq!(lines[1].index, 1);
        assert!(lines[1].question);
        assert!(lines[1].pitch_scale > lines[0].pitch_scale);
        assert!(b.finish().is_none());
    }

    #[test]
    fn urls_are_replaced_for_speech_but_kept_in_text() {
        let mut b = SpeechBuilder::new(400, false, 1.0);
        let lines = b.push("Details at https://example.com/x are on screen. ");
        assert_eq!(lines.len(), 1);
        assert!(lines[0].text.contains("https://example.com/x"));
        assert!(lines[0].spoken.contains("I've put that on screen instead."));
    }

    #[test]
    fn speak_urls_can_be_enabled() {
        let mut b = SpeechBuilder::new(400, true, 1.0);
        let lines = b.push("See https://example.com/x now. ");
        assert!(lines[0].spoken.contains("https://example.com/x"));
    }

    #[test]
    fn envelope_is_bounded_and_peaks_mid_word() {
        let start = syllable_envelope("maria", 0.0);
        let middle = syllable_envelope("maria", 0.5);
        let end = syllable_envelope("maria", 1.0);
        assert!(middle > start && middle > end);
        assert!((0.0..=1.0).contains(&middle));
    }

    #[tokio::test]
    async fn unavailable_tts_is_explicit() {
        let error = UnavailableTts::default().synthesize("hi").await.unwrap_err();
        assert!(matches!(error, JarvisError::NotConfigured(_)));
    }
}
