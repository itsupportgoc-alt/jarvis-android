//! Remote reasoning over an OpenAI-compatible streaming endpoint.
//!
//! Endpoint shape: `POST {base_url}/chat/completions` with `stream: true`.
//! Verified against the DashScope compatible-mode API and the BlackPig relay
//! (`https://api.vv666.ai/v1`); any OpenAI-compatible host works by changing
//! `base_url` and the model IDs.
//!
//! Latency rules:
//! * One long-lived `reqwest::Client` (connection reuse, TLS session reuse).
//! * Deltas are forwarded the moment an SSE event decodes; nothing is buffered.
//! * `CancellationToken` is raced against the next event, so interrupt/barge-in
//!   tears the HTTP body down immediately.
//! * If the primary model is unknown to the account, one retry with the
//!   fallback model is attempted *before* any token is emitted.

use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use async_trait::async_trait;
use eventsource_stream::Eventsource;
use futures::{Stream, StreamExt};
use serde::{Deserialize, Serialize};
use tokio_util::sync::CancellationToken;

use crate::error::{JarvisError, Result};
use crate::llm::openai_stream::OpenAiStreamParser;
use crate::llm::{
    request_body, ChatMessage, DeltaStream, LlmBackend, LlmDelta, ToolSchema, SYSTEM_PROMPT,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QwenRemoteConfig {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    pub fallback_model: String,
    #[serde(default = "default_temperature")]
    pub temperature: f32,
    #[serde(default = "default_max_tokens")]
    pub max_tokens: u32,
    /// Extra persona text appended to the built-in system prompt (voice presets
    /// can nudge brevity, formality, etc.).
    #[serde(default)]
    pub persona_extra: String,
}

fn default_temperature() -> f32 {
    0.4
}
fn default_max_tokens() -> u32 {
    512
}

impl Default for QwenRemoteConfig {
    fn default() -> Self {
        Self {
            base_url: "https://api.vv666.ai/v1".into(),
            api_key: String::new(),
            model: "gpt-6.1-sol".into(),
            fallback_model: "gpt-5.6-sol".into(),
            temperature: default_temperature(),
            max_tokens: default_max_tokens(),
            persona_extra: String::new(),
        }
    }
}

pub struct QwenRemote {
    http: reqwest::Client,
    cfg: QwenRemoteConfig,
    tools: Vec<ToolSchema>,
}

impl QwenRemote {
    pub fn new(cfg: QwenRemoteConfig, tools: Vec<ToolSchema>) -> Result<Self> {
        if cfg.api_key.trim().is_empty() {
            return Err(JarvisError::NotConfigured(
                "API key is empty; add it in Settings".into(),
            ));
        }
        if cfg.base_url.trim().is_empty() {
            return Err(JarvisError::NotConfigured(
                "Base URL is empty".into(),
            ));
        }
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(6))
            .pool_idle_timeout(Duration::from_secs(90))
            .build()
            .map_err(|e| JarvisError::Network(format!("http client: {e}")))?;
        Ok(Self { http, cfg, tools })
    }

    pub fn config(&self) -> &QwenRemoteConfig {
        &self.cfg
    }

    fn endpoint(&self) -> String {
        format!(
            "{}/chat/completions",
            self.cfg.base_url.trim_end_matches('/')
        )
    }

    fn messages_with_system(&self, messages: Vec<ChatMessage>) -> Vec<ChatMessage> {
        let mut all = Vec::with_capacity(messages.len() + 1);
        let mut system = SYSTEM_PROMPT.to_string();
        if !self.cfg.persona_extra.trim().is_empty() {
            system.push_str("\nAdditional style guidance: ");
            system.push_str(self.cfg.persona_extra.trim());
        }
        all.push(ChatMessage::system(system));
        all.extend(messages);
        all
    }

    async fn send_once(
        &self,
        model: &str,
        messages: &[ChatMessage],
        cancel: &CancellationToken,
    ) -> Result<reqwest::Response> {
        let body = request_body(
            model,
            messages,
            &self.tools,
            self.cfg.temperature,
            self.cfg.max_tokens,
        );
        let request = self
            .http
            .post(self.endpoint())
            .bearer_auth(&self.cfg.api_key)
            .header(reqwest::header::ACCEPT, "text/event-stream")
            .json(&body);

        let response = tokio::select! {
            _ = cancel.cancelled() => return Err(JarvisError::Cancelled),
            response = request.send() => response.map_err(|e| {
                if e.is_timeout() {
                    JarvisError::Network("Reasoning request timed out".into())
                } else if e.is_connect() {
                    JarvisError::Network(format!("cannot reach the reasoning endpoint: {e}"))
                } else {
                    JarvisError::Network(e.to_string())
                }
            })?,
        };

        if response.status().is_success() {
            return Ok(response);
        }

        let status = response.status();
        let text = response.text().await.unwrap_or_default();
        let detail = extract_error(&text).unwrap_or_else(|| text.chars().take(300).collect());
        Err(JarvisError::Network(format!("HTTP {status}: {detail}")))
    }
}

fn extract_error(body: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(body).ok()?;
    value
        .get("error")
        .and_then(|e| e.get("message"))
        .and_then(|m| m.as_str())
        .map(str::to_string)
        .or_else(|| value.get("message").and_then(|m| m.as_str()).map(str::to_string))
}

fn looks_like_unknown_model(error: &JarvisError) -> bool {
    match error {
        JarvisError::Network(message) => {
            message.contains("HTTP 404")
                || message.contains("model_not_found")
                || message.contains("Model not found")
                || message.contains("does not exist")
        }
        _ => false,
    }
}

#[async_trait]
impl LlmBackend for QwenRemote {
    async fn stream_chat(
        &self,
        messages: Vec<ChatMessage>,
        cancel: CancellationToken,
    ) -> Result<DeltaStream> {
        let messages = self.messages_with_system(messages);

        let response = match self.send_once(&self.cfg.model, &messages, &cancel).await {
            Ok(response) => response,
            Err(first) => {
                if looks_like_unknown_model(&first)
                    && !self.cfg.fallback_model.trim().is_empty()
                    && self.cfg.fallback_model != self.cfg.model
                {
                    self.send_once(&self.cfg.fallback_model, &messages, &cancel)
                        .await?
                } else {
                    return Err(first);
                }
            }
        };

        let (tx, rx) = tokio::sync::mpsc::channel::<Result<LlmDelta>>(32);
        let cancel_for_task = cancel.clone();

        tokio::spawn(async move {
            let mut events = response.bytes_stream().eventsource();
            let mut parser = OpenAiStreamParser::new();

            loop {
                let next = tokio::select! {
                    _ = cancel_for_task.cancelled() => {
                        let _ = tx.send(Err(JarvisError::Cancelled)).await;
                        break;
                    }
                    item = events.next() => item,
                };

                match next {
                    None => {
                        for delta in parser.finish(None) {
                            let _ = tx.send(Ok(delta)).await;
                        }
                        break;
                    }
                    Some(Err(error)) => {
                        let _ = tx
                            .send(Err(JarvisError::Network(format!("SSE: {error}"))))
                            .await;
                        break;
                    }
                    Some(Ok(event)) => {
                        if event.data.trim().is_empty() {
                            continue;
                        }
                        match parser.push_event(&event.data) {
                            Ok(deltas) => {
                                for delta in deltas {
                                    if tx.send(Ok(delta)).await.is_err() {
                                        return;
                                    }
                                }
                                if parser.is_finished() {
                                    break;
                                }
                            }
                            Err(error) => {
                                let _ = tx.send(Err(error)).await;
                                break;
                            }
                        }
                    }
                }
            }
        });

        Ok(Box::pin(MpscDeltaStream { rx }))
    }

    fn name(&self) -> &'static str {
        "qwen-remote"
    }
}

/// Adapts the pump task's channel to the `Stream` the engine consumes.
struct MpscDeltaStream {
    rx: tokio::sync::mpsc::Receiver<Result<LlmDelta>>,
}

impl Stream for MpscDeltaStream {
    type Item = Result<LlmDelta>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.rx.poll_recv(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> QwenRemoteConfig {
        QwenRemoteConfig {
            api_key: "sk-test".into(),
            ..QwenRemoteConfig::default()
        }
    }

    #[test]
    fn empty_key_is_not_configured() {
        let error = QwenRemote::new(QwenRemoteConfig::default(), vec![]).err().unwrap();
        assert!(matches!(error, JarvisError::NotConfigured(_)));
    }

    #[test]
    fn endpoint_trims_trailing_slash() {
        let backend = QwenRemote::new(
            QwenRemoteConfig {
                base_url: "https://example.com/v1/".into(),
                ..cfg()
            },
            vec![],
        )
        .unwrap();
        assert_eq!(backend.endpoint(), "https://example.com/v1/chat/completions");
    }

    #[test]
    fn persona_extra_is_appended_not_replacing() {
        let backend = QwenRemote::new(
            QwenRemoteConfig {
                persona_extra: "Use shorter sentences.".into(),
                ..cfg()
            },
            vec![],
        )
        .unwrap();
        let messages = backend.messages_with_system(vec![ChatMessage::user("hi")]);
        assert_eq!(messages.len(), 2);
        assert!(messages[0].content.contains("Use shorter sentences."));
        assert!(messages[0].content.contains("JARVIS"));
    }

    #[test]
    fn unknown_model_is_detected() {
        assert!(looks_like_unknown_model(&JarvisError::Network(
            "HTTP 404 Not Found: model_not_found".into()
        )));
        assert!(!looks_like_unknown_model(&JarvisError::Network(
            "Qwen HTTP 500 Internal Server Error".into()
        )));
    }
}
