//! LLM abstraction.
//!
//! Qwen is reached through `LlmBackend`, so the remote SSE backend and a future
//! on-device GGUF backend are interchangeable from the engine's point of view.
//!
//! Note on the trait shape: the brief shows `-> Result<impl Stream<...>>`. That
//! signature makes the trait non-object-safe, and the engine needs
//! `Arc<dyn LlmBackend>` to swap backends at runtime (and to test with a fake).
//! We therefore return a boxed `DeltaStream`. The behavioural contract — token
//! delivery as it arrives, cancellation honoured mid-stream — is unchanged.

use std::pin::Pin;

use async_trait::async_trait;
use futures::Stream;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio_util::sync::CancellationToken;

use crate::error::{JarvisError, Result};

#[cfg(feature = "local-llm")]
pub mod local;
pub mod openai_stream;
pub mod remote;

#[cfg(feature = "local-llm")]
pub use local::{LocalLlmConfig, QwenLocal};
pub use openai_stream::OpenAiStreamParser;
pub use remote::{QwenRemote, QwenRemoteConfig};

pub const SYSTEM_PROMPT: &str = r#"You are JARVIS, a private voice assistant running on the user's Android phone.

Persona: concise, dry-witted, formally polite. Never verbose. Answer in under three sentences unless the user explicitly asks you to elaborate. Never say you are an AI language model. Address the user as "sir" sparingly, not every sentence.

Voice rules:
- You are being spoken aloud. Use plain prose. No markdown, no bullet lists, no code blocks, no emoji.
- Numbers, times and dates should be written the way a person says them.
- If you need to show something visual, say "I've put that on screen" rather than reading a URL aloud.

Device control:
- Call exactly one tool at a time unless the user asks for several actions.
- Only call a tool the user actually asked for. Never invent a call to be helpful.
- Outgoing or irreversible actions (calls, SMS, payments, deletions, writing to settings) are gated by the host: emit the tool call and let the host ask the user. Do not ask for confirmation yourself.
- If a tool fails, say what failed in one short sentence and stop.

Mood:
- Optionally begin your reply with exactly one mood tag from this set: <mood>neutral</mood>, <mood>attentive</mood>, <mood>amused</mood>, <mood>concerned</mood>, <mood>working</mood>. The tag is consumed by the UI and never spoken. Omit it when neutral.
"#;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    System,
    User,
    Assistant,
    Tool,
}

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Role::System => "system",
            Role::User => "user",
            Role::Assistant => "assistant",
            Role::Tool => "tool",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: Value,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ChatMessage {
    pub role: Role,
    pub content: String,
    pub tool_calls: Vec<ToolCall>,
    pub tool_call_id: Option<String>,
}

impl ChatMessage {
    pub fn system(content: impl Into<String>) -> Self {
        Self {
            role: Role::System,
            content: content.into(),
            tool_calls: Vec::new(),
            tool_call_id: None,
        }
    }

    pub fn user(content: impl Into<String>) -> Self {
        Self {
            role: Role::User,
            content: content.into(),
            tool_calls: Vec::new(),
            tool_call_id: None,
        }
    }

    pub fn assistant(content: impl Into<String>, tool_calls: Vec<ToolCall>) -> Self {
        Self {
            role: Role::Assistant,
            content: content.into(),
            tool_calls,
            tool_call_id: None,
        }
    }

    pub fn tool_result(tool_call_id: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            role: Role::Tool,
            content: content.into(),
            tool_calls: Vec::new(),
            tool_call_id: Some(tool_call_id.into()),
        }
    }

    fn to_openai(&self) -> Value {
        let mut map = serde_json::Map::new();
        map.insert("role".into(), json!(self.role.as_str()));
        map.insert("content".into(), json!(self.content));
        if !self.tool_calls.is_empty() {
            map.insert(
                "tool_calls".into(),
                Value::Array(
                    self.tool_calls
                        .iter()
                        .map(|call| {
                            json!({
                                "id": call.id,
                                "type": "function",
                                "function": {
                                    "name": call.name,
                                    "arguments": serde_json::to_string(&call.arguments)
                                        .unwrap_or_else(|_| "{}".into()),
                                }
                            })
                        })
                        .collect(),
                ),
            );
        }
        if let Some(id) = &self.tool_call_id {
            map.insert("tool_call_id".into(), json!(id));
        }
        Value::Object(map)
    }
}

/// Tool schema produced by the Kotlin registry and sent to Qwen verbatim.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ToolSchema {
    pub name: String,
    pub description: String,
    pub parameters: Value,
}

impl ToolSchema {
    pub fn to_openai(&self) -> Value {
        json!({
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            }
        })
    }
}

pub fn request_body(
    model: &str,
    messages: &[ChatMessage],
    tools: &[ToolSchema],
    temperature: f32,
    max_tokens: u32,
) -> Value {
    let mut body = json!({
        "model": model,
        "stream": true,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "messages": messages.iter().map(ChatMessage::to_openai).collect::<Vec<_>>(),
    });
    if !tools.is_empty() {
        let map = body.as_object_mut().expect("object literal");
        map.insert(
            "tools".into(),
            Value::Array(tools.iter().map(ToolSchema::to_openai).collect()),
        );
        map.insert("tool_choice".into(), json!("auto"));
    }
    body
}

/// One increment from a backend.
#[derive(Debug, Clone, PartialEq)]
pub enum LlmDelta {
    /// Text fragment, already stripped of control tags.
    Content(String),
    /// Mood tag emitted by the model (see SYSTEM_PROMPT).
    Mood(String),
    /// Complete tool call, emitted once its arguments have fully arrived.
    ToolCall(ToolCall),
    Done {
        finish_reason: Option<String>,
    },
}

pub type DeltaStream = Pin<Box<dyn Stream<Item = Result<LlmDelta>> + Send>>;

#[async_trait]
pub trait LlmBackend: Send + Sync {
    async fn stream_chat(
        &self,
        messages: Vec<ChatMessage>,
        cancel: CancellationToken,
    ) -> Result<DeltaStream>;

    fn name(&self) -> &'static str;

    /// One-time model load cost, when the backend has one. Emitted by the
    /// engine as a latency stage so device benchmarks use measured values.
    fn load_ms(&self) -> Option<f64> {
        None
    }
}

/// Honest placeholder selected when no reasoning backend is available.
///
/// A keyless build still creates the engine: local memory, device tools and
/// text input must work before a key exists. A turn then fails with a typed
/// `NotConfigured` error instead of pretending to reason.
pub struct UnavailableBackend {
    pub reason: String,
}

impl UnavailableBackend {
    pub fn new(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
        }
    }
}

#[async_trait]
impl LlmBackend for UnavailableBackend {
    async fn stream_chat(
        &self,
        _messages: Vec<ChatMessage>,
        _cancel: CancellationToken,
    ) -> Result<DeltaStream> {
        Err(JarvisError::NotConfigured(self.reason.clone()))
    }

    fn name(&self) -> &'static str {
        "unavailable"
    }
}

/// Test/support backend: deterministic token stream. Used by engine tests and
/// by the `--demo` bench binary. Never selected in the Android build.
pub struct ScriptedBackend {
    pub tokens: Vec<String>,
    pub fail_after: Option<usize>,
}

#[async_trait]
impl LlmBackend for ScriptedBackend {
    async fn stream_chat(
        &self,
        _messages: Vec<ChatMessage>,
        cancel: CancellationToken,
    ) -> Result<DeltaStream> {
        let tokens = self.tokens.clone();
        let fail_after = self.fail_after;
        let stream = futures::stream::unfold(0usize, move |index| {
            let tokens = tokens.clone();
            let cancel = cancel.clone();
            async move {
                if cancel.is_cancelled() {
                    return Some((Err(JarvisError::Cancelled), tokens.len().saturating_add(1)));
                }
                if let Some(limit) = fail_after {
                    if index >= limit {
                        return Some((
                            Err(JarvisError::Network("scripted failure".into())),
                            tokens.len().saturating_add(1),
                        ));
                    }
                }
                if index < tokens.len() {
                    return Some((Ok(LlmDelta::Content(tokens[index].clone())), index + 1));
                }
                if index == tokens.len() {
                    return Some((
                        Ok(LlmDelta::Done {
                            finish_reason: Some("stop".into()),
                        }),
                        index + 1,
                    ));
                }
                None
            }
        });
        Ok(Box::pin(stream))
    }

    fn name(&self) -> &'static str {
        "scripted"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_includes_tools_only_when_present() {
        let msgs = vec![ChatMessage::system("s"), ChatMessage::user("hi")];
        let bare = request_body("qwen3", &msgs, &[], 0.4, 256);
        assert!(bare.get("tools").is_none());
        assert_eq!(bare["stream"], true);

        let tools = vec![ToolSchema {
            name: "set_alarm".into(),
            description: "Set an alarm".into(),
            parameters: json!({"type":"object","properties":{}}),
        }];
        let with_tools = request_body("qwen3", &msgs, &tools, 0.4, 256);
        assert_eq!(with_tools["tools"][0]["function"]["name"], "set_alarm");
        assert_eq!(with_tools["tool_choice"], "auto");
    }

    #[test]
    fn assistant_tool_calls_serialize_as_openai() {
        let msg = ChatMessage::assistant(
            "",
            vec![ToolCall {
                id: "call_1".into(),
                name: "get_battery".into(),
                arguments: json!({"verbose": true}),
            }],
        );
        let value = msg.to_openai();
        assert_eq!(value["role"], "assistant");
        assert_eq!(value["tool_calls"][0]["function"]["name"], "get_battery");
        let args = value["tool_calls"][0]["function"]["arguments"]
            .as_str()
            .unwrap();
        assert_eq!(args, r#"{"verbose":true}"#);
    }

    #[test]
    fn tool_result_carries_call_id() {
        let msg = ChatMessage::tool_result("call_9", "42%");
        let value = msg.to_openai();
        assert_eq!(value["role"], "tool");
        assert_eq!(value["tool_call_id"], "call_9");
    }

    #[test]
    fn system_prompt_demands_brevity_and_mood_tag() {
        assert!(SYSTEM_PROMPT.contains("under three sentences"));
        assert!(SYSTEM_PROMPT.contains("<mood>"));
    }
}
