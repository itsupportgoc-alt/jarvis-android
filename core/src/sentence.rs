//! Streaming sentence chunker.
//!
//! Tokens arrive one fragment at a time from Qwen. We must hand the *first*
//! sentence to TTS as soon as it is complete, without splitting on decimals,
//! abbreviations, URLs or code, and without waiting for the full reply.
//!
//! Design rules:
//! * Emit at a terminal `. ! ? …` followed by whitespace/end or a closing quote.
//! * Never split after a known abbreviation or a single-letter initial.
//! * Never split inside a URL (`http://`, `https://`, `www.`), an email, or a
//!   fenced code block.
//! * If a sentence exceeds `max_chars`, break at the last comma/space.
//! * `flush()` emits whatever remains when the stream ends.

const ABBREVIATIONS: &[&str] = &[
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "approx",
    "no", "fig", "al", "p.m", "a.m", "u.s", "u.k", "inc", "ltd", "co", "dept", "est",
];

const CLOSERS: &[char] = &['"', '\'', ')', ']', '}', '”', '’'];

#[derive(Debug, Clone, Copy)]
pub struct ChunkerConfig {
    pub max_chars: usize,
    /// Extend a too-short fragment onto the next sentence. Keep this tiny: a
    /// large value directly adds latency before the first word is spoken.
    pub min_chars: usize,
}

impl Default for ChunkerConfig {
    fn default() -> Self {
        Self {
            max_chars: 180,
            min_chars: 2,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SpeechChunk {
    pub text: String,
    pub question: bool,
    pub contains_url: bool,
}

#[derive(Debug, Default)]
pub struct SentenceChunker {
    cfg: ChunkerConfig,
    buffer: String,
    in_code_block: bool,
    backticks: u8,
}

impl SentenceChunker {
    pub fn new(cfg: ChunkerConfig) -> Self {
        Self {
            cfg,
            buffer: String::with_capacity(256),
            in_code_block: false,
            backticks: 0,
        }
    }

    pub fn pending(&self) -> &str {
        &self.buffer
    }

    /// Feed a token fragment. Returns zero or more completed sentences.
    ///
    /// Code fences are tracked at character level, so a fence split across any
    /// number of tokens still suppresses the entire block.
    pub fn push_str(&mut self, fragment: &str) -> Vec<SpeechChunk> {
        let mut out = Vec::new();
        for ch in fragment.chars() {
            if ch == '`' {
                self.backticks += 1;
                if self.backticks == 3 {
                    self.in_code_block = !self.in_code_block;
                    self.backticks = 0;
                    if !self.in_code_block
                        && !self.buffer.is_empty()
                        && !self.buffer.ends_with(' ')
                    {
                        self.buffer.push(' ');
                    }
                }
                continue;
            }
            self.backticks = 0;
            if self.in_code_block {
                continue;
            }
            self.buffer.push(ch);
            self.drain_ready(&mut out);
        }
        out
    }

    /// End of stream: emit the remainder.
    pub fn flush(&mut self) -> Option<SpeechChunk> {
        let trimmed = self.buffer.trim();
        let text = strip_markup(trimmed);
        self.buffer.clear();
        self.in_code_block = false;
        self.backticks = 0;
        if text.is_empty() {
            None
        } else {
            Some(SpeechChunk {
                question: text.ends_with('?'),
                contains_url: contains_url(&text),
                text,
            })
        }
    }

    fn drain_ready(&mut self, out: &mut Vec<SpeechChunk>) {
        loop {
            if self.buffer.is_empty() {
                return;
            }

            if let Some((cut, question)) = find_boundary(&self.buffer) {
                let candidate = self.buffer[..cut].trim().to_string();
                let rest = self.buffer[cut..].to_string();
                if is_speakable(&candidate) {
                    let text = strip_markup(&candidate);
                    if text.chars().count() >= self.cfg.min_chars {
                        out.push(SpeechChunk {
                            question: question || text.ends_with('?'),
                            contains_url: contains_url(&text),
                            text,
                        });
                        self.buffer = rest;
                        continue;
                    }
                }
                // Not a real boundary (abbreviation/initial): keep buffering.
                if candidate.chars().count() < 12 {
                    return;
                }
            }

            // Hard cap: split at the last natural break before max_chars.
            if self.buffer.chars().count() > self.cfg.max_chars {
                let cut = last_break(&self.buffer, self.cfg.max_chars)
                    .unwrap_or_else(|| byte_index_of_char(&self.buffer, self.cfg.max_chars));
                if cut == 0 {
                    return;
                }
                let candidate = self.buffer[..cut].trim().to_string();
                let rest = self.buffer[cut..].to_string();
                let text = strip_markup(&candidate);
                if !text.is_empty() {
                    out.push(SpeechChunk {
                        question: false,
                        contains_url: contains_url(&text),
                        text,
                    });
                }
                self.buffer = rest;
                continue;
            }
            return;
        }
    }
}

/// Find the earliest terminal punctuation that ends a sentence, returning the
/// byte index just past the punctuation (and any closing quote) plus whether it
/// ended with a question mark.
fn find_boundary(s: &str) -> Option<(usize, bool)> {
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let ch = s[i..].chars().next()?;
        let ch_len = ch.len_utf8();
        let end_of_sentence = matches!(ch, '.' | '!' | '?' | '…');
        if end_of_sentence {
            // Gather trailing closers.
            let mut cut = i + ch_len;
            while cut < bytes.len() {
                let next = s[cut..].chars().next()?;
                if CLOSERS.contains(&next) {
                    cut += next.len_utf8();
                } else {
                    break;
                }
            }
            // A terminal at the very end of the buffer is *not* a boundary:
            // the next token may be "50" (decimal), "m" (p.m.), or a closing
            // word. The stream end is handled by `flush`.
            let next_is_break = match s[cut..].chars().next() {
                None => false,
                Some(n) => n.is_whitespace(),
            };
            if next_is_break {
                let word = preceding_word(&s[..i]);
                let is_decimal = ch == '.'
                    && word.chars().last().is_some_and(|c| c.is_ascii_digit())
                    && s[cut..].trim_start().starts_with(|c: char| c.is_ascii_digit());
                let is_abbrev = ch == '.' && is_abbreviation(word);
                let is_url = ends_inside_url(&s[..i]);
                if !is_decimal && !is_abbrev && !is_url {
                    let question = ch == '?' || s[..cut].trim_end().ends_with('?');
                    return Some((cut, question));
                }
            }
        }
        i += ch_len;
    }
    None
}

fn preceding_word(prefix: &str) -> &str {
    let trimmed = prefix.trim_end();
    let start = trimmed
        .rfind(|c: char| c.is_whitespace() || c == '(' || c == '"' || c == '\'')
        .map(|i| i + 1)
        .unwrap_or(0);
    &trimmed[start..]
}

fn is_abbreviation(word: &str) -> bool {
    if word.is_empty() {
        return false;
    }
    // Single capital initial: "J. Smith".
    if word.chars().count() == 1
        && word
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_uppercase())
    {
        return true;
    }
    let normalized = word.to_ascii_lowercase();
    ABBREVIATIONS.contains(&normalized.as_str())
}

fn ends_inside_url(prefix: &str) -> bool {
    let last_token = prefix
        .rsplit(|c: char| c.is_whitespace())
        .next()
        .unwrap_or("");
    (last_token.starts_with("http://") || last_token.starts_with("https://") || last_token.starts_with("www."))
        && !last_token.ends_with('/')
}

fn contains_url(text: &str) -> bool {
    text.split_whitespace().any(|w| {
        w.starts_with("http://") || w.starts_with("https://") || w.starts_with("www.")
    })
}

fn is_speakable(candidate: &str) -> bool {
    candidate.chars().any(|c| c.is_alphanumeric())
}

fn last_break(s: &str, limit_chars: usize) -> Option<usize> {
    let mut best = None;
    for (idx, ch) in s.char_indices() {
        if idx >= byte_index_of_char(s, limit_chars) {
            break;
        }
        if matches!(ch, ',' | ';' | ':' | ' ' | '\n') {
            best = Some(idx + ch.len_utf8());
        }
    }
    best
}

fn byte_index_of_char(s: &str, chars: usize) -> usize {
    s.char_indices()
        .nth(chars)
        .map(|(i, _)| i)
        .unwrap_or(s.len())
}

/// Remove markdown emphasis/backticks and collapse whitespace. URLs are kept in
/// the transcript but the Kotlin TTS layer substitutes "I've put that on screen
/// instead." for the spoken form.
pub fn strip_markup(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut last_space = false;
    for ch in s.chars() {
        if matches!(ch, '*' | '`' | '#') {
            continue;
        }
        if ch.is_whitespace() {
            if !last_space && !out.is_empty() {
                out.push(' ');
            }
            last_space = true;
        } else {
            out.push(ch);
            last_space = false;
        }
    }
    out.trim().to_string()
}

/// Text safe to hand to a TTS engine.
pub fn spoken_form(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for word in text.split_whitespace() {
        let is_url =
            word.starts_with("http://") || word.starts_with("https://") || word.starts_with("www.");
        if is_url {
            out.push_str("I've put that on screen instead.");
        } else {
            out.push_str(word);
        }
        out.push(' ');
    }
    out.trim_end().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collect(fragments: &[&str]) -> Vec<String> {
        let mut c = SentenceChunker::default();
        let mut out = Vec::new();
        for f in fragments {
            out.extend(c.push_str(f).into_iter().map(|s| s.text));
        }
        if let Some(last) = c.flush() {
            out.push(last.text);
        }
        out
    }

    #[test]
    fn splits_two_sentences() {
        let out = collect(&["Good evening, sir. How may I be of service?"]);
        assert_eq!(out, vec!["Good evening, sir.", "How may I be of service?"]);
    }

    #[test]
    fn streams_across_token_boundaries() {
        let mut c = SentenceChunker::new(ChunkerConfig {
            max_chars: 500,
            min_chars: 1,
        });
        assert!(c.push_str("The reactor is at ").is_empty());
        assert!(c.push_str("ninety-eight").is_empty());
        let chunks = c.push_str(" percent. Ready when you are.");
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].text, "The reactor is at ninety-eight percent.");
        assert_eq!(c.flush().unwrap().text, "Ready when you are.");
        // No boundary is emitted for a terminal that ends the current buffer:
        // the next fragment may continue a decimal or abbreviation.
        let mut c2 = SentenceChunker::new(ChunkerConfig {
            max_chars: 500,
            min_chars: 1,
        });
        assert!(c2.push_str("It is 3.").is_empty());
        let tail = c2.push_str("5 seconds. ");
        assert_eq!(tail.len(), 1);
        assert_eq!(tail[0].text, "It is 3.5 seconds.");
    }

    #[test]
    fn does_not_split_decimals_or_abbreviations() {
        let out = collect(&["Dr. Smith paid 3.50 dollars at 4.30 p.m. yesterday."]);
        assert_eq!(out.len(), 1, "{out:?}");
        assert!(out[0].starts_with("Dr. Smith paid 3.50"));
    }

    #[test]
    fn initials_are_not_boundaries() {
        let out = collect(&["J. R. Tolkien wrote it."]);
        assert_eq!(out.len(), 1, "{out:?}");
    }

    #[test]
    fn urls_are_not_split_and_are_flagged() {
        let mut c = SentenceChunker::default();
        let chunks = c.push_str("See https://example.com/a.b/c for the data. ");
        assert_eq!(chunks.len(), 1, "{chunks:?}");
        assert!(chunks[0].contains_url);
    }

    #[test]
    fn code_fences_are_dropped() {
        let mut c = SentenceChunker::new(ChunkerConfig {
            max_chars: 500,
            min_chars: 1,
        });
        let chunks = c.push_str("Here:\n```rust\nfn main() { panic!() }\n```\nDone. ");
        let texts: Vec<_> = chunks.into_iter().map(|c| c.text).collect();
        assert!(
            texts.iter().all(|t| !t.contains("fn main")),
            "code leaked: {texts:?}"
        );
        assert!(texts.iter().any(|t| t.contains("Done.")), "{texts:?}");
    }

    #[test]
    fn hard_cap_splits_at_a_space() {
        let mut c = SentenceChunker::new(ChunkerConfig {
            max_chars: 40,
            min_chars: 1,
        });
        let long = "word ".repeat(40);
        let chunks = c.push_str(&long);
        assert!(!chunks.is_empty());
        assert!(chunks.iter().all(|chunk| chunk.text.chars().count() <= 40));
    }

    #[test]
    fn questions_are_flagged() {
        let out = collect(&["Shall I call her?"]);
        assert_eq!(out.len(), 1);
        let mut c = SentenceChunker::default();
        let chunks = c.push_str("Shall I call her? ");
        assert!(chunks[0].question);
    }

    #[test]
    fn spoken_form_replaces_urls() {
        assert_eq!(
            spoken_form("Details at https://example.com/x now."),
            "Details at I've put that on screen instead. now."
        );
    }

    #[test]
    fn empty_flush_is_none() {
        let mut c = SentenceChunker::default();
        assert!(c.flush().is_none());
    }
}
