//! Control-path benchmarks.
//!
//! These measure the Rust stages that run between "speech ended" and "first
//! audio out" and between "token arrived" and "sentence handed to TTS". They are
//! host numbers (x86_64 desktop), not phone numbers. Device-only stages (wake
//! model, Whisper RTF, TTS synthesis) need the models and an ARM device; see
//! LATENCY.md for the method and the reporting template.

use std::hint::black_box;

use criterion::{criterion_group, criterion_main, Criterion, Throughput};
use jarvis_core::audio::{rms, visual_level, FRAME_SAMPLES};
use jarvis_core::llm::openai_stream::OpenAiStreamParser;
use jarvis_core::llm::LlmDelta;
use jarvis_core::sentence::SentenceChunker;
use jarvis_core::tts::SpeechBuilder;
use jarvis_core::vad::{EnergyVad, Vad, VadConfig};
use jarvis_core::viseme::from_pcm;

fn chunker_throughput(c: &mut Criterion) {
    let tokens: Vec<&str> = "The reactor is stable. Shall I run a full diagnostic? \
        It will take about four minutes, and I will keep you informed."
        .split_inclusive(' ')
        .collect();
    let mut group = c.benchmark_group("sentence_chunker");
    group.throughput(Throughput::Elements(tokens.len() as u64));
    group.bench_function("stream_tokens_to_sentences", |b| {
        b.iter(|| {
            let mut chunker = SentenceChunker::default();
            let mut lines = 0;
            for token in &tokens {
                lines += chunker.push_str(black_box(token)).len();
            }
            lines += usize::from(chunker.flush().is_some());
            black_box(lines)
        })
    });
    group.finish();
}

fn sse_parsing(c: &mut Criterion) {
    let events: Vec<String> = (0..40)
        .map(|i| {
            format!(
                r#"{{"choices":[{{"delta":{{"content":"token{i} "}},"index":0}}]}}"#
            )
        })
        .collect();
    let mut group = c.benchmark_group("sse_parser");
    group.throughput(Throughput::Elements(events.len() as u64));
    group.bench_function("parse_delta_batch", |b| {
        b.iter(|| {
            let mut parser = OpenAiStreamParser::new();
            let mut chars = 0;
            for event in &events {
                for delta in parser.push_event(black_box(event)).unwrap() {
                    if let LlmDelta::Content(text) = delta {
                        chars += text.len();
                    }
                }
            }
            let _ = parser.push_event("[DONE]").unwrap();
            black_box(chars)
        })
    });
    group.finish();
}

fn frame_path(c: &mut Criterion) {
    let mut vad = EnergyVad::new(VadConfig::default());
    let frame = [0.03f32; FRAME_SAMPLES];
    let mut group = c.benchmark_group("audio_frame");
    group.throughput(Throughput::Elements(FRAME_SAMPLES as u64));
    group.bench_function("rms_vad_visual_viseme_20ms", |b| {
        b.iter(|| {
            let level = rms(black_box(&frame));
            let out = vad.push_frame(&frame);
            let visual = visual_level(level);
            let mouth = from_pcm(&frame, 16_000.0);
            black_box((out.voiced, visual, mouth.openness))
        })
    });
    group.finish();
}

fn sentence_to_tts(c: &mut Criterion) {
    let mut group = c.benchmark_group("speech_builder");
    group.bench_function("build_three_lines", |b| {
        b.iter(|| {
            let mut builder = SpeechBuilder::new(180, false, 1.0);
            let mut lines = builder
                .push(black_box("Good evening. The reactor is stable. Any orders?"))
                .len();
            lines += usize::from(builder.finish().is_some());
            black_box(lines)
        })
    });
    group.finish();
}

criterion_group!(benches, chunker_throughput, sse_parsing, frame_path, sentence_to_tts);
criterion_main!(benches);
