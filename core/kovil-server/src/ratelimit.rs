//! Request limits in the same Redis counters the Java app uses, so a route served by either server draws from one budget.
//! Mirrors `common/ratelimit/RateLimitStore` (script, key format, fail-open) and the policy yaml in application.yaml.

use std::time::Duration;

use redis::aio::ConnectionManager;

use crate::auth::{REDIS_TIMEOUT, redis_manager};

/// Subject used by GLOBAL policies (`RateLimitKeyType.GLOBAL`).
pub const GLOBAL_KEY: &str = "global";
const KEY_PREFIX: &str = "ratelimit:";

/// `RateLimitStore.CONSUME_SCRIPT`, kept verbatim: INCR, arm the expiry on first hit, report TTL.
/// Returns `{1, ttl}` when allowed, `{0, ttl}` when over the limit. The counter also moves on denied calls.
const CONSUME: &str = r#"
local current = redis.call('INCR', KEYS[1])
if current == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('TTL', KEYS[1])
if ttl < 0 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
  ttl = tonumber(ARGV[1])
end
if current > tonumber(ARGV[2]) then
  return {0, ttl}
end
return {1, ttl}
"#;

/// One policy. `name` is the Java enum name lower-cased: it forms the Redis key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    pub name: &'static str,
    pub limit: u64,
    pub window: Duration,
}

impl Rule {
    fn key(&self, subject: &str) -> String {
        format!("{KEY_PREFIX}{}:{subject}", self.name)
    }
}

/// The policies this server applies. Defaults are the values in application.yaml.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rules {
    pub default_backstop: Rule,
    pub listing_translate: Rule,
    pub listing_translate_global: Rule,
}

impl Default for Rules {
    fn default() -> Self {
        Self {
            default_backstop: Rule { name: "default_backstop", limit: 120, window: Duration::from_secs(60) },
            listing_translate: Rule { name: "listing_translate", limit: 30, window: Duration::from_secs(3600) },
            listing_translate_global: Rule {
                name: "listing_translate_global",
                limit: 3000,
                window: Duration::from_secs(86_400),
            },
        }
    }
}

/// `1h`, `30m`, `1d`, `45s`: whole seconds, the units Spring's `Duration` binding accepts for these keys.
pub fn parse_window(raw: &str) -> Result<Duration, String> {
    let s = raw.trim();
    let (num, mult) = match s.as_bytes().last() {
        Some(b's') => (&s[..s.len() - 1], 1),
        Some(b'm') => (&s[..s.len() - 1], 60),
        Some(b'h') => (&s[..s.len() - 1], 3600),
        Some(b'd') => (&s[..s.len() - 1], 86_400),
        _ => return Err(format!("'{raw}' must be <n>s, <n>m, <n>h or <n>d")),
    };
    let n: u64 = num.parse().map_err(|_| format!("'{raw}' must start with a whole number"))?;
    n.checked_mul(mult).filter(|secs| *secs > 0).map(Duration::from_secs).ok_or_else(|| format!("'{raw}' must be positive"))
}

/// Outcome of one policy check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Admission {
    Allowed,
    /// Redis failed or timed out and the call proceeds. Later policies would fail the same way, so callers stop checking.
    FailedOpen,
    /// Policy spent: seconds until the window resets, at least 1.
    Denied(u64),
}

/// Shared counters. `redis: None` means limits are off (RATELIMIT_ENABLED=false, or tests).
pub struct RateLimiter {
    redis: Option<ConnectionManager>,
    pub rules: Rules,
}

impl RateLimiter {
    pub fn disabled() -> Self {
        Self { redis: None, rules: Rules::default() }
    }

    pub fn connect(redis_url: &str, rules: Rules) -> Result<Self, String> {
        Ok(Self { redis: Some(redis_manager(redis_url)?), rules })
    }

    /// Redis trouble fails open, as in Java. Reported as `FailedOpen`, not `Allowed`, so the caller can stop early.
    pub async fn consume(&self, rule: &Rule, subject: &str) -> Admission {
        let Some(conn) = &self.redis else { return Admission::Allowed };
        let mut conn = conn.clone();
        // EVAL rather than EVALSHA: the same script text, without the redis crate's `script` feature.
        let mut cmd = redis::cmd("EVAL");
        cmd.arg(CONSUME).arg(1).arg(rule.key(subject)).arg(rule.window.as_secs()).arg(rule.limit);
        let call = cmd.query_async::<Vec<i64>>(&mut conn);
        match tokio::time::timeout(REDIS_TIMEOUT, call).await {
            Ok(Ok(reply)) => verdict(rule, &reply),
            Ok(Err(e)) => {
                tracing::error!("[ratelimit] Redis failed, fail-open. policy={} error={e}", rule.name);
                Admission::FailedOpen
            }
            Err(_) => {
                tracing::error!("[ratelimit] Redis timed out, fail-open. policy={}", rule.name);
                Admission::FailedOpen
            }
        }
    }
}

/// Reads the script reply. Anything other than a two-element answer fails open, as `RateLimitStore` does.
fn verdict(rule: &Rule, reply: &[i64]) -> Admission {
    match reply {
        [1, _] => Admission::Allowed,
        // Denied with a TTL below 1 still tells the caller to wait one second, as Java's deny() does.
        [0, ttl] => Admission::Denied((*ttl).max(1) as u64),
        _ => {
            tracing::error!("[ratelimit] Lua reply invalid, fail-open. policy={} reply={reply:?}", rule.name);
            Admission::FailedOpen
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_matches_java_store() {
        let r = Rules::default();
        assert_eq!(r.listing_translate.key("user-uuid"), "ratelimit:listing_translate:user-uuid");
        assert_eq!(r.listing_translate_global.key(GLOBAL_KEY), "ratelimit:listing_translate_global:global");
        assert_eq!(r.default_backstop.key("u"), "ratelimit:default_backstop:u");
    }

    #[test]
    fn defaults_match_application_yaml() {
        let r = Rules::default();
        assert_eq!((r.listing_translate.limit, r.listing_translate.window.as_secs()), (30, 3600));
        assert_eq!((r.listing_translate_global.limit, r.listing_translate_global.window.as_secs()), (3000, 86_400));
        assert_eq!((r.default_backstop.limit, r.default_backstop.window.as_secs()), (120, 60));
    }

    #[test]
    fn windows_parse_like_spring_durations() {
        assert_eq!(parse_window("1h").unwrap(), Duration::from_secs(3600));
        assert_eq!(parse_window(" 1d ").unwrap(), Duration::from_secs(86_400));
        assert_eq!(parse_window("1m").unwrap(), Duration::from_secs(60));
        assert!(parse_window("0h").is_err());
        assert!(parse_window("1").is_err());
        assert!(parse_window("1ms").is_err(), "sub-second windows cannot be expressed in EXPIRE seconds");
        assert!(parse_window("h").is_err());
    }

    #[test]
    fn verdict_allows_denies_and_fails_open() {
        let rule = Rules::default().listing_translate;
        assert_eq!(verdict(&rule, &[1, 3599]), Admission::Allowed);
        assert_eq!(verdict(&rule, &[0, 42]), Admission::Denied(42));
        assert_eq!(verdict(&rule, &[0, -1]), Admission::Denied(1));
        assert_eq!(verdict(&rule, &[]), Admission::FailedOpen);
    }

    #[tokio::test]
    async fn disabled_limiter_never_denies() {
        let limiter = RateLimiter::disabled();
        let rule = limiter.rules.listing_translate.clone();
        for _ in 0..100 {
            assert_eq!(limiter.consume(&rule, "someone").await, Admission::Allowed);
        }
    }

    /// Runs only with KOVIL_TEST_REDIS_URL (CI sets it). Uses a fresh subject so reruns never collide.
    #[tokio::test]
    async fn redis_counter_denies_after_limit() {
        let Ok(url) = std::env::var("KOVIL_TEST_REDIS_URL") else {
            eprintln!("skipped: KOVIL_TEST_REDIS_URL not set");
            return;
        };
        let limiter = RateLimiter::connect(&url, Rules::default()).unwrap();
        let rule = Rule { name: "ratelimit_test", limit: 2, window: Duration::from_secs(60) };
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let subject = format!("t-{nanos}");
        assert_eq!(limiter.consume(&rule, &subject).await, Admission::Allowed);
        assert_eq!(limiter.consume(&rule, &subject).await, Admission::Allowed);
        let Admission::Denied(retry) = limiter.consume(&rule, &subject).await else { panic!("third call must be denied") };
        assert!((1..=60).contains(&retry), "retry-after {retry}");
    }
}
