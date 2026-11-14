//! Environment config, validated at startup so a bad deploy fails fast instead of on first request.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

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
}

impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("bind_addr", &self.bind_addr)
            .field("messages_dir", &self.messages_dir)
            .field("db_max_connections", &self.db_max_connections)
            .field("area_reload", &self.area_reload)
            .field("jwt_issuer", &self.jwt_issuer)
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

impl Config {
    pub fn from_env() -> Result<Self, String> {
        Self::from_lookup(|k| std::env::var(k).ok())
    }

    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> Result<Self, String> {
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
        Ok(Self { database_url, bind_addr, messages_dir, db_max_connections, area_reload, jwt_secret, jwt_issuer, redis_url })
    }
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
}
