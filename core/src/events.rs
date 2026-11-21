//! Events emitted from the Rust engine to the Kotlin host.
//!
//! One JSON object per event, `type`-tagged. Kotlin maps these into a sealed
//! `JarvisEvent` and from there into `StateFlow`s consumed by Compose.
//!
//! All amplitude values are real measurements from the audio path. The UI must
//! never synthesise motion when a `micAmplitude` event is available.

use serde::Serialize;

use crate::error::JarvisError;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum EngineEvent {
    /// Session state changed. `state` is one of idle/listening/thinking/speaking/error.
    State { state: String },
    /// Real 16 kHz mic RMS (0.0..1.0 post-normalisation), ~50 Hz.
    MicAmplitude { rms: f32 },
    /// Playback RMS reported by the host TTS player, ~60 Hz.
    TtsAmplitude { rms: f32 },
    /// VAD opened the utterance window.
    SpeechStart,
    /// The user spoke over JARVIS: the host must stop TTS immediately.
    BargeIn,
    /// VAD closed the utterance window.
    SpeechEnd { duration_ms: u64, peak_rms: f32 },
    /// Streaming ASR hypothesis (partial). Empty string clears the ghost line.
    PartialTranscript { text: String },
    /// Final transcription for the utterance.
    Transcript { text: String },
    /// One token (or token fragment) from Qwen.
    AssistantDelta { text: String },
    /// Qwen finished. `text` is the full reply, already stripped of tags.
    AssistantDone { text: String, mood: String },
    /// A sentence is ready to be spoken. Kotlin enqueues it immediately.
    Speak {
        text: String,
        question: bool,
        index: u32,
    },
    /// Qwen requested a device tool. Kotlin executes it and calls submitToolResult.
    ToolCall {
        id: String,
        name: String,
        arguments: serde_json::Value,
    },
    /// Qwen emitted a mood tag for the character face.
    Mood { mood: String },
    /// Latency instrumentation sample, in milliseconds.
    Latency { stage: String, ms: f64 },
    /// Non-fatal error surfaced to the UI.
    Error { error: JarvisError },
    /// Engine lifecycle, sent when recording starts/stops.
    Engine { running: bool },
    /// Tool trace record (also persisted encrypted by the host).
    ToolTrace {
        tool: String,
        arguments: serde_json::Value,
        result: String,
        ok: bool,
        latency_ms: f64,
    },
}

impl EngineEvent {
    pub fn state(state: crate::session::SessionState) -> Self {
        EngineEvent::State {
            state: state.as_str().to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn events_are_type_tagged() {
        let json = serde_json::to_string(&EngineEvent::Speak {
            text: "Right away, sir.".into(),
            question: false,
            index: 0,
        })
        .unwrap();
        assert!(json.starts_with(r#"{"type":"speak""#), "{json}");
        assert!(json.contains("\"question\":false"));
    }

    #[test]
    fn errors_keep_their_kind() {
        let json = serde_json::to_string(&EngineEvent::Error {
            error: JarvisError::Cancelled,
        })
        .unwrap();
        assert!(json.contains(r#""kind":"cancelled""#));
    }
}
