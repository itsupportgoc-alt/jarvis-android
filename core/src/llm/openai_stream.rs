//! OpenAI-compatible SSE chunk parser.
//!
//! `eventsource-stream` handles framing (event types, multi-line `data:`,
//! comments, CRLF, reconnection ids). This module turns each `data:` payload
//! into `LlmDelta`s and owns the two fiddly parts:
//!
//! * **Tool-call assembly.** Qwen streams `tool_calls[i].function.arguments` as
//!   fragments; they are only valid JSON once the finish chunk arrives.
//! * **Mood tag filtering.** `<mood>amused</mood>` can be split across arbitrary
//!   token boundaries, so a naive `replace` would leak `<mood>` into the
//!   transcript. `TagFilter` holds back only the bytes that could still become
//!   a tag.

use std::collections::BTreeMap;

use serde_json::Value;

use crate::error::{JarvisError, Result};
use crate::llm::{LlmDelta, ToolCall};

#[derive(Debug, Default)]
struct PartialToolCall {
    id: String,
    name: String,
    arguments: String,
}

#[derive(Debug, Default)]
pub struct OpenAiStreamParser {
    tools: BTreeMap<u32, PartialToolCall>,
    filter: TagFilter,
    finished: bool,
}

impl OpenAiStreamParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Parse one SSE `data:` payload. Returns zero or more deltas.
    pub fn push_event(&mut self, data: &str) -> Result<Vec<LlmDelta>> {
        if data.trim() == "[DONE]" {
            return Ok(self.finish(None));
        }
        let value: Value = serde_json::from_str(data)
            .map_err(|e| JarvisError::Network(format!("malformed SSE payload: {e}")))?;

        if let Some(error) = value.get("error") {
            let message = error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("upstream error");
            return Err(JarvisError::Network(message.to_string()));
        }

        let mut deltas = Vec::new();
        let choice = value
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|c| c.first());
        let Some(choice) = choice else {
            return Ok(deltas);
        };

        if let Some(delta) = choice.get("delta") {
            if let Some(content) = delta.get("content").and_then(Value::as_str) {
                let (clean, mood) = self.filter.push(content);
                if !clean.is_empty() {
                    deltas.push(LlmDelta::Content(clean));
                }
                if let Some(mood) = mood {
                    deltas.push(LlmDelta::Mood(mood));
                }
            }
            if let Some(calls) = delta.get("tool_calls").and_then(Value::as_array) {
                for (fallback_index, call) in calls.iter().enumerate() {
                    let index = call
                        .get("index")
                        .and_then(Value::as_u64)
                        .map(|i| i as u32)
                        .unwrap_or(fallback_index as u32);
                    let entry = self.tools.entry(index).or_default();
                    if let Some(id) = call.get("id").and_then(Value::as_str) {
                        entry.id = id.to_string();
                    }
                    if let Some(function) = call.get("function") {
                        if let Some(name) = function.get("name").and_then(Value::as_str) {
                            entry.name.push_str(name);
                        }
                        if let Some(args) = function.get("arguments").and_then(Value::as_str) {
                            entry.arguments.push_str(args);
                        }
                    }
                }
            }
        }

        if let Some(reason) = choice.get("finish_reason").and_then(Value::as_str) {
            deltas.extend(self.finish(Some(reason.to_string())));
        }

        Ok(deltas)
    }

    /// Emit whatever is buffered and mark the stream complete.
    pub fn finish(&mut self, reason: Option<String>) -> Vec<LlmDelta> {
        if self.finished {
            return Vec::new();
        }
        self.finished = true;
        let mut deltas = Vec::new();

        let (tail, mood) = self.filter.finish();
        if !tail.is_empty() {
            deltas.push(LlmDelta::Content(tail));
        }
        if let Some(mood) = mood {
            deltas.push(LlmDelta::Mood(mood));
        }

        for (index, partial) in std::mem::take(&mut self.tools) {
            if partial.name.is_empty() {
                continue;
            }
            let arguments = if partial.arguments.trim().is_empty() {
                Value::Object(Default::default())
            } else {
                serde_json::from_str(&partial.arguments).unwrap_or_else(|_| {
                    // Keep the raw text so the host can show a useful trace
                    // instead of dropping the call silently.
                    Value::String(partial.arguments.clone())
                })
            };
            let id = if partial.id.is_empty() {
                format!("call_{index}")
            } else {
                partial.id
            };
            deltas.push(LlmDelta::ToolCall(ToolCall {
                id,
                name: partial.name,
                arguments,
            }));
        }

        deltas.push(LlmDelta::Done {
            finish_reason: reason,
        });
        deltas
    }

    pub fn is_finished(&self) -> bool {
        self.finished
    }
}

/// Streaming filter for a single `<mood>...</mood>` tag.
#[derive(Debug, Default)]
struct TagFilter {
    pending: String,
    in_tag: bool,
}

const OPEN: &str = "<mood>";
const CLOSE: &str = "</mood>";

impl TagFilter {
    /// Returns (visible_text, mood_if_completed).
    fn push(&mut self, fragment: &str) -> (String, Option<String>) {
        self.pending.push_str(fragment);
        let mut visible = String::new();
        let mut mood = None;

        loop {
            if self.in_tag {
                if let Some(end) = self.pending.find(CLOSE) {
                    let value = self.pending[..end].trim().to_string();
                    self.pending.drain(..end + CLOSE.len());
                    self.in_tag = false;
                    if !value.is_empty() {
                        mood = Some(value);
                    }
                    continue;
                }
                // Runaway tag: not a mood tag after all. Emit it literally so
                // the user never loses text to a malformed prefix.
                if self.pending.len() > 64 {
                    visible.push_str(OPEN);
                    visible.push_str(&std::mem::take(&mut self.pending));
                    self.in_tag = false;
                }
                break;
            }

            if let Some(start) = self.pending.find(OPEN) {
                visible.push_str(&self.pending[..start]);
                self.pending.drain(..start + OPEN.len());
                self.in_tag = true;
                continue;
            }

            // Hold back only bytes that could still become "<mood>".
            let keep = longest_suffix_that_prefixes(&self.pending, OPEN);
            let emit_to = self.pending.len() - keep;
            visible.push_str(&self.pending[..emit_to]);
            self.pending.drain(..emit_to);
            break;
        }

        (visible, mood)
    }

    fn finish(&mut self) -> (String, Option<String>) {
        let rest = std::mem::take(&mut self.pending);
        self.in_tag = false;
        (rest, None)
    }
}

/// Length in bytes of the longest suffix of `s` that is a prefix of `tag`.
fn longest_suffix_that_prefixes(s: &str, tag: &str) -> usize {
    let max = s.len().min(tag.len().saturating_sub(1));
    for len in (1..=max).rev() {
        if !s.is_char_boundary(s.len() - len) {
            continue;
        }
        if tag.starts_with(&s[s.len() - len..]) {
            return len;
        }
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn content(deltas: &[LlmDelta]) -> String {
        deltas
            .iter()
            .filter_map(|d| match d {
                LlmDelta::Content(c) => Some(c.as_str()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn parses_content_and_done() {
        let mut p = OpenAiStreamParser::new();
        let d = p
            .push_event(r#"{"choices":[{"delta":{"content":"Good evening"},"index":0}]}"#)
            .unwrap();
        assert_eq!(content(&d), "Good evening");
        let d = p.push_event("[DONE]").unwrap();
        assert!(matches!(d.last(), Some(LlmDelta::Done { .. })));
        assert!(p.is_finished());
    }

    #[test]
    fn assembles_fragmented_tool_call() {
        let mut p = OpenAiStreamParser::new();
        p.push_event(
            r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"call_contact","arguments":"{\"na"}}]}}]}"#,
        )
        .unwrap();
        p.push_event(
            r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"me\":\"Maria\"}"}}]}}]}"#,
        )
        .unwrap();
        let out = p
            .push_event(r#"{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"#)
            .unwrap();
        let call = out
            .iter()
            .find_map(|d| match d {
                LlmDelta::ToolCall(c) => Some(c),
                _ => None,
            })
            .expect("tool call");
        assert_eq!(call.name, "call_contact");
        assert_eq!(call.arguments["name"], "Maria");
        assert!(matches!(out.last(), Some(LlmDelta::Done { .. })));
    }

    #[test]
    fn moods_split_across_chunks_never_leak() {
        let mut p = OpenAiStreamParser::new();
        let mut visible = String::new();
        let mut moods = Vec::new();
        for chunk in ["<mo", "od>amu", "sed</mo", "od>Good evening, sir."] {
            let data = format!(
                r#"{{"choices":[{{"delta":{{"content":{}}}}}]}}"#,
                serde_json::to_string(chunk).unwrap()
            );
            for delta in p.push_event(&data).unwrap() {
                match delta {
                    LlmDelta::Content(c) => visible.push_str(&c),
                    LlmDelta::Mood(m) => moods.push(m),
                    _ => {}
                }
            }
        }
        assert_eq!(visible, "Good evening, sir.");
        assert_eq!(moods, vec!["amused".to_string()]);
    }

    #[test]
    fn a_lone_angle_bracket_is_not_swallowed() {
        let mut p = OpenAiStreamParser::new();
        let mut visible = String::new();
        for chunk in ["5 < 6", " and 7 > 2"] {
            let data = format!(
                r#"{{"choices":[{{"delta":{{"content":{}}}}}]}}"#,
                serde_json::to_string(chunk).unwrap()
            );
            for delta in p.push_event(&data).unwrap() {
                if let LlmDelta::Content(c) = delta {
                    visible.push_str(&c);
                }
            }
        }
        assert_eq!(visible, "5 < 6 and 7 > 2");
    }

    #[test]
    fn upstream_error_becomes_network_error() {
        let mut p = OpenAiStreamParser::new();
        let err = p
            .push_event(r#"{"error":{"message":"invalid api key"}}"#)
            .unwrap_err();
        assert!(matches!(err, JarvisError::Network(_)));
        assert!(err.to_string().contains("invalid api key"));
    }

    #[test]
    fn malformed_payload_is_an_error() {
        let mut p = OpenAiStreamParser::new();
        assert!(matches!(
            p.push_event("{not json"),
            Err(JarvisError::Network(_))
        ));
    }

    #[test]
    fn keepalive_and_usage_chunks_are_ignored() {
        let mut p = OpenAiStreamParser::new();
        assert!(p.push_event(r#"{"choices":[]}"#).unwrap().is_empty());
        assert!(p
            .push_event(r#"{"usage":{"total_tokens":10},"choices":[]}"#)
            .unwrap()
            .is_empty());
    }

    #[test]
    fn blank_sse_comment_payload_is_not_json() {
        // eventsource-stream does not yield comments as events, but a stray
        // empty data line should fail loudly rather than panic.
        let mut p = OpenAiStreamParser::new();
        assert!(p.push_event("").is_err());
    }

    #[test]
    fn tool_call_with_bad_json_arguments_is_kept_raw() {
        let mut p = OpenAiStreamParser::new();
        p.push_event(
            r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"x","arguments":"{oops"}}]}}]}"#,
        )
        .unwrap();
        let out = p.finish(Some("tool_calls".into()));
        let call = out
            .iter()
            .find_map(|d| match d {
                LlmDelta::ToolCall(c) => Some(c),
                _ => None,
            })
            .unwrap();
        assert_eq!(call.arguments, Value::String("{oops".into()));
    }
}
