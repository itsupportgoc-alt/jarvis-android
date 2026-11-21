//! Explicit session state machine.
//!
//! The UI orb is a pure function of this state, so illegal transitions are
//! rejected here rather than guessed in Compose. A generation counter lets the
//! UI discard stale animations and lets the engine discard stale streams.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum SessionState {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Error,
}

impl SessionState {
    pub fn as_str(self) -> &'static str {
        match self {
            SessionState::Idle => "idle",
            SessionState::Listening => "listening",
            SessionState::Thinking => "thinking",
            SessionState::Speaking => "speaking",
            SessionState::Error => "error",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionEvent {
    /// Wake word, push-to-talk, or "open the mic".
    Wake,
    /// VAD heard speech. May arrive while speaking (barge-in).
    SpeechStart,
    /// VAD closed the utterance window.
    SpeechEnd,
    /// First sentence was handed to TTS.
    ReplyStarted,
    /// TTS finished the last sentence.
    ReplyFinished,
    /// User interrupted (button or barge-in).
    Interrupt,
    Failure,
    Reset,
}

impl SessionEvent {
    pub fn as_str(self) -> &'static str {
        match self {
            SessionEvent::Wake => "wake",
            SessionEvent::SpeechStart => "speechStart",
            SessionEvent::SpeechEnd => "speechEnd",
            SessionEvent::ReplyStarted => "replyStarted",
            SessionEvent::ReplyFinished => "replyFinished",
            SessionEvent::Interrupt => "interrupt",
            SessionEvent::Failure => "failure",
            SessionEvent::Reset => "reset",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Transition {
    pub from: SessionState,
    pub to: SessionState,
    pub generation: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("invalid transition {event:?} from {state:?}")]
pub struct InvalidTransition {
    pub state: SessionState,
    pub event: SessionEvent,
}

#[derive(Debug, Clone)]
pub struct SessionMachine {
    state: SessionState,
    generation: u64,
}

impl Default for SessionMachine {
    fn default() -> Self {
        Self {
            state: SessionState::Idle,
            generation: 0,
        }
    }
}

impl SessionMachine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn state(&self) -> SessionState {
        self.state
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// Apply an event. Returns `None` when the event is valid but intentionally
    /// does not change state (e.g. a stray SpeechStart while already listening).
    pub fn apply(&mut self, event: SessionEvent) -> Result<Option<Transition>, InvalidTransition> {
        use SessionEvent as E;
        use SessionState as S;

        let next = match (self.state, event) {
            // Idle
            (S::Idle, E::Wake) | (S::Idle, E::SpeechStart) => Some(S::Listening),
            (S::Idle, E::Reset) | (S::Idle, E::Interrupt) => None,
            (S::Idle, E::Failure) => Some(S::Error),

            // Listening
            (S::Listening, E::Wake) => None,
            (S::Listening, E::SpeechStart) => None,
            (S::Listening, E::SpeechEnd) => Some(S::Thinking),
            (S::Listening, E::Interrupt) | (S::Listening, E::Reset) => Some(S::Idle),
            (S::Listening, E::ReplyStarted) => Some(S::Speaking),
            (S::Listening, E::Failure) => Some(S::Error),

            // Thinking
            (S::Thinking, E::ReplyStarted) => Some(S::Speaking),
            (S::Thinking, E::SpeechStart) => None,
            (S::Thinking, E::Interrupt) | (S::Thinking, E::Reset) => Some(S::Idle),
            (S::Thinking, E::SpeechEnd) => None,
            (S::Thinking, E::Failure) => Some(S::Error),

            // Speaking
            (S::Speaking, E::ReplyFinished) => Some(S::Idle),
            (S::Speaking, E::SpeechStart) => Some(S::Listening),
            (S::Speaking, E::SpeechEnd) => None,
            (S::Speaking, E::Wake) => None,
            (S::Speaking, E::Interrupt) | (S::Speaking, E::Reset) => Some(S::Listening),
            (S::Speaking, E::Failure) => Some(S::Error),

            // Error
            (S::Error, E::Reset) => Some(S::Idle),
            (S::Error, E::Wake) => Some(S::Listening),
            (S::Error, E::Interrupt) => Some(S::Idle),
            // Everything else is rejected below.
            _ => {
                return Err(InvalidTransition {
                    state: self.state,
                    event,
                })
            }
        };

        match next {
            Some(to) => {
                let from = self.state;
                self.state = to;
                if matches!(to, S::Listening) && from != S::Listening {
                    self.generation += 1;
                }
                if to == S::Idle {
                    // A new utterance always gets a fresh generation, so late
                    // tokens from a cancelled stream are trivially discarded.
                    self.generation += 1;
                }
                Ok(Some(Transition {
                    from,
                    to,
                    generation: self.generation,
                }))
            }
            None => Ok(None),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use SessionEvent as E;
    use SessionState as S;

    fn run(events: &[SessionEvent]) -> SessionMachine {
        let mut m = SessionMachine::new();
        for e in events {
            m.apply(*e).unwrap();
        }
        m
    }

    #[test]
    fn happy_path() {
        let mut m = SessionMachine::new();
        assert_eq!(m.state(), S::Idle);
        m.apply(E::Wake).unwrap();
        assert_eq!(m.state(), S::Listening);
        m.apply(E::SpeechEnd).unwrap();
        assert_eq!(m.state(), S::Thinking);
        m.apply(E::ReplyStarted).unwrap();
        assert_eq!(m.state(), S::Speaking);
        m.apply(E::ReplyFinished).unwrap();
        assert_eq!(m.state(), S::Idle);
    }

    #[test]
    fn barge_in_from_speaking_returns_to_listening() {
        let m = run(&[E::Wake, E::SpeechEnd, E::ReplyStarted, E::SpeechStart]);
        assert_eq!(m.state(), S::Listening);
    }

    #[test]
    fn generation_bumps_on_new_utterance_and_never_goes_back() {
        let mut m = SessionMachine::new();
        let first = m.apply(E::Wake).unwrap().unwrap().generation;
        m.apply(E::SpeechEnd).unwrap();
        m.apply(E::ReplyStarted).unwrap();
        m.apply(E::ReplyFinished).unwrap();
        let second = m.apply(E::Wake).unwrap().unwrap().generation;
        assert!(second > first, "{second} !> {first}");
    }

    #[test]
    fn interrupted_thinking_returns_to_idle() {
        let mut m = run(&[E::Wake, E::SpeechEnd]);
        m.apply(E::Interrupt).unwrap();
        assert_eq!(m.state(), S::Idle);
    }

    #[test]
    fn invalid_event_does_not_mutate_state() {
        let mut m = SessionMachine::new();
        let before = m.generation();
        assert!(m.apply(E::ReplyFinished).is_err());
        assert_eq!(m.state(), S::Idle);
        assert_eq!(m.generation(), before);
    }

    #[test]
    fn failure_then_reset_recovers() {
        let mut m = run(&[E::Wake, E::Failure]);
        assert_eq!(m.state(), S::Error);
        m.apply(E::Reset).unwrap();
        assert_eq!(m.state(), S::Idle);
    }

    #[test]
    fn stray_speech_start_is_harmless() {
        let mut m = run(&[E::Wake]);
        assert!(m.apply(E::SpeechStart).unwrap().is_none());
        assert_eq!(m.state(), S::Listening);
    }
}
