//! Encrypted local storage.
//!
//! Transcripts and tool traces are personal data: they are written as
//! AES-256-GCM records (12-byte random nonce, 16-byte tag) and base64-encoded
//! one per line, so the file is never plaintext and a partial write cannot
//! corrupt earlier records.
//!
//! The 32-byte key is generated and held in the Android Keystore by
//! `SecureStore.kt`; it is handed to Rust once at engine construction and never
//! written to disk by Rust. On the host, tests use a fixed key.

use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine as _;
use parking_lot::Mutex;
use serde_json::Value;

use crate::error::{JarvisError, Result};

const NONCE_LEN: usize = 12;

pub struct EncryptedStore {
    cipher: Aes256Gcm,
    path: PathBuf,
    write_lock: Mutex<()>,
}

impl EncryptedStore {
    pub fn new(key: &[u8], path: impl AsRef<Path>) -> Result<Self> {
        if key.len() != 32 {
            return Err(JarvisError::InvalidArgument(format!(
                "storage key must be 32 bytes, got {}",
                key.len()
            )));
        }
        let cipher = Aes256Gcm::new_from_slice(key)
            .map_err(|e| JarvisError::Internal(format!("invalid key: {e}")))?;
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| JarvisError::Internal(format!("create {parent:?}: {e}")))?;
        }
        Ok(Self {
            cipher,
            path,
            write_lock: Mutex::new(()),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn append(&self, record: &Value) -> Result<()> {
        let plaintext = serde_json::to_vec(record)
            .map_err(|e| JarvisError::InvalidArgument(format!("record: {e}")))?;
        let mut nonce_bytes = [0u8; NONCE_LEN];
        getrandom::fill(&mut nonce_bytes)
            .map_err(|e| JarvisError::Internal(format!("csprng: {e}")))?;
        let nonce = Nonce::from(nonce_bytes);
        let ciphertext = self
            .cipher
            .encrypt(&nonce, plaintext.as_ref())
            .map_err(|_| JarvisError::Internal("encrypt failed".into()))?;

        let mut line = Vec::with_capacity(NONCE_LEN + ciphertext.len());
        line.extend_from_slice(&nonce_bytes);
        line.extend_from_slice(&ciphertext);
        let encoded = base64::engine::general_purpose::STANDARD.encode(line);

        let _guard = self.write_lock.lock();
        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)
            .map_err(|e| JarvisError::Internal(format!("open {:?}: {e}", self.path)))?;
        writeln!(file, "{encoded}")
            .map_err(|e| JarvisError::Internal(format!("write {:?}: {e}", self.path)))?;
        Ok(())
    }

    pub fn read_all(&self) -> Result<Vec<Value>> {
        let file = match File::open(&self.path) {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(JarvisError::Internal(format!("open: {e}"))),
        };
        let reader = BufReader::new(file);
        let mut out = Vec::new();
        for line in reader.lines() {
            let line = line.map_err(|e| JarvisError::Internal(format!("read: {e}")))?;
            if line.trim().is_empty() {
                continue;
            }
            let raw = base64::engine::general_purpose::STANDARD
                .decode(line.as_bytes())
                .map_err(|e| JarvisError::Model(format!("corrupt store: {e}")))?;
            if raw.len() <= NONCE_LEN {
                continue;
            }
            let (nonce_bytes, ciphertext) = raw.split_at(NONCE_LEN);
            let nonce = Nonce::try_from(nonce_bytes)
                .map_err(|_| JarvisError::Model("store record has a short nonce".into()))?;
            let plaintext = self
                .cipher
                .decrypt(&nonce, ciphertext)
                .map_err(|_| JarvisError::Model("store record failed authentication".into()))?;
            let value: Value = serde_json::from_slice(&plaintext)
                .map_err(|e| JarvisError::Model(format!("store json: {e}")))?;
            out.push(value);
        }
        Ok(out)
    }

    /// Drop everything but the newest `keep` records (rewrites the file).
    pub fn compact(&self, keep: usize) -> Result<()> {
        let records = self.read_all()?;
        let sliced = if records.len() > keep {
            &records[records.len() - keep..]
        } else {
            &records[..]
        };
        let _guard = self.write_lock.lock();
        let tmp = self.path.with_extension("tmp");
        {
            let mut file = File::create(&tmp)
                .map_err(|e| JarvisError::Internal(format!("compact create: {e}")))?;
            for record in sliced {
                let plaintext = serde_json::to_vec(record)
                    .map_err(|e| JarvisError::InvalidArgument(format!("record: {e}")))?;
                let mut nonce_bytes = [0u8; NONCE_LEN];
                getrandom::fill(&mut nonce_bytes)
                    .map_err(|e| JarvisError::Internal(format!("csprng: {e}")))?;
                let nonce = Nonce::from(nonce_bytes);
                let ct = self
                    .cipher
                    .encrypt(&nonce, plaintext.as_ref())
                    .map_err(|_| JarvisError::Internal("encrypt failed".into()))?;
                let mut line = Vec::with_capacity(NONCE_LEN + ct.len());
                line.extend_from_slice(&nonce_bytes);
                line.extend_from_slice(&ct);
                writeln!(
                    file,
                    "{}",
                    base64::engine::general_purpose::STANDARD.encode(line)
                )
                .map_err(|e| JarvisError::Internal(format!("compact write: {e}")))?;
            }
        }
        std::fs::rename(&tmp, &self.path)
            .map_err(|e| JarvisError::Internal(format!("compact rename: {e}")))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn key() -> [u8; 32] {
        [7u8; 32]
    }

    #[test]
    fn round_trip_and_not_plaintext_on_disk() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("jarvis.log");
        let store = EncryptedStore::new(&key(), &path).unwrap();
        store
            .append(&json!({"role":"user","text":"call Maria"}))
            .unwrap();
        store.append(&json!({"role":"assistant","text":"Calling."})).unwrap();

        let records = store.read_all().unwrap();
        assert_eq!(records.len(), 2);
        assert_eq!(records[1]["text"], "Calling.");

        let disk = std::fs::read_to_string(&path).unwrap();
        assert!(!disk.contains("Maria"), "plaintext leaked: {disk}");
    }

    #[test]
    fn wrong_key_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("jarvis.log");
        EncryptedStore::new(&key(), &path)
            .unwrap()
            .append(&json!({"secret":true}))
            .unwrap();
        let other = EncryptedStore::new(&[9u8; 32], &path).unwrap();
        assert!(other.read_all().is_err());
    }

    #[test]
    fn tampering_is_detected() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("jarvis.log");
        let store = EncryptedStore::new(&key(), &path).unwrap();
        store.append(&json!({"a":1})).unwrap();
        let mut bytes = std::fs::read_to_string(&path).unwrap();
        // Flip one base64 character in the middle of the record.
        let idx = bytes.len() / 2;
        let replacement = if bytes.as_bytes()[idx] == b'A' { 'B' } else { 'A' };
        bytes.replace_range(idx..idx + 1, &replacement.to_string());
        std::fs::write(&path, bytes).unwrap();
        assert!(store.read_all().is_err());
    }

    #[test]
    fn compaction_keeps_newest() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("jarvis.log");
        let store = EncryptedStore::new(&key(), &path).unwrap();
        for i in 0..10 {
            store.append(&json!({"i": i})).unwrap();
        }
        store.compact(3).unwrap();
        let records = store.read_all().unwrap();
        assert_eq!(records.len(), 3);
        assert_eq!(records[2]["i"], 9);
    }

    #[test]
    fn rejects_short_key() {
        let dir = tempfile::tempdir().unwrap();
        assert!(EncryptedStore::new(&[1u8; 16], dir.path().join("x")).is_err());
    }
}
