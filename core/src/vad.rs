//! Voice activity detection.
//!
//! The engine talks to `Vad` so a Silero ONNX detector can be dropped in
//! without touching the session machine. The default `EnergyVad` is fully
//! offline, allocation-free and adaptive: it tracks the noise floor during
//! silence, opens after two consecutive voiced 20 ms frames and closes after
//! `trailing_silence_ms` of quiet, with a hard 15 s cap.
//!
//! Silero was not compiled into this build (it needs an ONNX Runtime for
//! Android, which this machine cannot produce); see DESIGN.md. The interface is
//! ready for it and `EngineConfig::vad` selects the backend.

use serde::{Deserialize, Serialize};

use crate::audio::{rms, FRAME_MS, FRAME_SAMPLES};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VadConfig {
    /// Absolute RMS floor, so a silent room cannot open the mic on noise alone.
    pub threshold: f32,
    /// Multiplier applied to the tracked noise floor.
    pub noise_multiplier: f32,
    /// Quiet time that closes an utterance.
    pub trailing_silence_ms: u32,
    /// Hard cap on one utterance.
    pub max_utterance_ms: u32,
    /// Consecutive voiced frames required to open.
    pub start_frames: u32,
}

impl Default for VadConfig {
    fn default() -> Self {
        Self {
            threshold: 0.012,
            noise_multiplier: 3.0,
            trailing_silence_ms: 700,
            max_utterance_ms: 15_000,
            start_frames: 2,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct VadOutput {
    pub voiced: bool,
    pub started: bool,
    pub ended: bool,
    pub rms: f32,
    /// Milliseconds since the utterance opened (0 when not speaking).
    pub utterance_ms: u32,
}

pub trait Vad: Send {
    fn push_frame(&mut self, frame: &[f32]) -> VadOutput;
    fn reset(&mut self);
    fn is_speaking(&self) -> bool;
    fn name(&self) -> &'static str;
}

#[derive(Debug)]
pub struct EnergyVad {
    cfg: VadConfig,
    noise_floor: f32,
    run: u32,
    silence_ms: u32,
    utterance_ms: u32,
    speaking: bool,
    peak_rms: f32,
}

impl EnergyVad {
    pub fn new(cfg: VadConfig) -> Self {
        Self {
            cfg,
            noise_floor: 0.004,
            run: 0,
            silence_ms: 0,
            utterance_ms: 0,
            speaking: false,
            peak_rms: 0.0,
        }
    }

    fn threshold(&self) -> f32 {
        (self.noise_floor * self.cfg.noise_multiplier).max(self.cfg.threshold)
    }

    pub fn peak_rms(&self) -> f32 {
        self.peak_rms
    }
}

impl Vad for EnergyVad {
    fn push_frame(&mut self, frame: &[f32]) -> VadOutput {
        debug_assert_eq!(frame.len(), FRAME_SAMPLES);
        let level = rms(frame);
        let threshold = self.threshold();
        let voiced = level > threshold;

        let mut out = VadOutput {
            voiced,
            rms: level,
            ..Default::default()
        };

        if !self.speaking {
            // Track the noise floor only while idle, and only with a slow
            // coefficient so a passing noise does not desensitise the mic.
            if !voiced {
                self.noise_floor = 0.97 * self.noise_floor + 0.03 * level;
                self.run = 0;
            } else {
                self.run += 1;
                if self.run >= self.cfg.start_frames {
                    self.speaking = true;
                    self.silence_ms = 0;
                    self.utterance_ms = 0;
                    self.peak_rms = level;
                    out.started = true;
                    out.utterance_ms = 0;
                }
            }
        } else {
            self.utterance_ms += FRAME_MS;
            out.utterance_ms = self.utterance_ms;
            self.peak_rms = self.peak_rms.max(level);

            if voiced {
                self.silence_ms = 0;
            } else {
                self.silence_ms += FRAME_MS;
            }

            let quiet_enough = self.silence_ms >= self.cfg.trailing_silence_ms;
            let too_long = self.utterance_ms >= self.cfg.max_utterance_ms;
            if quiet_enough || too_long {
                self.speaking = false;
                self.run = 0;
                out.ended = true;
                out.utterance_ms = self.utterance_ms;
            }
        }

        out
    }

    fn reset(&mut self) {
        self.run = 0;
        self.silence_ms = 0;
        self.utterance_ms = 0;
        self.speaking = false;
        self.peak_rms = 0.0;
    }

    fn is_speaking(&self) -> bool {
        self.speaking
    }

    fn name(&self) -> &'static str {
        "energy-vad"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quiet() -> [f32; FRAME_SAMPLES] {
        [0.0; FRAME_SAMPLES]
    }

    fn speech(level: f32) -> [f32; FRAME_SAMPLES] {
        [level; FRAME_SAMPLES]
    }

    fn feed(vad: &mut EnergyVad, frames: u32, frame: &[f32; FRAME_SAMPLES]) -> Vec<VadOutput> {
        (0..frames).map(|_| vad.push_frame(frame)).collect()
    }

    #[test]
    fn opens_after_two_voiced_frames() {
        let mut vad = EnergyVad::new(VadConfig::default());
        let out = feed(&mut vad, 2, &speech(0.2));
        assert!(!out[0].started);
        assert!(out[1].started);
        assert!(vad.is_speaking());
    }

    #[test]
    fn closes_after_trailing_silence() {
        let mut vad = EnergyVad::new(VadConfig::default());
        feed(&mut vad, 5, &speech(0.2));
        // 700 ms of trailing silence == 35 frames, ending on the 35th.
        let out = feed(&mut vad, 35, &quiet());
        assert!(!out[33].ended);
        assert!(out[34].ended);
        assert!(!vad.is_speaking());
    }

    #[test]
    fn a_cough_does_not_open_the_mic() {
        let mut vad = EnergyVad::new(VadConfig::default());
        let out = vad.push_frame(&speech(0.2));
        assert!(!out.started);
        assert!(!vad.is_speaking());
    }

    #[test]
    fn max_utterance_forces_end() {
        let mut vad = EnergyVad::new(VadConfig {
            max_utterance_ms: 200,
            ..VadConfig::default()
        });
        feed(&mut vad, 2, &speech(0.3));
        let out = feed(&mut vad, 10, &speech(0.3));
        assert!(out.last().unwrap().ended, "hard cap should close");
    }

    #[test]
    fn noise_floor_adapts_to_a_hum() {
        let mut vad = EnergyVad::new(VadConfig::default());
        // A steady 0.010 hum sits under the absolute floor, so it is treated as
        // noise; the tracked floor rises toward it and raises the threshold.
        assert!(!vad.push_frame(&speech(0.010)).voiced);
        for _ in 0..600 {
            vad.push_frame(&speech(0.010));
        }
        let threshold = vad.threshold();
        assert!(threshold > 0.02, "floor did not adapt: {threshold}");
        // Speech well above the adapted floor still opens immediately.
        assert!(!vad.push_frame(&speech(0.3)).started);
        assert!(vad.push_frame(&speech(0.3)).started);
    }
}
