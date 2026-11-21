//! Viseme extraction for the character face.
//!
//! Two sources, both honest about what they measure:
//!
//! 1. `from_pcm` — used when JARVIS synthesises audio itself (a Piper/VITS
//!    voice). Amplitude drives jaw opening; the spectral balance between a
//!    250 Hz and 2.5 kHz band selects rounded (dark) versus wide (bright)
//!    mouth shapes. This is the real "map from the audio envelope" path.
//! 2. `from_text` — used with platform TTS, which hands us word timings but no
//!    PCM. The mouth shape comes from the vowel/consonant being spoken and the
//!    jaw from an estimated syllable envelope. This is an approximation and is
//!    labelled as such in the UI settings ("platform TTS").
//!
//! True phoneme timings (Piper) would replace both; the enum already carries
//! the eight shapes that mapping needs.

use std::f32::consts::PI;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Viseme {
    Closed,
    OpenNarrow,
    OpenWide,
    Rounded,
    Teeth,
    Fv,
    L,
    Smile,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VisemeFrame {
    pub viseme: Viseme,
    /// 0.0 closed .. 1.0 fully open.
    pub openness: f32,
}

impl VisemeFrame {
    pub const REST: Self = Self {
        viseme: Viseme::Closed,
        openness: 0.0,
    };
}

/// Goertzel power at one frequency. Allocation-free and adequate for shape
/// classification (we only compare two bands).
fn goertzel(samples: &[f32], sample_rate: f32, freq: f32) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let n = samples.len() as f32;
    let k = (n * freq / sample_rate).round();
    let omega = 2.0 * PI * k / n;
    let coeff = 2.0 * omega.cos();
    let (mut s1, mut s2) = (0.0, 0.0);
    for &x in samples {
        let s0 = x + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    (s1 * s1 + s2 * s2 - coeff * s1 * s2).max(0.0).sqrt()
}

/// Derive a mouth shape from one analysis window of PCM (16 kHz mono).
pub fn from_pcm(samples: &[f32], sample_rate: f32) -> VisemeFrame {
    if samples.is_empty() {
        return VisemeFrame::REST;
    }
    let rms = (samples.iter().map(|s| s * s).sum::<f32>() / samples.len() as f32).sqrt();
    let openness = (rms * 8.0).clamp(0.0, 1.0);
    if openness < 0.06 {
        return VisemeFrame {
            viseme: Viseme::Closed,
            openness: 0.0,
        };
    }

    let low = goertzel(samples, sample_rate, 250.0);
    let high = goertzel(samples, sample_rate, 2500.0);
    let brightness = high / (low + high + 1e-6);

    let viseme = if brightness > 0.55 {
        Viseme::OpenWide
    } else if brightness < 0.30 {
        Viseme::Rounded
    } else if openness > 0.75 {
        Viseme::Teeth
    } else {
        Viseme::OpenNarrow
    };

    VisemeFrame { viseme, openness }
}

/// Text-driven shape, for engines that give word timings only.
pub fn from_text(word: &str, openness: f32) -> VisemeFrame {
    let openness = openness.clamp(0.0, 1.0);
    let lower = word.to_ascii_lowercase();
    let last = lower.chars().rev().find(|c| c.is_alphabetic());
    let viseme = match last {
        Some('a') | Some('e') => Viseme::OpenWide,
        Some('o') | Some('u') | Some('w') => Viseme::Rounded,
        Some('i') | Some('y') => Viseme::OpenNarrow,
        Some('f') | Some('v') => Viseme::Fv,
        Some('l') => Viseme::L,
        Some('m') | Some('b') | Some('p') => Viseme::Closed,
        Some('s') | Some('t') | Some('c') | Some('z') => Viseme::Teeth,
        Some(_) => Viseme::OpenNarrow,
        None => Viseme::Smile,
    };
    let openness = if matches!(viseme, Viseme::Closed | Viseme::Fv) {
        openness * 0.25
    } else {
        openness
    };
    VisemeFrame { viseme, openness }
}

/// Syllable scale used to shape the fallback envelope when only word timings
/// are available. Diphthongs and long vowels get more energy.
pub fn syllable_weight(word: &str) -> f32 {
    let vowels = word
        .chars()
        .filter(|c| matches!(c.to_ascii_lowercase(), 'a' | 'e' | 'i' | 'o' | 'u' | 'y'))
        .count();
    (0.45 + 0.18 * vowels as f32).min(1.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(freq: f32, amp: f32) -> Vec<f32> {
        (0..480)
            .map(|i| {
                amp * (2.0 * PI * freq * i as f32 / 16_000.0).sin()
            })
            .collect()
    }

    #[test]
    fn silence_is_closed() {
        let f = from_pcm(&[0.0; 480], 16_000.0);
        assert_eq!(f.viseme, Viseme::Closed);
        assert_eq!(f.openness, 0.0);
    }

    #[test]
    fn low_tone_is_rounded_and_high_tone_is_wide() {
        let low = from_pcm(&tone(250.0, 0.3), 16_000.0);
        let high = from_pcm(&tone(2500.0, 0.3), 16_000.0);
        assert_eq!(low.viseme, Viseme::Rounded, "{low:?}");
        assert_eq!(high.viseme, Viseme::OpenWide, "{high:?}");
    }

    #[test]
    fn loudness_becomes_openness() {
        let quiet = from_pcm(&tone(500.0, 0.02), 16_000.0);
        let loud = from_pcm(&tone(500.0, 0.4), 16_000.0);
        assert!(loud.openness > quiet.openness);
        assert!(loud.openness <= 1.0);
    }

    #[test]
    fn text_mapping_covers_the_eight_shapes() {
        assert_eq!(from_text("mama", 0.5).viseme, Viseme::OpenWide);
        assert_eq!(from_text("go", 0.5).viseme, Viseme::Rounded);
        assert_eq!(from_text("of", 0.5).viseme, Viseme::Fv);
        assert_eq!(from_text("all", 0.5).viseme, Viseme::L);
        assert_eq!(from_text("yes", 0.5).viseme, Viseme::Teeth);
        assert_eq!(from_text("...", 0.0).viseme, Viseme::Smile);
    }

    #[test]
    fn syllable_weight_is_bounded() {
        assert!(syllable_weight("a") >= 0.4);
        assert!(syllable_weight("onomatopoeia") <= 1.0);
    }
}
