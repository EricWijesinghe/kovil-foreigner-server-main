//! Listing description translation engines, and the gate that decides whether engine output may be shown or cached.
//! Mirrors `infra/translation/*`, `infra/openai/translation/*` and `infra/gemini/translation/*`.
//!
//! Engine text goes through `kovil_l10n::validate`. A result with an error-severity issue (changed amount, wrong
//! language, broken placeholder) is never returned or stored: one retry, then the caller shows the Korean original.

use std::collections::BTreeSet;
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use reqwest::StatusCode;
use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::json;

use kovil_l10n::{Context, Issue, Severity, prompt, validate};

/// Listing text states deposits, rents and floors as facts: the neutral, concise register.
pub const CONTEXT: Context = Context::Transactional;
/// Engine answers are sampled; a second attempt can pass where the first changed an amount.
const MAX_ATTEMPTS: usize = 2;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
/// An answer more than this many times the source length (plus the slack) is added text, not a translation.
const MAX_LENGTH_RATIO: usize = 4;
const LENGTH_SLACK: usize = 200;

pub type EngineFuture<'a> = Pin<Box<dyn Future<Output = Result<String, EngineError>> + Send + 'a>>;

/// A translation vendor. `system` is the kovil_l10n system instruction for the target locale; `text` is the Korean.
pub trait Engine: Send + Sync {
    /// Stored in `listing_description_translations.engine`.
    fn name(&self) -> &'static str;
    fn complete<'a>(&'a self, system: &'a str, text: &'a str) -> EngineFuture<'a>;
}

/// Why a vendor call produced no text. Logged by reason only: never the text, never the key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EngineError {
    Unauthorized,
    RateLimited,
    Provider,
}

/// Credential. Debug prints a placeholder, so a config dump never shows a key.
#[derive(Clone)]
pub struct Secret(String);

impl Secret {
    pub fn new(value: String) -> Self {
        Self(value)
    }

    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("<redacted>")
    }
}

#[derive(Debug, Clone)]
pub struct OpenAiSettings {
    pub api_url: String,
    pub api_key: Secret,
    pub model: String,
    /// None = field not sent, so the model's default effort applies.
    pub reasoning_effort: Option<String>,
    pub max_completion_tokens: u32,
    pub timeout: Duration,
}

#[derive(Debug, Clone)]
pub struct GeminiSettings {
    pub api_url: String,
    pub api_key: Secret,
    pub model: String,
    pub timeout: Duration,
}

/// `TRANSLATION_PROVIDER`. Fake is the default, as in Java.
#[derive(Debug, Clone)]
pub enum Provider {
    Fake,
    OpenAi(OpenAiSettings),
    Gemini(GeminiSettings),
}

impl Provider {
    pub fn name(&self) -> &'static str {
        match self {
            Self::Fake => "fake",
            Self::OpenAi(_) => "openai",
            Self::Gemini(_) => "gemini",
        }
    }
}

pub fn build(provider: &Provider) -> Result<Arc<dyn Engine>, String> {
    Ok(match provider {
        Provider::Fake => Arc::new(Fake),
        Provider::OpenAi(s) => Arc::new(OpenAi { client: http_client(s.timeout)?, s: s.clone() }),
        Provider::Gemini(s) => Arc::new(Gemini { client: http_client(s.timeout)?, s: s.clone() }),
    })
}

fn http_client(timeout: Duration) -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(timeout)
        .build()
        .map_err(|e| format!("translation http client: {e}"))
}

/// Dev and test engine. It echoes the source with a marker, so the gate rejects it: nothing fake is ever cached.
pub struct Fake;

impl Engine for Fake {
    fn name(&self) -> &'static str {
        "fake"
    }

    fn complete<'a>(&'a self, _system: &'a str, text: &'a str) -> EngineFuture<'a> {
        Box::pin(async move { Ok(format!("[FAKE] {text}")) })
    }
}

/// Maps a non-success status and transport failure onto the reasons Java's error decoders use.
async fn parse<T: DeserializeOwned>(sent: reqwest::Result<reqwest::Response>) -> Result<T, EngineError> {
    let res = sent.map_err(|_| EngineError::Provider)?;
    let status = res.status();
    if !status.is_success() {
        return Err(match status {
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => EngineError::Unauthorized,
            StatusCode::TOO_MANY_REQUESTS | StatusCode::SERVICE_UNAVAILABLE => EngineError::RateLimited,
            _ => EngineError::Provider,
        });
    }
    res.json::<T>().await.map_err(|_| EngineError::Provider)
}

struct OpenAi {
    client: reqwest::Client,
    s: OpenAiSettings,
}

#[derive(Deserialize)]
struct OpenAiReply {
    #[serde(default)]
    choices: Vec<OpenAiChoice>,
}

#[derive(Deserialize)]
struct OpenAiChoice {
    message: OpenAiMessage,
}

#[derive(Deserialize)]
struct OpenAiMessage {
    content: Option<String>,
}

impl Engine for OpenAi {
    fn name(&self) -> &'static str {
        "openai"
    }

    fn complete<'a>(&'a self, system: &'a str, text: &'a str) -> EngineFuture<'a> {
        Box::pin(async move {
            // "developer" carries the instruction; the source is the only user turn.
            let mut body = json!({
                "model": self.s.model,
                "messages": [
                    {"role": "developer", "content": system},
                    {"role": "user", "content": text},
                ],
                "max_completion_tokens": self.s.max_completion_tokens,
            });
            if let Some(effort) = &self.s.reasoning_effort {
                body["reasoning_effort"] = json!(effort);
            }
            let url = format!("{}/v1/chat/completions", self.s.api_url);
            let sent = self.client.post(url).bearer_auth(self.s.api_key.expose()).json(&body).send().await;
            let reply: OpenAiReply = parse(sent).await?;
            reply
                .choices
                .into_iter()
                .next()
                .and_then(|c| c.message.content)
                .ok_or(EngineError::Provider)
        })
    }
}

struct Gemini {
    client: reqwest::Client,
    s: GeminiSettings,
}

#[derive(Deserialize)]
struct GeminiReply {
    #[serde(default)]
    candidates: Vec<GeminiCandidate>,
}

#[derive(Deserialize)]
struct GeminiCandidate {
    content: Option<GeminiContent>,
}

#[derive(Deserialize)]
struct GeminiContent {
    #[serde(default)]
    parts: Vec<GeminiPart>,
}

#[derive(Deserialize)]
struct GeminiPart {
    text: Option<String>,
}

impl GeminiReply {
    /// First candidate, first part. Any missing link means no text, never a panic.
    fn first_text(self) -> Option<String> {
        self.candidates.into_iter().next()?.content?.parts.into_iter().next()?.text
    }
}

impl Engine for Gemini {
    fn name(&self) -> &'static str {
        "gemini"
    }

    fn complete<'a>(&'a self, system: &'a str, text: &'a str) -> EngineFuture<'a> {
        Box::pin(async move {
            // temperature 0: the same source should give the same text, or the cache means nothing.
            let body = json!({
                "contents": [{"parts": [{"text": text}]}],
                "systemInstruction": {"parts": [{"text": system}]},
                "generationConfig": {"temperature": 0.0},
            });
            let url = format!("{}/v1beta/models/{}:generateContent", self.s.api_url, self.s.model);
            let sent = self.client.post(url).header("x-goog-api-key", self.s.api_key.expose()).json(&body).send().await;
            let reply: GeminiReply = parse(sent).await?;
            reply.first_text().ok_or(EngineError::Provider)
        })
    }
}

/// Trims padding and a markdown fence a vendor sometimes wraps around the text. None when nothing is left.
pub fn clean(raw: &str) -> Option<String> {
    let mut s = raw.trim();
    if let Some(inner) = s.strip_prefix("```") {
        // Drop an optional info line ("```text"), then the closing fence.
        let inner = inner.find('\n').map_or(inner, |i| &inner[i + 1..]);
        s = inner.strip_suffix("```").unwrap_or(inner).trim();
    }
    (!s.is_empty()).then(|| s.to_owned())
}

/// Translation of `source` into `target` that passed the gate, or None: the caller then shows the original.
/// Vendor failures and empty answers are not retried; a gate rejection is retried once.
pub async fn translate_checked(engine: &dyn Engine, source: &str, target: &str) -> Option<String> {
    let system = match prompt::system_prompt(target, CONTEXT, source) {
        Ok(system) => system,
        Err(e) => {
            tracing::warn!(lang = target, "no locale profile, original shown: {e}");
            return None;
        }
    };
    for attempt in 1..=MAX_ATTEMPTS {
        let raw = match engine.complete(&system, source).await {
            Ok(raw) => raw,
            Err(reason) => {
                tracing::warn!(engine = engine.name(), lang = target, ?reason, "translation failed");
                return None;
            }
        };
        let Some(out) = clean(&raw) else {
            tracing::warn!(engine = engine.name(), lang = target, "translation came back empty");
            return None;
        };
        let Ok(issues) = validate::validate(source, &out, target, None) else { return None };
        // Codes only: messages quote amounts from the listing.
        let mut blocking: Vec<&str> = issues.iter().filter(|i| is_blocking(i)).map(|i| i.code).collect();
        // Text the source never had (a link, a phone number, a long appended tail) is what an injected listing produces.
        if adds_contact(source, &out) {
            blocking.push("contact_added");
        }
        if out_of_proportion(source, &out) {
            blocking.push("out_of_proportion");
        }
        if blocking.is_empty() {
            return Some(out);
        }
        tracing::warn!(engine = engine.name(), lang = target, attempt, codes = ?blocking, "translation rejected by validation");
    }
    None
}

/// Error severity blocks. `untranslated` blocks too: English returned for a non-Latin target would be cached and served.
fn is_blocking(issue: &Issue) -> bool {
    issue.severity == Severity::Error || issue.code == "untranslated"
}

/// Links, emails, handles and phone numbers in `text`, lower-cased. A messenger ID written without a symbol is not caught.
fn contact_tokens(text: &str) -> BTreeSet<String> {
    text.split_whitespace()
        .map(|w| w.trim_matches(|c: char| !c.is_alphanumeric() && !matches!(c, '@' | '+' | '/')).to_lowercase())
        .filter(|w| is_contact(w))
        .collect()
}

fn is_contact(w: &str) -> bool {
    let phone = w.chars().filter(char::is_ascii_digit).count() >= 9
        && (w.starts_with('0') || w.starts_with('+'))
        && w.chars().all(|c| c.is_ascii_digit() || matches!(c, '-' | '+'));
    let email = w.split_once('@').is_some_and(|(local, domain)| !local.is_empty() && domain.contains('.'));
    let domain = w
        .rsplit_once('.')
        .is_some_and(|(label, tld)| !label.is_empty() && (2..=6).contains(&tld.len()) && tld.chars().all(|c| c.is_ascii_alphabetic()));
    phone || email || domain || w.starts_with('@') || w.contains("://") || w.starts_with("www.")
}

/// True when the output carries a contact token the source does not. Shortcut: a heuristic, so a reformatted number can false-reject.
fn adds_contact(source: &str, output: &str) -> bool {
    let known = contact_tokens(source);
    contact_tokens(output).iter().any(|t| !known.contains(t))
}

/// Korean is the denser script, so an answer this much longer than its source is added text.
fn out_of_proportion(source: &str, output: &str) -> bool {
    output.chars().count() > source.chars().count() * MAX_LENGTH_RATIO + LENGTH_SLACK
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;

    const SOURCE: &str = "보증금 1,000,000원, 월세 50만원. 전세 계약 가능.";
    const GOOD: &str = "Deposit 1,000,000 won, monthly rent 500,000 won. Lease contract available.";
    /// Same wording, deposit cut by a factor of ten: the failure the gate exists for.
    const DEPOSIT_CHANGED: &str = "Deposit 100,000 won, monthly rent 500,000 won. Lease contract available.";

    /// Replays scripted answers and records every call and the system instruction it was given.
    struct Scripted {
        replies: Mutex<VecDeque<Result<String, EngineError>>>,
        calls: AtomicUsize,
        systems: Mutex<Vec<String>>,
    }

    impl Scripted {
        fn new(replies: Vec<Result<&str, EngineError>>) -> Self {
            Self {
                replies: Mutex::new(replies.into_iter().map(|r| r.map(str::to_owned)).collect()),
                calls: AtomicUsize::new(0),
                systems: Mutex::new(Vec::new()),
            }
        }

        fn calls(&self) -> usize {
            self.calls.load(Ordering::SeqCst)
        }
    }

    impl Engine for Scripted {
        fn name(&self) -> &'static str {
            "scripted"
        }

        fn complete<'a>(&'a self, system: &'a str, _text: &'a str) -> EngineFuture<'a> {
            Box::pin(async move {
                self.calls.fetch_add(1, Ordering::SeqCst);
                self.systems.lock().unwrap().push(system.to_owned());
                self.replies.lock().unwrap().pop_front().unwrap_or(Err(EngineError::Provider))
            })
        }
    }

    #[tokio::test]
    async fn clean_output_passes_on_first_attempt() {
        let engine = Scripted::new(vec![Ok(GOOD)]);
        assert_eq!(translate_checked(&engine, SOURCE, "en").await.as_deref(), Some(GOOD));
        assert_eq!(engine.calls(), 1);
    }

    #[tokio::test]
    async fn changed_deposit_is_rejected_after_one_retry() {
        let engine = Scripted::new(vec![Ok(DEPOSIT_CHANGED), Ok(DEPOSIT_CHANGED)]);
        assert_eq!(translate_checked(&engine, SOURCE, "en").await, None);
        assert_eq!(engine.calls(), MAX_ATTEMPTS);
    }

    #[tokio::test]
    async fn retry_recovers_when_second_answer_keeps_the_amount() {
        let engine = Scripted::new(vec![Ok(DEPOSIT_CHANGED), Ok(GOOD)]);
        assert_eq!(translate_checked(&engine, SOURCE, "en").await.as_deref(), Some(GOOD));
        assert_eq!(engine.calls(), 2);
    }

    #[tokio::test]
    async fn wrong_language_is_rejected() {
        let korean_back = "보증금 1,000,000원, 월세 500,000원. 전세 계약 가능.";
        let engine = Scripted::new(vec![Ok(korean_back), Ok(korean_back)]);
        assert_eq!(translate_checked(&engine, SOURCE, "en").await, None);
    }

    #[tokio::test]
    async fn vendor_error_and_blank_answer_are_not_retried() {
        let failed = Scripted::new(vec![Err(EngineError::RateLimited), Ok(GOOD)]);
        assert_eq!(translate_checked(&failed, SOURCE, "en").await, None);
        assert_eq!(failed.calls(), 1);

        let blank = Scripted::new(vec![Ok("  \n "), Ok(GOOD)]);
        assert_eq!(translate_checked(&blank, SOURCE, "en").await, None);
        assert_eq!(blank.calls(), 1);
    }

    #[tokio::test]
    async fn unknown_locale_never_calls_the_engine() {
        let engine = Scripted::new(vec![Ok(GOOD)]);
        assert_eq!(translate_checked(&engine, SOURCE, "xx").await, None);
        assert_eq!(engine.calls(), 0);
    }

    #[tokio::test]
    async fn system_instruction_is_the_transactional_register_for_the_target() {
        let engine = Scripted::new(vec![Ok(GOOD)]);
        translate_checked(&engine, SOURCE, "en").await;
        let system = engine.systems.lock().unwrap()[0].clone();
        assert!(system.contains("Context: Transactional."), "{system}");
        assert!(system.contains("\"jeonse\""), "glossary hit for 전세 missing");
    }

    #[test]
    fn clean_strips_padding_and_fences() {
        assert_eq!(clean("  Hello  ").as_deref(), Some("Hello"));
        assert_eq!(clean("```\nHello there\n```").as_deref(), Some("Hello there"));
        assert_eq!(clean("```text\nHello\n```").as_deref(), Some("Hello"));
        assert_eq!(clean("```\n```"), None);
        assert_eq!(clean(" \n\t "), None);
    }

    #[test]
    fn gemini_first_text_survives_odd_shapes() {
        let full: GeminiReply = serde_json::from_value(json!({"candidates": [{"content": {"parts": [{"text": "a"}]}}]})).unwrap();
        assert_eq!(full.first_text().as_deref(), Some("a"));
        for shape in [json!({}), json!({"candidates": []}), json!({"candidates": [{}]}), json!({"candidates": [{"content": {"parts": []}}]})] {
            let r: GeminiReply = serde_json::from_value(shape).unwrap();
            assert_eq!(r.first_text(), None);
        }
    }

    #[tokio::test]
    async fn english_for_non_latin_target_is_rejected() {
        let engine = Scripted::new(vec![Ok(GOOD), Ok(GOOD)]);
        assert_eq!(translate_checked(&engine, SOURCE, "th").await, None);
        assert_eq!(engine.calls(), MAX_ATTEMPTS);
    }

    #[test]
    fn korean_residue_is_a_warning_not_a_block() {
        let residue = Issue { severity: Severity::Warning, code: "hangul_left", message: String::new() };
        assert!(!is_blocking(&residue), "keep-with-gloss terms keep Hangul");
    }

    #[tokio::test]
    async fn answer_that_adds_a_contact_is_rejected() {
        let injected = format!("{GOOD} Call 010-1234-5678.");
        let engine = Scripted::new(vec![Ok(injected.as_str()), Ok(injected.as_str())]);
        assert_eq!(translate_checked(&engine, SOURCE, "en").await, None);
        assert_eq!(engine.calls(), MAX_ATTEMPTS);
    }

    #[test]
    fn contact_details_must_come_from_the_source() {
        assert!(adds_contact(SOURCE, "Call 010-1234-5678 for details"));
        assert!(adds_contact(SOURCE, "Apply at https://bit.ly/abc"));
        assert!(adds_contact(SOURCE, "Message @broker_kim"));
        assert!(adds_contact(SOURCE, "Email kim@example.com"));
        assert!(!adds_contact("문의 010-1234-5678", "Contact 010-1234-5678."));
        assert!(!adds_contact(SOURCE, GOOD), "prices and sentences are not contact details");
    }

    #[test]
    fn answer_far_longer_than_source_is_out_of_proportion() {
        assert!(!out_of_proportion(SOURCE, GOOD));
        assert!(out_of_proportion(SOURCE, &"a".repeat(400)));
    }

    #[test]
    fn secret_never_prints() {
        let s = Secret::new("sk-live-123".into());
        assert_eq!(format!("{s:?}"), "<redacted>");
        assert_eq!(s.expose(), "sk-live-123");
    }
}
