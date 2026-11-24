//! Encrypted local memory.
//!
//! The transcript log in `storage.rs` is an append-only audit trail. This module
//! is the memory engine: structured records with a kind, importance, tags and an
//! expiry, written to an AES-256-GCM journal under the same Keystore-derived key.
//!
//! File format (one base64 line per record):
//!
//! ```text
//! line 0 : {"header":{"version":1,"createdAt":...}}
//! line n : {"record":{...MemoryRecord...}}
//! ```
//!
//! Each line is `nonce (12 bytes) || ciphertext`, base64-encoded. The AEAD
//! associated data binds the magic, schema version and line index:
//!
//! ```text
//! jarvis-memory:1:<index>
//! ```
//!
//! so reordering, truncating or splicing records fails authentication instead of
//! silently changing history. A corrupt line after a valid header is skipped and
//! counted; only a missing/invalid header is fatal.
//!
//! Policy:
//! * conversation turns are stored as `short_term` with low importance, never
//!   auto-promoted to `long_term`;
//! * `long_term`, `preference` and `goal` records require explicit user approval;
//! * credential-looking text is refused outright;
//! * expired records are excluded from retrieval and dropped by compaction;
//! * duplicate text of the same kind updates the existing record.

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine as _;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::error::{JarvisError, Result};

const MAGIC: &str = "jarvis-memory";
const VERSION: u32 = 1;
const NONCE_LEN: usize = 12;
const MAX_TEXT_CHARS: usize = 4_000;
const MAX_TAGS: usize = 16;

/// Memory categories. Serde keeps these stable for the Kotlin command channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MemoryKind {
    Working,
    ShortTerm,
    LongTerm,
    Preference,
    Task,
    Goal,
    Error,
    Learning,
}

impl MemoryKind {
    pub fn as_str(self) -> &'static str {
        match self {
            MemoryKind::Working => "working",
            MemoryKind::ShortTerm => "short_term",
            MemoryKind::LongTerm => "long_term",
            MemoryKind::Preference => "preference",
            MemoryKind::Task => "task",
            MemoryKind::Goal => "goal",
            MemoryKind::Error => "error",
            MemoryKind::Learning => "learning",
        }
    }

    fn default_importance(self) -> f32 {
        match self {
            MemoryKind::Working => 0.2,
            MemoryKind::ShortTerm => 0.35,
            MemoryKind::LongTerm => 0.8,
            MemoryKind::Preference => 0.8,
            MemoryKind::Task => 0.5,
            MemoryKind::Goal => 0.7,
            MemoryKind::Error => 0.6,
            MemoryKind::Learning => 0.6,
        }
    }

    /// Working memory expires fast; task history is kept for a quarter. Items
    /// the user explicitly approved do not expire.
    fn ttl_secs(self) -> Option<u64> {
        match self {
            MemoryKind::Working => Some(60 * 60),
            MemoryKind::ShortTerm => Some(7 * 24 * 60 * 60),
            MemoryKind::Task | MemoryKind::Error | MemoryKind::Learning => {
                Some(90 * 24 * 60 * 60)
            }
            MemoryKind::LongTerm | MemoryKind::Preference | MemoryKind::Goal => None,
        }
    }

    /// Permanent memory always needs an explicit user act.
    fn requires_approval(self) -> bool {
        matches!(
            self,
            MemoryKind::LongTerm | MemoryKind::Preference | MemoryKind::Goal
        )
    }
}

/// One memory record. `hash` is derived from the kind and normalised text and is
/// used for deduplication, not as a security primitive.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryRecord {
    pub id: String,
    pub kind: MemoryKind,
    pub text: String,
    pub source: String,
    pub importance: f32,
    pub created_at: u64,
    pub updated_at: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<u64>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub hash: String,
    #[serde(default)]
    pub confidence: f32,
    #[serde(default)]
    pub user_approved: bool,
    #[serde(default)]
    pub deleted: bool,
}

/// Input for a new record.
#[derive(Debug, Clone)]
pub struct RememberRequest {
    pub kind: MemoryKind,
    pub text: String,
    pub source: String,
    pub importance: Option<f32>,
    pub tags: Vec<String>,
    pub expires_at: Option<u64>,
    pub confidence: f32,
    pub user_approved: bool,
}

impl RememberRequest {
    pub fn conversation(kind: MemoryKind, text: impl Into<String>) -> Self {
        Self {
            kind,
            text: text.into(),
            source: "conversation".into(),
            importance: None,
            tags: Vec::new(),
            expires_at: None,
            confidence: 0.9,
            user_approved: false,
        }
    }

    pub fn tool(tool: &str, text: impl Into<String>, ok: bool) -> Self {
        Self {
            kind: if ok { MemoryKind::Task } else { MemoryKind::Error },
            text: text.into(),
            source: "tool".into(),
            importance: None,
            tags: vec![tool.to_string()],
            expires_at: None,
            confidence: 1.0,
            user_approved: false,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryStats {
    pub total: usize,
    pub active: usize,
    pub expired: usize,
    pub corrupt: usize,
    pub by_kind: HashMap<String, usize>,
}

struct State {
    records: Vec<MemoryRecord>,
    next_index: u64,
    corrupt: usize,
}

/// The encrypted, append-only memory journal.
pub struct MemoryStore {
    cipher: Aes256Gcm,
    path: PathBuf,
    state: Mutex<State>,
}

impl MemoryStore {
    pub fn open(key: &[u8], path: impl AsRef<Path>) -> Result<Self> {
        if key.len() != 32 {
            return Err(JarvisError::InvalidArgument(format!(
                "memory key must be 32 bytes, got {}",
                key.len()
            )));
        }
        let cipher = Aes256Gcm::new_from_slice(key)
            .map_err(|e| JarvisError::Internal(format!("memory cipher: {e}")))?;
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| JarvisError::Internal(format!("create {parent:?}: {e}")))?;
        }
        let state = if path.exists() {
            load(&cipher, &path)?
        } else {
            write_header(&cipher, &path)?;
            State {
                records: Vec::new(),
                next_index: 1,
                corrupt: 0,
            }
        };
        Ok(Self {
            cipher,
            path,
            state: Mutex::new(state),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Add a record, updating the existing one when kind + normalised text match.
    /// Returns the stored record and whether it deduplicated an existing one.
    pub fn remember(&self, request: RememberRequest) -> Result<(MemoryRecord, bool)> {
        let text = request.text.trim();
        if text.is_empty() {
            return Err(JarvisError::InvalidArgument(
                "memory text is empty".into(),
            ));
        }
        if text.chars().count() > MAX_TEXT_CHARS {
            return Err(JarvisError::InvalidArgument(format!(
                "memory text exceeds {MAX_TEXT_CHARS} characters"
            )));
        }
        if looks_like_secret(text) {
            return Err(JarvisError::InvalidArgument(
                "memory refuses to store passwords, tokens or API keys".into(),
            ));
        }
        if request.kind.requires_approval() && !request.user_approved {
            return Err(JarvisError::PermissionDenied(
                "permanent memory needs explicit user approval".into(),
            ));
        }
        let now = unix_secs();
        let hash = content_hash(request.kind, text);
        let mut state = self.state.lock();

        if let Some(existing) = state
            .records
            .iter_mut()
            .filter(|r| !r.deleted && r.kind == request.kind && r.hash == hash)
            .min_by_key(|r| r.created_at)
        {
            existing.text = text.to_string();
            existing.updated_at = now;
            existing.importance = request
                .importance
                .unwrap_or(existing.importance)
                .clamp(0.0, 1.0);
            if !request.tags.is_empty() {
                existing.tags = dedup_tags(request.tags);
            }
            if let Some(expires) = request.expires_at {
                existing.expires_at = Some(expires);
            }
            existing.confidence = request.confidence.clamp(0.0, 1.0);
            existing.user_approved |= request.user_approved;
            let updated = existing.clone();
            append_record(&self.cipher, &self.path, &mut state, &updated)?;
            return Ok((updated, true));
        }

        let record = MemoryRecord {
            id: new_id(now),
            kind: request.kind,
            text: text.to_string(),
            source: if request.source.trim().is_empty() {
                "conversation".into()
            } else {
                request.source.trim().to_string()
            },
            importance: request
                .importance
                .unwrap_or_else(|| request.kind.default_importance())
                .clamp(0.0, 1.0),
            created_at: now,
            updated_at: now,
            expires_at: request
                .expires_at
                .or_else(|| request.kind.ttl_secs().map(|ttl| now + ttl)),
            tags: dedup_tags(request.tags),
            hash,
            confidence: request.confidence.clamp(0.0, 1.0),
            user_approved: request.user_approved,
            deleted: false,
        };
        append_record(&self.cipher, &self.path, &mut state, &record)?;
        Ok((record, false))
    }

    /// Active records, newest first.
    pub fn list(&self, limit: usize) -> Vec<MemoryRecord> {
        let now = unix_secs();
        let state = self.state.lock();
        let mut out: Vec<MemoryRecord> = state
            .records
            .iter()
            .filter(|r| is_active(r, now))
            .cloned()
            .collect();
        out.sort_by_key(|record| std::cmp::Reverse(record.updated_at));
        out.truncate(limit.max(1));
        out
    }

    /// Relevance search over active, non-secret records. This is a deterministic
    /// keyword/tag score, deliberately not an embedding dependency.
    pub fn search(&self, query: &str, limit: usize) -> Vec<MemoryRecord> {
        let now = unix_secs();
        let tokens = tokenize(query);
        let phrase = normalize(query);
        let state = self.state.lock();
        let mut scored: Vec<(f32, MemoryRecord)> = state
            .records
            .iter()
            .filter(|r| is_active(r, now) && retrievable(r))
            .filter_map(|r| {
                let score = relevance(r, &tokens, &phrase, now);
                (score > 0.0).then(|| (score, r.clone()))
            })
            .collect();
        scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        scored.into_iter().take(limit.max(1)).map(|(_, r)| r).collect()
    }

    /// Prompt-ready retrieval, bounded by characters. Empty when nothing matches.
    pub fn context_for(&self, query: &str, budget_chars: usize) -> String {
        if budget_chars < 80 {
            return String::new();
        }
        let records = self.search(query, 8);
        if records.is_empty() {
            return String::new();
        }
        let mut out = String::from("Relevant memory (verified records, not guesses):\n");
        for record in records {
            let line = format!("- [{}] {}\n", record.kind.as_str(), record.text.trim());
            if out.len() + line.len() > budget_chars {
                break;
            }
            out.push_str(&line);
        }
        if out.lines().count() <= 1 {
            return String::new();
        }
        out
    }

    pub fn delete(&self, id: &str) -> Result<bool> {
        let now = unix_secs();
        let mut state = self.state.lock();
        let Some(record) = state
            .records
            .iter_mut()
            .find(|r| r.id == id && !r.deleted)
            .map(|r| {
                r.deleted = true;
                r.updated_at = now;
                r.clone()
            })
        else {
            return Ok(false);
        };
        append_record(&self.cipher, &self.path, &mut state, &record)?;
        Ok(true)
    }

    pub fn clear(&self) -> Result<usize> {
        let mut state = self.state.lock();
        let removed = state.records.iter().filter(|r| !r.deleted).count();
        rewrite(&self.cipher, &self.path, &mut state, &[])?;
        Ok(removed)
    }

    /// Drop tombstones and expired records, rewriting the journal compactly.
    pub fn compact(&self) -> Result<usize> {
        let now = unix_secs();
        let mut state = self.state.lock();
        let keep: Vec<MemoryRecord> = state
            .records
            .iter()
            .filter(|r| is_active(r, now))
            .cloned()
            .collect();
        let kept = keep.len();
        rewrite(&self.cipher, &self.path, &mut state, &keep)?;
        Ok(kept)
    }

    pub fn stats(&self) -> MemoryStats {
        let now = unix_secs();
        let state = self.state.lock();
        let active: Vec<&MemoryRecord> = state.records.iter().filter(|r| is_active(r, now)).collect();
        let mut by_kind = HashMap::new();
        for record in &active {
            *by_kind.entry(record.kind.as_str().to_string()).or_insert(0) += 1;
        }
        MemoryStats {
            total: state.records.len(),
            active: active.len(),
            expired: state.records.iter().filter(|r| is_expired(r, now)).count(),
            corrupt: state.corrupt,
            by_kind,
        }
    }

    /// Full JSON command channel used by the JNI surface.
    pub fn invoke(&self, request: &str) -> String {
        match self.handle_invoke(request) {
            Ok(data) => json!({"ok": true, "data": data}).to_string(),
            Err(error) => json!({"ok": false, "error": error}).to_string(),
        }
    }

    fn handle_invoke(&self, request: &str) -> Result<Value> {
        let value: Value = serde_json::from_str(request)
            .map_err(|e| JarvisError::InvalidArgument(format!("memory request: {e}")))?;
        let op = value
            .get("op")
            .and_then(Value::as_str)
            .unwrap_or_default();
        match op {
            "memory.list" => {
                let limit = value.get("limit").and_then(Value::as_u64).unwrap_or(50) as usize;
                let records = self.list(limit);
                let stats = self.stats();
                Ok(json!({"records": records, "stats": stats}))
            }
            "memory.search" => {
                let query = value
                    .get("query")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                let limit = value.get("limit").and_then(Value::as_u64).unwrap_or(20) as usize;
                let budget = value
                    .get("budgetChars")
                    .and_then(Value::as_u64)
                    .unwrap_or(800) as usize;
                let records = self.search(query, limit);
                Ok(json!({
                    "records": records,
                    "context": self.context_for(query, budget),
                    "stats": self.stats(),
                }))
            }
            "memory.add" => {
                let text = value
                    .get("text")
                    .and_then(Value::as_str)
                    .ok_or_else(|| JarvisError::InvalidArgument("memory.add requires text".into()))?;
                let kind = parse_kind(value.get("kind").and_then(Value::as_str))?;
                let tags = value
                    .get("tags")
                    .and_then(Value::as_array)
                    .map(|items| {
                        items
                            .iter()
                            .filter_map(Value::as_str)
                            .map(str::to_string)
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default();
                let (record, deduped) = self.remember(RememberRequest {
                    kind,
                    text: text.to_string(),
                    source: value
                        .get("source")
                        .and_then(Value::as_str)
                        .unwrap_or("user")
                        .to_string(),
                    importance: value
                        .get("importance")
                        .and_then(Value::as_f64)
                        .map(|v| v as f32),
                    tags,
                    expires_at: value.get("expiresAt").and_then(Value::as_u64),
                    confidence: value
                        .get("confidence")
                        .and_then(Value::as_f64)
                        .map(|v| v as f32)
                        .unwrap_or(1.0),
                    user_approved: value
                        .get("userApproved")
                        .and_then(Value::as_bool)
                        .unwrap_or(false),
                })?;
                Ok(json!({"record": record, "deduped": deduped}))
            }
            "memory.delete" => {
                let id = value
                    .get("id")
                    .and_then(Value::as_str)
                    .ok_or_else(|| JarvisError::InvalidArgument("memory.delete requires id".into()))?;
                let deleted = self.delete(id)?;
                Ok(json!({"deleted": deleted, "stats": self.stats()}))
            }
            "memory.clear" => {
                let removed = self.clear()?;
                Ok(json!({"removed": removed, "stats": self.stats()}))
            }
            "memory.compact" => {
                let kept = self.compact()?;
                Ok(json!({"kept": kept, "stats": self.stats()}))
            }
            "memory.stats" => Ok(json!({"stats": self.stats()})),
            "memory.export" => Ok(json!({
                "records": self.list(usize::MAX),
                "stats": self.stats(),
                "exportedAt": unix_secs(),
            })),
            "" => Err(JarvisError::InvalidArgument("memory request has no op".into())),
            other => Err(JarvisError::InvalidArgument(format!(
                "unknown memory op: {other}"
            ))),
        }
    }
}

fn parse_kind(value: Option<&str>) -> Result<MemoryKind> {
    match value.unwrap_or("short_term") {
        "working" => Ok(MemoryKind::Working),
        "short_term" => Ok(MemoryKind::ShortTerm),
        "long_term" => Ok(MemoryKind::LongTerm),
        "preference" => Ok(MemoryKind::Preference),
        "task" => Ok(MemoryKind::Task),
        "goal" => Ok(MemoryKind::Goal),
        "error" => Ok(MemoryKind::Error),
        "learning" => Ok(MemoryKind::Learning),
        other => Err(JarvisError::InvalidArgument(format!(
            "unknown memory kind: {other}"
        ))),
    }
}

fn is_expired(record: &MemoryRecord, now: u64) -> bool {
    record.expires_at.is_some_and(|expires| expires <= now)
}

fn is_active(record: &MemoryRecord, now: u64) -> bool {
    !record.deleted && !is_expired(record, now)
}

/// Approved long-term memory is retrievable; unapproved permanent memory is
/// visible for review but never injected into a prompt.
fn retrievable(record: &MemoryRecord) -> bool {
    !record.kind.requires_approval() || record.user_approved
}

fn looks_like_secret(text: &str) -> bool {
    let lower = text.to_lowercase();
    const NEEDLES: [&str; 10] = [
        "password",
        "passwd",
        "api key",
        "api_key",
        "apikey",
        "secret key",
        "client secret",
        "bearer ",
        "ghp_",
        "-----begin",
    ];
    NEEDLES.iter().any(|needle| lower.contains(needle)) || has_key_prefix(&lower)
}

/// `sk-…` is only a credential when it looks like a key, not when it is part of
/// a normal word such as "risk-free".
fn has_key_prefix(lower: &str) -> bool {
    lower.split_whitespace().any(|word| {
        (word.starts_with("sk-") && word.len() >= 20)
            || (word.starts_with("ghp_") && word.len() >= 20)
            || word.starts_with("github_pat_")
    })
}

fn dedup_tags(tags: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for tag in tags {
        let tag = tag.trim().to_lowercase();
        if tag.is_empty() || out.contains(&tag) {
            continue;
        }
        out.push(tag);
        if out.len() >= MAX_TAGS {
            break;
        }
    }
    out
}

fn normalize(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut space = false;
    for ch in text.chars().flat_map(char::to_lowercase) {
        if ch.is_alphanumeric() {
            out.push(ch);
            space = false;
        } else if !space && !out.is_empty() {
            out.push(' ');
            space = true;
        }
    }
    out.trim_end().to_string()
}

fn tokenize(text: &str) -> Vec<String> {
    const STOPWORDS: [&str; 24] = [
        "the", "and", "for", "with", "that", "this", "how", "should", "what", "when",
        "where", "who", "why", "are", "you", "your", "can", "could", "would", "please",
        "about", "from", "into", "just",
    ];
    let mut out: Vec<String> = Vec::new();
    for token in normalize(text).split_whitespace() {
        if token.chars().count() >= 3
            && !STOPWORDS.contains(&token)
            && !out.iter().any(|seen| seen == token)
        {
            out.push(token.to_string());
        }
    }
    out
}

fn content_hash(kind: MemoryKind, text: &str) -> String {
    // FNV-1a: stable and cheap; used only for duplicate detection.
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    let normalized = normalize(text);
    let bytes = kind
        .as_str()
        .bytes()
        .chain(b"|".iter().copied())
        .chain(normalized.bytes());
    for byte in bytes {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:016x}")
}

fn relevance(record: &MemoryRecord, tokens: &[String], phrase: &str, now: u64) -> f32 {
    let text = normalize(&record.text);
    let tags: Vec<String> = record.tags.iter().map(|t| normalize(t)).collect();
    let mut score = 0.0f32;
    for token in tokens {
        if text.split_whitespace().any(|word| word == token) {
            score += 1.0;
        } else if text.contains(token) {
            score += 0.5;
        }
        if tags.iter().any(|tag| tag == token || tag.contains(token)) {
            score += 1.5;
        }
    }
    if !phrase.is_empty() && text.contains(phrase) {
        score += 2.0;
    }
    if score <= 0.0 {
        return 0.0;
    }
    score += record.importance.clamp(0.0, 1.0) * 0.75;
    score += record.confidence.clamp(0.0, 1.0) * 0.25;
    let age_days = now.saturating_sub(record.updated_at) as f32 / 86_400.0;
    let recency = 1.0 - (age_days / 90.0).min(0.4);
    score * recency
}

fn new_id(now: u64) -> String {
    let mut bytes = [0u8; 8];
    let random = match getrandom::fill(&mut bytes) {
        Ok(()) => u64::from_le_bytes(bytes),
        Err(_) => now ^ 0x9e37_79b9_7f4a_7c15,
    };
    format!("m-{now:x}-{random:016x}")
}

fn unix_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn aad(index: u64) -> String {
    format!("{MAGIC}:{VERSION}:{index}")
}

fn encrypt_line(cipher: &Aes256Gcm, payload: &Value, index: u64) -> Result<String> {
    let plaintext = serde_json::to_vec(payload)
        .map_err(|e| JarvisError::Internal(format!("memory json: {e}")))?;
    let mut nonce_bytes = [0u8; NONCE_LEN];
    getrandom::fill(&mut nonce_bytes)
        .map_err(|e| JarvisError::Internal(format!("memory csprng: {e}")))?;
    let nonce = Nonce::from(nonce_bytes);
    let bound = aad(index);
    let ciphertext = cipher
        .encrypt(
            &nonce,
            Payload {
                msg: &plaintext,
                aad: bound.as_bytes(),
            },
        )
        .map_err(|_| JarvisError::Internal("memory encrypt failed".into()))?;
    let mut packed = Vec::with_capacity(NONCE_LEN + ciphertext.len());
    packed.extend_from_slice(&nonce_bytes);
    packed.extend_from_slice(&ciphertext);
    Ok(base64::engine::general_purpose::STANDARD.encode(packed))
}

fn decrypt_line(cipher: &Aes256Gcm, line: &str, index: u64) -> Result<Value> {
    let raw = base64::engine::general_purpose::STANDARD
        .decode(line.as_bytes())
        .map_err(|e| JarvisError::Model(format!("memory record is not base64: {e}")))?;
    if raw.len() <= NONCE_LEN {
        return Err(JarvisError::Model("memory record is too short".into()));
    }
    let (nonce_bytes, ciphertext) = raw.split_at(NONCE_LEN);
    let nonce = Nonce::try_from(nonce_bytes)
        .map_err(|_| JarvisError::Model("memory nonce is short".into()))?;
    let bound = aad(index);
    let plaintext = cipher
        .decrypt(
            &nonce,
            Payload {
                msg: ciphertext,
                aad: bound.as_bytes(),
            },
        )
        .map_err(|_| JarvisError::Model("memory record failed authentication".into()))?;
    serde_json::from_slice(&plaintext)
        .map_err(|e| JarvisError::Model(format!("memory json: {e}")))
}

fn write_header(cipher: &Aes256Gcm, path: &Path) -> Result<()> {
    let header = json!({"header": {"version": VERSION, "createdAt": unix_secs()}});
    let line = encrypt_line(cipher, &header, 0)?;
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|e| JarvisError::Internal(format!("create memory {path:?}: {e}")))?;
    writeln!(file, "{line}")
        .map_err(|e| JarvisError::Internal(format!("write memory {path:?}: {e}")))?;
    Ok(())
}

fn load(cipher: &Aes256Gcm, path: &Path) -> Result<State> {
    let file =
        File::open(path).map_err(|e| JarvisError::Internal(format!("open memory: {e}")))?;
    let reader = BufReader::new(file);
    let mut records: Vec<MemoryRecord> = Vec::new();
    let mut corrupt = 0usize;
    let mut index = 0u64;
    let mut header_seen = false;
    for line in reader.lines() {
        let line = line.map_err(|e| JarvisError::Internal(format!("read memory: {e}")))?;
        if line.trim().is_empty() {
            continue;
        }
        match decrypt_line(cipher, line.trim(), index) {
            Ok(value) => {
                if index == 0 {
                    let version = value
                        .get("header")
                        .and_then(|h| h.get("version"))
                        .and_then(Value::as_u64)
                        .unwrap_or(0);
                    if version != u64::from(VERSION) {
                        return Err(JarvisError::InvalidArgument(format!(
                            "memory file has unsupported version {version}"
                        )));
                    }
                    header_seen = true;
                } else if let Some(record) = value.get("record") {
                    match serde_json::from_value::<MemoryRecord>(record.clone()) {
                        Ok(record) => apply_record(&mut records, record),
                        Err(_) => corrupt += 1,
                    }
                } else {
                    corrupt += 1;
                }
            }
            Err(_) => corrupt += 1,
        }
        index += 1;
    }
    if !header_seen {
        return Err(JarvisError::InvalidArgument(
            "memory file has no valid header".into(),
        ));
    }
    Ok(State {
        records,
        next_index: index,
        corrupt,
    })
}

fn apply_record(records: &mut Vec<MemoryRecord>, record: MemoryRecord) {
    if let Some(existing) = records.iter_mut().find(|r| r.id == record.id) {
        *existing = record;
    } else {
        records.push(record);
    }
}

fn append_record(
    cipher: &Aes256Gcm,
    path: &Path,
    state: &mut State,
    record: &MemoryRecord,
) -> Result<()> {
    let index = state.next_index;
    let payload = json!({"record": record});
    let line = encrypt_line(cipher, &payload, index)?;
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|e| JarvisError::Internal(format!("open memory: {e}")))?;
    writeln!(file, "{line}")
        .map_err(|e| JarvisError::Internal(format!("write memory: {e}")))?;
    apply_record(&mut state.records, record.clone());
    state.next_index = index + 1;
    Ok(())
}

fn rewrite(
    cipher: &Aes256Gcm,
    path: &Path,
    state: &mut State,
    keep: &[MemoryRecord],
) -> Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut file = File::create(&tmp)
            .map_err(|e| JarvisError::Internal(format!("compact create: {e}")))?;
        let header = json!({"header": {"version": VERSION, "createdAt": unix_secs()}});
        writeln!(file, "{}", encrypt_line(cipher, &header, 0)?)
            .map_err(|e| JarvisError::Internal(format!("compact header: {e}")))?;
        for (offset, record) in keep.iter().enumerate() {
            let line = encrypt_line(cipher, &json!({"record": record}), offset as u64 + 1)?;
            writeln!(file, "{line}")
                .map_err(|e| JarvisError::Internal(format!("compact write: {e}")))?;
        }
    }
    std::fs::rename(&tmp, path)
        .map_err(|e| JarvisError::Internal(format!("compact rename: {e}")))?;
    state.records = keep.to_vec();
    state.next_index = keep.len() as u64 + 1;
    state.corrupt = 0;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> [u8; 32] {
        [7u8; 32]
    }

    fn store(dir: &tempfile::TempDir) -> MemoryStore {
        MemoryStore::open(&key(), dir.path().join("memory.enc")).unwrap()
    }

    fn approved(kind: MemoryKind, text: &str) -> RememberRequest {
        RememberRequest {
            kind,
            text: text.into(),
            source: "user".into(),
            importance: None,
            tags: Vec::new(),
            expires_at: None,
            confidence: 1.0,
            user_approved: true,
        }
    }

    #[test]
    fn round_trip_is_not_plaintext_on_disk() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("memory.enc");
        {
            let memory = MemoryStore::open(&key(), &path).unwrap();
            memory
                .remember(RememberRequest::conversation(
                    MemoryKind::ShortTerm,
                    "the spare key is under the blue pot",
                ))
                .unwrap();
        }
        let disk = std::fs::read_to_string(&path).unwrap();
        assert!(!disk.contains("blue pot"), "plaintext leaked");
        let reopened = MemoryStore::open(&key(), &path).unwrap();
        let records = reopened.list(10);
        assert_eq!(records.len(), 1);
        assert!(records[0].text.contains("blue pot"));
    }

    #[test]
    fn duplicate_text_updates_instead_of_appending() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        let (first, deduped) = memory
            .remember(RememberRequest::conversation(
                MemoryKind::ShortTerm,
                "Call Maria about the reactor",
            ))
            .unwrap();
        assert!(!deduped);
        let (second, deduped) = memory
            .remember(RememberRequest::conversation(
                MemoryKind::ShortTerm,
                "call maria about the reactor",
            ))
            .unwrap();
        assert!(deduped);
        assert_eq!(first.id, second.id);
        assert_eq!(memory.list(10).len(), 1);
    }

    #[test]
    fn permanent_memory_requires_approval() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        let denied = memory.remember(RememberRequest {
            user_approved: false,
            ..approved(MemoryKind::LongTerm, "Lester's birthday is 4 May")
        });
        assert!(matches!(denied, Err(JarvisError::PermissionDenied(_))));
        assert!(memory
            .remember(approved(MemoryKind::LongTerm, "Lester's birthday is 4 May"))
            .is_ok());
    }

    #[test]
    fn credentials_are_refused() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        let error = memory
            .remember(RememberRequest::conversation(
                MemoryKind::ShortTerm,
                "my github token is ghp_example",
            ))
            .unwrap_err();
        assert!(matches!(error, JarvisError::InvalidArgument(_)));
        assert!(memory.list(10).is_empty());
    }

    #[test]
    fn expired_records_are_not_retrieved() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        memory
            .remember(RememberRequest {
                expires_at: Some(unix_secs().saturating_sub(10)),
                ..approved(MemoryKind::LongTerm, "old preference about tea")
            })
            .unwrap();
        assert!(memory.list(10).is_empty());
        assert!(memory.search("tea", 10).is_empty());
        assert_eq!(memory.stats().expired, 1);
    }

    #[test]
    fn retrieval_prefers_relevant_and_approved_records() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        memory
            .remember(approved(MemoryKind::Preference, "Lester prefers brief answers"))
            .unwrap();
        memory
            .remember(approved(MemoryKind::LongTerm, "Lester's office is on the third floor"))
            .unwrap();
        let context = memory.context_for("how should I phrase the answer to Lester", 120);
        assert!(context.contains("brief answers"), "{context}");
        assert!(!context.contains("third floor"), "{context}");
    }

    #[test]
    fn unapproved_permanent_records_are_not_injected() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        // Simulate a legacy/imported record with approval=false.
        let record = MemoryRecord {
            id: "legacy-1".into(),
            kind: MemoryKind::LongTerm,
            text: "office door code is 4411".into(),
            source: "import".into(),
            importance: 1.0,
            created_at: unix_secs(),
            updated_at: unix_secs(),
            expires_at: None,
            tags: Vec::new(),
            hash: content_hash(MemoryKind::LongTerm, "office door code is 4411"),
            confidence: 1.0,
            user_approved: false,
            deleted: false,
        };
        let mut state = memory.state.lock();
        append_record(&memory.cipher, &memory.path, &mut state, &record).unwrap();
        drop(state);
        assert!(memory.search("office door code", 10).is_empty());
        assert_eq!(memory.list(10).len(), 1);
    }

    #[test]
    fn delete_is_a_tombstone_and_compaction_drops_it() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        let (record, _) = memory
            .remember(RememberRequest::conversation(
                MemoryKind::ShortTerm,
                "temporary note",
            ))
            .unwrap();
        assert!(memory.delete(&record.id).unwrap());
        assert!(memory.list(10).is_empty());
        assert!(!memory.delete(&record.id).unwrap());
        let kept = memory.compact().unwrap();
        assert_eq!(kept, 0);
        assert_eq!(memory.stats().total, 0);
    }

    #[test]
    fn corruption_is_quarantined_not_fatal() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("memory.enc");
        {
            let memory = MemoryStore::open(&key(), &path).unwrap();
            memory
                .remember(RememberRequest::conversation(
                    MemoryKind::ShortTerm,
                    "this record survives",
                ))
                .unwrap();
        }
        let mut file = OpenOptions::new().append(true).open(&path).unwrap();
        writeln!(file, "not-a-valid-record").unwrap();
        drop(file);
        let memory = MemoryStore::open(&key(), &path).unwrap();
        assert_eq!(memory.list(10).len(), 1);
        assert_eq!(memory.stats().corrupt, 1);
    }

    #[test]
    fn reordering_or_wrong_key_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("memory.enc");
        {
            let memory = MemoryStore::open(&key(), &path).unwrap();
            memory
                .remember(RememberRequest::conversation(MemoryKind::ShortTerm, "one"))
                .unwrap();
            memory
                .remember(RememberRequest::conversation(MemoryKind::ShortTerm, "two"))
                .unwrap();
        }
        let lines: Vec<String> = std::fs::read_to_string(&path)
            .unwrap()
            .lines()
            .map(str::to_string)
            .collect();
        assert_eq!(lines.len(), 3);
        // Swap the two record lines: the index is bound as AAD, so both fail.
        let swapped = format!("{}\n{}\n{}\n", lines[0], lines[2], lines[1]);
        std::fs::write(&path, swapped).unwrap();
        let memory = MemoryStore::open(&key(), &path).unwrap();
        assert!(memory.list(10).is_empty());
        assert!(memory.stats().corrupt >= 2);

        assert!(MemoryStore::open(&[9u8; 32], &path).is_err());
    }

    #[test]
    fn invoke_channel_adds_searches_and_deletes() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        let added = memory.invoke(
            r#"{"op":"memory.add","kind":"preference","text":"Lester likes tea","userApproved":true}"#,
        );
        let value: Value = serde_json::from_str(&added).unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["data"]["deduped"], false);

        let found: Value = serde_json::from_str(
            &memory.invoke(r#"{"op":"memory.search","query":"tea"}"#),
        )
        .unwrap();
        assert_eq!(found["data"]["records"][0]["kind"], "preference");

        let listed: Value =
            serde_json::from_str(&memory.invoke(r#"{"op":"memory.list"}"#)).unwrap();
        let id = listed["data"]["records"][0]["id"].as_str().unwrap().to_string();
        let deleted: Value = serde_json::from_str(
            &memory.invoke(&format!(r#"{{"op":"memory.delete","id":"{id}"}}"#)),
        )
        .unwrap();
        assert_eq!(deleted["data"]["deleted"], true);
    }

    #[test]
    fn unknown_op_and_missing_text_are_typed_errors() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        let bad: Value =
            serde_json::from_str(&memory.invoke(r#"{"op":"memory.nope"}"#)).unwrap();
        assert_eq!(bad["ok"], false);
        assert_eq!(bad["error"]["kind"], "invalidArgument");

        let missing: Value =
            serde_json::from_str(&memory.invoke(r#"{"op":"memory.add"}"#)).unwrap();
        assert_eq!(missing["ok"], false);
    }

    #[test]
    fn clear_removes_everything_and_keeps_the_header() {
        let dir = tempfile::tempdir().unwrap();
        let memory = store(&dir);
        memory
            .remember(RememberRequest::conversation(MemoryKind::ShortTerm, "one"))
            .unwrap();
        memory
            .remember(RememberRequest::conversation(MemoryKind::ShortTerm, "two"))
            .unwrap();
        assert_eq!(memory.clear().unwrap(), 2);
        assert!(memory.list(10).is_empty());
        let reopened = MemoryStore::open(&key(), &memory.path).unwrap();
        assert!(reopened.list(10).is_empty());
        assert_eq!(reopened.stats().corrupt, 0);
    }
}
