//! JARVIS core.
//!
//! Rust owns the realtime path: PCM hand-off, VAD, wake-word scheduling,
//! streaming ASR, Qwen orchestration, sentence chunking and the session state
//! machine. Kotlin owns Android: permissions, the foreground service, the
//! device-tool registry and Compose UI.
//!
//! The crate is split so the control plane compiles and tests on any host. The
//! optional `whisper` and `local-llm` features pull in native C/C++ toolchains;
//! see `docs/OFFLINE_BUILD.md` for the Android llama.cpp build.

#![deny(clippy::undocumented_unsafe_blocks)]

pub mod asr;
pub mod audio;
pub mod engine;
pub mod error;
pub mod events;
pub mod llm;
pub mod memory;
pub mod metrics;
pub mod sentence;
pub mod session;
pub mod storage;
pub mod tts;
pub mod vad;
pub mod viseme;
pub mod wake;

#[cfg(feature = "jni-bridge")]
pub mod ffi;

pub use engine::{EngineConfig, HostSink, JarvisEngine};
pub use error::{JarvisError, Result};
pub use events::EngineEvent;
pub use session::SessionState;

/// Voice pipeline sample rate. Everything between capture and TTS is 16 kHz mono.
pub const PIPELINE_SAMPLE_RATE: u32 = audio::SAMPLE_RATE;
