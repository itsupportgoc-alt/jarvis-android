//! Audio ingress.
//!
//! Capture itself is owned by Kotlin (`AudioRecord`, 16 kHz mono f32) because
//! the microphone permission, audio focus and foreground-service lifecycle are
//! Android concerns. The samples are handed to this module through a lock-free
//! SPSC ring buffer: the capture thread only ever writes to `PcmIngress`, the
//! Rust DSP thread only ever reads from the single `Consumer`. No mutex, no
//! allocation and no logging on either side of the hand-off.
//!
//! `PcmIngress` wraps `rtrb::Producer` in an `UnsafeCell` so that `push` can
//! take `&self`. That is sound only under a strict single-producer contract:
//! exactly one Android capture thread may call `push`. The JNI entry point
//! documents and enforces that by construction (one AudioRecord per engine).

use std::cell::UnsafeCell;
use std::sync::Arc;

use rtrb::{Consumer, Producer, RingBuffer};

pub const SAMPLE_RATE: u32 = 16_000;
pub const FRAME_MS: u32 = 20;
pub const FRAME_SAMPLES: usize = (SAMPLE_RATE as usize * FRAME_MS as usize) / 1000;

/// One second of headroom at 16 kHz. If the DSP thread ever stalls this long we
/// drop audio rather than block the capture thread.
pub const RING_CAPACITY: usize = 16_000;

pub struct PcmIngress {
    producer: UnsafeCell<Producer<f32>>,
    dropped: std::sync::atomic::AtomicU64,
}

// SAFETY: `push` is only ever called from the single capture thread. `Producer`
// itself is `Send`; we never expose `&mut` beyond this invariant.
unsafe impl Send for PcmIngress {}
// SAFETY: sharing `&PcmIngress` across threads is sound because every method
// either takes `&self` and touches only the atomic counter, or mutates the
// producer under the single-thread contract above. No other thread ever reads
// or writes the producer concurrently.
unsafe impl Sync for PcmIngress {}

impl PcmIngress {
    /// Push interleaved-but-mono f32 samples. Returns the number accepted.
    pub fn push(&self, samples: &[f32]) -> usize {
        // SAFETY: single-producer invariant documented above.
        let producer = unsafe { &mut *self.producer.get() };
        let mut written = 0;
        for &sample in samples {
            match producer.push(sample) {
                Ok(()) => written += 1,
                Err(_) => break,
            }
        }
        let dropped = samples.len() - written;
        if dropped > 0 {
            self.dropped
                .fetch_add(dropped as u64, std::sync::atomic::Ordering::Relaxed);
        }
        written
    }

    pub fn dropped(&self) -> u64 {
        self.dropped.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// Producer side handed to the host. The consumer is moved into the DSP thread.
pub struct AudioRing {
    ingress: Arc<PcmIngress>,
    consumer: Option<Consumer<f32>>,
}

impl AudioRing {
    pub fn new(capacity: usize) -> Self {
        let (producer, consumer) = RingBuffer::new(capacity.max(FRAME_SAMPLES * 4));
        Self {
            ingress: Arc::new(PcmIngress {
                producer: UnsafeCell::new(producer),
                dropped: std::sync::atomic::AtomicU64::new(0),
            }),
            consumer: Some(consumer),
        }
    }

    pub fn ingress(&self) -> Arc<PcmIngress> {
        self.ingress.clone()
    }

    pub fn take_consumer(&mut self) -> Option<Consumer<f32>> {
        self.consumer.take()
    }
}

/// Fixed-size 20 ms frame assembler. Allocation-free in steady state.
pub struct FrameAssembler {
    frame: [f32; FRAME_SAMPLES],
    len: usize,
}

impl Default for FrameAssembler {
    fn default() -> Self {
        Self {
            frame: [0.0; FRAME_SAMPLES],
            len: 0,
        }
    }
}

impl FrameAssembler {
    /// Returns `true` when a complete frame is available in `frame()`.
    pub fn push(&mut self, sample: f32) -> bool {
        self.frame[self.len] = sample;
        self.len += 1;
        if self.len == FRAME_SAMPLES {
            self.len = 0;
            true
        } else {
            false
        }
    }

    pub fn frame(&self) -> &[f32; FRAME_SAMPLES] {
        &self.frame
    }
}

/// Root-mean-square level of one frame.
pub fn rms(frame: &[f32]) -> f32 {
    if frame.is_empty() {
        return 0.0;
    }
    let sum: f32 = frame.iter().map(|s| s * s).sum();
    (sum / frame.len() as f32).sqrt()
}

/// Map a measured RMS to the 0..1 range the UI consumes. Speech at a normal
/// distance lands near 0.05 .. 0.25 RMS on a phone mic; 6x gain with a soft
/// knee keeps the waveform lively without saturating on plosives.
pub fn visual_level(rms_value: f32) -> f32 {
    let amplified = (rms_value * 6.0).min(1.0);
    // sqrt curve: quiet speech still visibly moves the rings.
    amplified.sqrt()
}

/// Peak level in a buffer, used for the utterance summary event.
pub fn peak(frame: &[f32]) -> f32 {
    frame.iter().fold(0.0_f32, |acc, s| acc.max(s.abs()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_assembler_emits_every_320_samples() {
        let mut a = FrameAssembler::default();
        let mut frames = 0;
        for i in 0..(FRAME_SAMPLES * 3 + 17) {
            if a.push(if i % 2 == 0 { 0.5 } else { -0.5 }) {
                frames += 1;
                assert_eq!(a.frame().len(), FRAME_SAMPLES);
            }
        }
        assert_eq!(frames, 3);
    }

    #[test]
    fn ring_buffer_round_trip_is_lossless_when_drained() {
        let mut ring = AudioRing::new(1024);
        let ingress = ring.ingress();
        let mut consumer = ring.take_consumer().unwrap();
        let input: Vec<f32> = (0..512).map(|i| i as f32 * 0.001).collect();
        assert_eq!(ingress.push(&input), input.len());
        for expected in input {
            assert_eq!(consumer.pop().unwrap(), expected);
        }
        assert_eq!(ingress.dropped(), 0);
    }

    #[test]
    fn overflow_is_counted_not_blocking() {
        let mut ring = AudioRing::new(256);
        let ingress = ring.ingress();
        let _consumer = ring.take_consumer().unwrap();
        let written = ingress.push(&vec![0.1; 3000]);
        assert!(written < 3000, "ring never overflowed: {written}");
        assert_eq!(ingress.dropped(), 3000 - written as u64);
    }

    #[test]
    fn rms_of_silence_is_zero_and_of_dc_is_abs() {
        assert_eq!(rms(&[0.0; 320]), 0.0);
        assert!((rms(&[0.25; 320]) - 0.25).abs() < 1e-6);
    }

    #[test]
    fn visual_level_is_bounded() {
        assert_eq!(visual_level(0.0), 0.0);
        assert!(visual_level(0.02) > 0.2);
        assert_eq!(visual_level(1.0), 1.0);
    }
}
