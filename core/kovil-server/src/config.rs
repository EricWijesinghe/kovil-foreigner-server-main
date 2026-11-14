//! Environment config, validated at startup so a bad deploy fails fast instead of on first request.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

use crate::ratelimit::{Rule, Rules, parse_window};
use crate::translation::{GeminiSettings, OpenAiSettings, Provider, Secret};

/// Debug is hand-written: the URLs and the JWT secret are credentials.
pub struct Config {
    pub database_url: String,
    pub bind_addr: SocketAddr,
    pub messages_dir: PathBuf,
    pub db_max_connections: u32,
    /// How often the admin_dongs index is re-read (CD applies dml after the app is already running).
    pub area_reload: Duration,
    /// Base64 HMAC key shared with the Java app (`JWT_SECRET`); both must verify the same tokens.
    pub jwt_secret: String,
    pub jwt_issuer: String,
    /// Token revocation list (logout, role change). Same Redis as the Java app.
    pub redis_url: String,
    /// Listing description translation vendor (`TRANSLATION_PROVIDER`).
    pub translation: Provider,
    /// Request limits in the shared Redis counters. None = `RATELIMIT_ENABLED=false`.
    pub rate_limit: Option<Rules>,
}

impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("bind_addr", &self.bind_addr)
            .field("messages_dir", &self.messages_dir)
            .field("db_max_connections", &self.db_max_connections)
            .field("area_reload", &self.area_reload)
            .field("jwt_issuer", &self.jwt_issuer)
            .field("translation", &self.translation)
            .field("rate_limit", &self.rate_limit)
            .finish_non_exhaustive()
    }
}

const DEFAULT_BIND: &str = "0.0.0.0:8081";
const DEFAULT_AREA_RELOAD_SECS: u64 = 3600;
const MIN_AREA_RELOAD_SECS: u64 = 60;
/// Source-tree bundles for local runs from any directory. Deployments must set MESSAGES_DIR.
const DEFAULT_MESSAGES_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../src/main/resources/messages");
const DEFAULT_DB_MAX_CONNECTIONS: u32 = 10;
/// `jwt.issuer` in application.yaml.
const DEFAULT_JWT_ISSUER: &str = "kovil";
/// Defaults from application.yaml (`openai.*`, `translation.*`).
const DEFAULT_OPENAI_URL: &str = "https://api.openai.com";
const DEFAULT_OPENAI_TRANSLATION_MODEL: &str = "gpt-6-luna";
const DEFAULT_OPENAI_TRANSLATION_EFFORT: &str = "none";
const DEFAULT_OPENAI_MAX_TOKENS: u64 = 4000;
const DEFAULT_OPENAI_TIMEOUT_SECS: u64 = 30;
const DEFAULT_GEMINI_URL: &str = "https://generativelanguage.googleapis.com";
const DEFAULT_GEMINI_MODEL: &str = "gemini-3.1-flash-lite";
const DEFAULT_GEMINI_TIMEOUT_SECS: u64 = 20;

type Lookup<'a> = &'a dyn Fn(&str) -> Option<String>;

impl Config {
    pub fn from_env() -> Result<Self, String> {
        Self::from_lookup(|k| std::env::var(k).ok())
    }

    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> Result<Self, String> {
        let get: Lookup<'_> = &get;
        let database_url = get("DATABASE_URL")
            .filter(|v| v.starts_with("mysql://"))
            .ok_or("DATABASE_URL must be set to a mysql:// URL")?;
        let bind_addr = get("BIND_ADDR")
            .unwrap_or_else(|| DEFAULT_BIND.into())
            .parse()
            .map_err(|e| format!("BIND_ADDR: {e}"))?;
        let db_max_connections = match get("DB_MAX_CONNECTIONS") {
            Some(v) => v.parse().ok().filter(|n| *n > 0).ok_or("DB_MAX_CONNECTIONS must be a positive integer")?,
            None => DEFAULT_DB_MAX_CONNECTIONS,
        };
        let messages_dir = PathBuf::from(get("MESSAGES_DIR").unwrap_or_else(|| DEFAULT_MESSAGES_DIR.into()));
        let area_reload_secs = match get("AREA_RELOAD_SECS") {
            Some(v) => v
                .parse()
                .ok()
                .filter(|n| *n >= MIN_AREA_RELOAD_SECS)
                .ok_or(format!("AREA_RELOAD_SECS must be an integer >= {MIN_AREA_RELOAD_SECS}"))?,
            None => DEFAULT_AREA_RELOAD_SECS,
        };
        let area_reload = Duration::from_secs(area_reload_secs);
        let jwt_secret = get("JWT_SECRET").filter(|v| !v.trim().is_empty()).ok_or("JWT_SECRET must be set (same value as the Java app)")?;
        let jwt_issuer = get("JWT_ISSUER").unwrap_or_else(|| DEFAULT_JWT_ISSUER.into());
        let redis_url = get("REDIS_URL")
            // rediss:// would need the redis crate's TLS feature; prod Valkey is plain (application-prod.yml has no ssl).
            .filter(|v| v.starts_with("redis://"))
            .ok_or("REDIS_URL must be set to a redis:// URL (TLS not built in)")?;
        let translation = translation_provider(get)?;
        let rate_limit = rate_limit_rules(get)?;
        Ok(Self {
            database_url,
            bind_addr,
            messages_dir,
            db_max_connections,
            area_reload,
            jwt_secret,
            jwt_issuer,
            redis_url,
            translation,
            rate_limit,
        })
    }
}

/// Non-blank value, trimmed; blank counts as unset, as Spring's `${VAR:default}` does for these keys.
fn value(get: Lookup, key: &str) -> Option<String> {
    get(key).map(|v| v.trim().to_owned()).filter(|v| !v.is_empty())
}

fn text(get: Lookup, key: &str, default: &str) -> String {
    value(get, key).unwrap_or_else(|| default.to_owned())
}

fn flag(get: Lookup, key: &str, default: bool) -> Result<bool, String> {
    match value(get, key).as_deref() {
        None => Ok(default),
        Some("true") => Ok(true),
        Some("false") => Ok(false),
        Some(_) => Err(format!("{key} must be true or false")),
    }
}

/// Positive integer, or the default when unset.
fn positive(get: Lookup, key: &str, default: u64) -> Result<u64, String> {
    match value(get, key) {
        None => Ok(default),
        Some(v) => v.parse::<u64>().ok().filter(|n| *n > 0).ok_or(format!("{key} must be a positive integer")),
    }
}

fn http_url(get: Lookup, key: &str, default: &str) -> Result<String, String> {
    let url = text(get, key, default);
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err(format!("{key} must be an http:// or https:// URL"));
    }
    Ok(url.trim_end_matches('/').to_owned())
}

fn required_secret(get: Lookup, key: &str, provider: &str) -> Result<Secret, String> {
    value(get, key).map(Secret::new).ok_or_else(|| format!("{key} must be set when TRANSLATION_PROVIDER={provider}"))
}

fn translation_provider(get: Lookup) -> Result<Provider, String> {
    match text(get, "TRANSLATION_PROVIDER", "fake").as_str() {
        "fake" => Ok(Provider::Fake),
        "openai" => Ok(Provider::OpenAi(OpenAiSettings {
            api_url: http_url(get, "OPENAI_API_URL", DEFAULT_OPENAI_URL)?,
            api_key: required_secret(get, "OPENAI_API_KEY", "openai")?,
            model: text(get, "OPENAI_TRANSLATION_MODEL", DEFAULT_OPENAI_TRANSLATION_MODEL),
            reasoning_effort: match get("OPENAI_TRANSLATION_REASONING_EFFORT") {
                None => Some(DEFAULT_OPENAI_TRANSLATION_EFFORT.to_owned()),
                // Blank = not sent, so the model's own default applies (that default may bill reasoning tokens).
                Some(v) => Some(v.trim().to_owned()).filter(|v| !v.is_empty()),
            },
            max_completion_tokens: u32::try_from(positive(get, "OPENAI_TRANSLATION_MAX_COMPLETION_TOKENS", DEFAULT_OPENAI_MAX_TOKENS)?)
                .map_err(|_| "OPENAI_TRANSLATION_MAX_COMPLETION_TOKENS is too large".to_owned())?,
            timeout: Duration::from_secs(positive(get, "OPENAI_TRANSLATION_TIMEOUT_SECONDS", DEFAULT_OPENAI_TIMEOUT_SECS)?),
        })),
        "gemini" => Ok(Provider::Gemini(GeminiSettings {
            api_url: http_url(get, "GEMINI_API_URL", DEFAULT_GEMINI_URL)?,
            api_key: required_secret(get, "GEMINI_API_KEY", "gemini")?,
            model: text(get, "GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
            timeout: Duration::from_secs(positive(get, "GEMINI_TIMEOUT_SECONDS", DEFAULT_GEMINI_TIMEOUT_SECS)?),
        })),
        other => Err(format!("TRANSLATION_PROVIDER must be fake, openai or gemini (got '{other}')")),
    }
}

fn rate_limit_rules(get: Lookup) -> Result<Option<Rules>, String> {
    if !flag(get, "RATELIMIT_ENABLED", true)? {
        return Ok(None);
    }
    let d = Rules::default();
    Ok(Some(Rules {
        default_backstop: rule(get, "RL_BACKSTOP", d.default_backstop)?,
        listing_translate: rule(get, "RL_LISTING_TRANSLATE", d.listing_translate)?,
        listing_translate_global: rule(get, "RL_LISTING_TRANSLATE_GLOBAL", d.listing_translate_global)?,
    }))
}

/// `<prefix>_LIMIT` and `<prefix>_WINDOW`, the names application.yaml reads for each policy.
fn rule(get: Lookup, prefix: &str, default: Rule) -> Result<Rule, String> {
    let limit = positive(get, &format!("{prefix}_LIMIT"), default.limit)?;
    let window = match value(get, &format!("{prefix}_WINDOW")) {
        Some(v) => parse_window(&v).map_err(|e| format!("{prefix}_WINDOW: {e}"))?,
        None => default.window,
    };
    Ok(Rule { limit, window, ..default })
}

#[cfg(test)]
mod tests {
    use super::*;

    const AUTH: [(&str, &str); 2] = [("JWT_SECRET", "s"), ("REDIS_URL", "redis://127.0.0.1:6379")];

    fn cfg(pairs: &[(&str, &str)]) -> Result<Config, String> {
        Config::from_lookup(|k| pairs.iter().chain(AUTH.iter()).find(|(n, _)| *n == k).map(|(_, v)| (*v).to_owned()))
    }

    #[test]
    fn requires_mysql_url() {
        assert!(cfg(&[]).is_err());
        assert!(cfg(&[("DATABASE_URL", "postgres://x")]).is_err());
        let c = cfg(&[("DATABASE_URL", "mysql://u:p@h/db")]).unwrap();
        assert_eq!(c.bind_addr.port(), 8081);
        assert_eq!(c.db_max_connections, 10);
    }

    #[test]
    fn rejects_bad_numbers() {
        assert!(cfg(&[("DATABASE_URL", "mysql://h/db"), ("BIND_ADDR", "nope")]).is_err());
        assert!(cfg(&[("DATABASE_URL", "mysql://h/db"), ("DB_MAX_CONNECTIONS", "0")]).is_err());
        assert!(cfg(&[("DATABASE_URL", "mysql://h/db"), ("AREA_RELOAD_SECS", "5")]).is_err());
        assert_eq!(cfg(&[("DATABASE_URL", "mysql://h/db")]).unwrap().area_reload.as_secs(), 3600);
    }

    #[test]
    fn requires_jwt_secret_and_redis() {
        let only = |pairs: &[(&str, &str)]| Config::from_lookup(|k| pairs.iter().find(|(n, _)| *n == k).map(|(_, v)| (*v).to_owned()));
        assert!(only(&[("DATABASE_URL", "mysql://h/db"), ("REDIS_URL", "redis://h")]).is_err());
        assert!(only(&[("DATABASE_URL", "mysql://h/db"), ("JWT_SECRET", "s")]).is_err());
        assert!(only(&[("DATABASE_URL", "mysql://h/db"), ("JWT_SECRET", "s"), ("REDIS_URL", "http://h")]).is_err());
        assert!(only(&[("DATABASE_URL", "mysql://h/db"), ("JWT_SECRET", "s"), ("REDIS_URL", "rediss://h")]).is_err());
        assert_eq!(cfg(&[("DATABASE_URL", "mysql://h/db")]).unwrap().jwt_issuer, "kovil");
        let shown = format!("{:?}", cfg(&[("DATABASE_URL", "mysql://u:pw@h/db")]).unwrap());
        assert!(!shown.contains("pw") && !shown.contains("redis://"), "credentials never in Debug: {shown}");
    }

    const DB: (&str, &str) = ("DATABASE_URL", "mysql://h/db");

    #[test]
    fn translation_defaults_to_fake_with_limits_on() {
        let c = cfg(&[DB]).unwrap();
        assert!(matches!(c.translation, Provider::Fake));
        assert_eq!(c.rate_limit, Some(Rules::default()));
    }

    #[test]
    fn openai_needs_a_key_and_never_prints_it() {
        assert!(cfg(&[DB, ("TRANSLATION_PROVIDER", "openai")]).is_err());
        let c = cfg(&[DB, ("TRANSLATION_PROVIDER", "openai"), ("OPENAI_API_KEY", "sk-secret-value")]).unwrap();
        let Provider::OpenAi(s) = &c.translation else { panic!("provider") };
        assert_eq!((s.model.as_str(), s.reasoning_effort.as_deref(), s.max_completion_tokens), ("gpt-6-luna", Some("none"), 4000));
        assert_eq!(s.api_url, "https://api.openai.com");
        assert_eq!(s.timeout, Duration::from_secs(30));
        assert!(!format!("{:?}", c).contains("sk-secret-value"));
    }

    #[test]
    fn blank_reasoning_effort_is_not_sent() {
        let c = cfg(&[DB, ("TRANSLATION_PROVIDER", "openai"), ("OPENAI_API_KEY", "k"), ("OPENAI_TRANSLATION_REASONING_EFFORT", " ")]).unwrap();
        let Provider::OpenAi(s) = c.translation else { panic!("provider") };
        assert_eq!(s.reasoning_effort, None);
    }

    #[test]
    fn gemini_settings_and_validation() {
        let c = cfg(&[DB, ("TRANSLATION_PROVIDER", "gemini"), ("GEMINI_API_KEY", "g-secret"), ("GEMINI_API_URL", "https://gen.example/")]).unwrap();
        let Provider::Gemini(s) = &c.translation else { panic!("provider") };
        assert_eq!((s.api_url.as_str(), s.model.as_str(), s.timeout), ("https://gen.example", "gemini-3.1-flash-lite", Duration::from_secs(20)));
        assert!(!format!("{:?}", c).contains("g-secret"));
        assert!(cfg(&[DB, ("TRANSLATION_PROVIDER", "gemini")]).is_err());
        assert!(cfg(&[DB, ("TRANSLATION_PROVIDER", "gemini"), ("GEMINI_API_KEY", "k"), ("GEMINI_API_URL", "gen.example")]).is_err());
        assert!(cfg(&[DB, ("TRANSLATION_PROVIDER", "gemini"), ("GEMINI_API_KEY", "k"), ("GEMINI_TIMEOUT_SECONDS", "0")]).is_err());
    }

    #[test]
    fn unknown_provider_and_bad_limits_fail_fast() {
        assert!(cfg(&[DB, ("TRANSLATION_PROVIDER", "bogus")]).is_err());
        assert!(cfg(&[DB, ("RL_LISTING_TRANSLATE_WINDOW", "5ms")]).is_err());
        assert!(cfg(&[DB, ("RL_LISTING_TRANSLATE_LIMIT", "0")]).is_err());
        assert!(cfg(&[DB, ("RATELIMIT_ENABLED", "yes")]).is_err());
        assert_eq!(cfg(&[DB, ("RATELIMIT_ENABLED", "false")]).unwrap().rate_limit, None);
        let c = cfg(&[DB, ("RL_LISTING_TRANSLATE_LIMIT", "5"), ("RL_LISTING_TRANSLATE_GLOBAL_WINDOW", "2d")]).unwrap();
        let rules = c.rate_limit.unwrap();
        assert_eq!((rules.listing_translate.limit, rules.listing_translate.window.as_secs()), (5, 3600));
        assert_eq!(rules.listing_translate_global.window.as_secs(), 2 * 86_400);
    }
}
