//! Typed error boundary.
//!
//! Internally the engine uses `anyhow` for context. At the FFI boundary every
//! failure is converted into `JarvisError`, which carries a stable numeric code
//! Kotlin can switch on without parsing strings.

use serde::Serialize;

pub type Result<T> = std::result::Result<T, JarvisError>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, thiserror::Error)]
#[serde(tag = "kind", content = "detail", rename_all = "camelCase")]
pub enum JarvisError {
    #[error("not configured: {0}")]
    NotConfigured(String),
    #[error("permission denied: {0}")]
    PermissionDenied(String),
    #[error("invalid argument: {0}")]
    InvalidArgument(String),
    #[error("audio failure: {0}")]
    Audio(String),
    #[error("network failure: {0}")]
    Network(String),
    #[error("model failure: {0}")]
    Model(String),
    #[error("cancelled")]
    Cancelled,
    #[error("internal failure: {0}")]
    Internal(String),
}

impl JarvisError {
    /// Stable JNI error codes. Never renumber these.
    pub fn code(&self) -> i32 {
        match self {
            JarvisError::NotConfigured(_) => 10,
            JarvisError::PermissionDenied(_) => 11,
            JarvisError::InvalidArgument(_) => 12,
            JarvisError::Audio(_) => 20,
            JarvisError::Network(_) => 21,
            JarvisError::Model(_) => 22,
            JarvisError::Cancelled => 30,
            JarvisError::Internal(_) => 40,
        }
    }

    /// Map any internal error to the closest typed variant. Network and model
    /// errors are tagged where they are produced so this is only a last resort.
    pub fn from_anyhow(error: &anyhow::Error) -> Self {
        if error.downcast_ref::<JarvisError>().is_some() {
            return error
                .downcast_ref::<JarvisError>()
                .cloned()
                .unwrap_or(JarvisError::Internal("unknown".into()));
        }
        if let Some(io) = error.downcast_ref::<std::io::Error>() {
            return JarvisError::Audio(io.to_string());
        }
        JarvisError::Internal(format!("{error:#}"))
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self)
            .unwrap_or_else(|_| r#"{"kind":"internal","detail":"unserializable error"}"#.to_string())
    }
}

impl From<anyhow::Error> for JarvisError {
    fn from(value: anyhow::Error) -> Self {
        JarvisError::from_anyhow(&value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_stable() {
        assert_eq!(JarvisError::Cancelled.code(), 30);
        assert_eq!(JarvisError::NotConfigured("x".into()).code(), 10);
        assert_eq!(JarvisError::Network("x".into()).code(), 21);
    }

    #[test]
    fn anyhow_round_trip() {
        let err = anyhow::anyhow!(JarvisError::Model("bad gguf".into()));
        let mapped = JarvisError::from(err);
        assert_eq!(mapped, JarvisError::Model("bad gguf".into()));
    }

    #[test]
    fn serializes_with_kind_and_detail() {
        let json = JarvisError::PermissionDenied("mic".into()).to_json();
        assert!(json.contains("\"kind\":\"permissionDenied\""));
        assert!(json.contains("\"detail\":\"mic\""));
    }
}
