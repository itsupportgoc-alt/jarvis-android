//! Latency instrumentation.
//!
//! Every utterance carries a `LatencyTrace`. Stages are recorded with a
//! monotonic clock and emitted as `Latency` events so the debug view can show
//! the measured budget rather than a claim.

use std::time::Instant;

use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct LatencySample {
    pub stage: String,
    pub ms: f64,
}

#[derive(Debug)]
pub struct LatencyTrace {
    started: Instant,
    marks: Vec<LatencySample>,
    last: Instant,
}

impl Default for LatencyTrace {
    fn default() -> Self {
        Self::new()
    }
}

impl LatencyTrace {
    pub fn new() -> Self {
        let now = Instant::now();
        Self {
            started: now,
            marks: Vec::with_capacity(8),
            last: now,
        }
    }

    /// Record a stage measured since the previous mark.
    pub fn mark(&mut self, stage: &str) -> f64 {
        let now = Instant::now();
        let ms = now.duration_since(self.last).as_secs_f64() * 1000.0;
        self.last = now;
        if self.marks.len() < 32 {
            self.marks.push(LatencySample {
                stage: stage.to_string(),
                ms,
            });
        }
        ms
    }

    /// Record a stage measured since the trace began.
    pub fn mark_from_start(&mut self, stage: &str) -> f64 {
        let ms = self.started.elapsed().as_secs_f64() * 1000.0;
        if self.marks.len() < 32 {
            self.marks.push(LatencySample {
                stage: stage.to_string(),
                ms,
            });
        }
        ms
    }

    pub fn total_ms(&self) -> f64 {
        self.started.elapsed().as_secs_f64() * 1000.0
    }

    pub fn samples(&self) -> &[LatencySample] {
        &self.marks
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn marks_accumulate_monotonically() {
        let mut t = LatencyTrace::new();
        let a = t.mark("wake");
        std::thread::sleep(std::time::Duration::from_millis(2));
        let b = t.mark("asr");
        assert!(a >= 0.0 && b >= 2.0, "{a} {b}");
        assert_eq!(t.samples().len(), 2);
        assert!(t.total_ms() >= b);
    }
}
