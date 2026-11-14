use std::process::ExitCode;
use std::time::Duration;

use sqlx::mysql::MySqlPoolOptions;
use tracing_subscriber::EnvFilter;

use kovil_server::auth::{Auth, JwtVerifier, Revocations};
use kovil_server::config::Config;
use kovil_server::domain::area;
use kovil_server::i18n::Messages;
use kovil_server::ratelimit::RateLimiter;
use kovil_server::translation;
use kovil_server::{AppState, router};

/// Startup and per-request wait for a DB connection: a dead database should fail the deploy, not hang it.
const DB_ACQUIRE_TIMEOUT: Duration = Duration::from_secs(5);
/// While the area index is empty, reload this often.
const AREA_EMPTY_RETRY: Duration = Duration::from_secs(60);

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt().with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into())).init();
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!("{e}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), String> {
    let cfg = Config::from_env()?;
    let messages = Messages::load(&cfg.messages_dir).map_err(|e| format!("messages: {e}"))?;
    let db = MySqlPoolOptions::new()
        .max_connections(cfg.db_max_connections)
        .acquire_timeout(DB_ACQUIRE_TIMEOUT)
        .connect(&cfg.database_url)
        .await
        .map_err(|e| format!("database: {e}"))?;
    let auth = Auth {
        jwt: Some(JwtVerifier::new(&cfg.jwt_secret, &cfg.jwt_issuer)?),
        revocations: Some(Revocations::lazy(&cfg.redis_url)?),
    };
    let translator = translation::build(&cfg.translation)?;
    let limiter = match &cfg.rate_limit {
        Some(rules) => RateLimiter::connect(&cfg.redis_url, rules.clone())?,
        None => RateLimiter::disabled(),
    };
    tracing::info!(translation = cfg.translation.name(), rate_limits = cfg.rate_limit.is_some(), "features configured");
    let state = AppState::new(db, messages).with_auth(auth).with_translator(translator).with_rate_limiter(limiter);
    area::reload(&state.db, &state.areas).await;
    let (db, areas) = (state.db.clone(), state.areas.clone());
    tokio::spawn(async move {
        loop {
            // Empty (first deploy: CD applies dml/08 after the app starts) -> retry soon instead of an hour of 500s.
            let empty = areas.read().map(|g| g.index.is_empty()).unwrap_or(true);
            tokio::time::sleep(if empty { AREA_EMPTY_RETRY } else { cfg.area_reload }).await;
            area::reload(&db, &areas).await;
        }
    });
    let listener = tokio::net::TcpListener::bind(cfg.bind_addr).await.map_err(|e| format!("bind {}: {e}", cfg.bind_addr))?;
    tracing::info!("listening on {}", cfg.bind_addr);
    axum::serve(listener, router(state))
        .with_graceful_shutdown(async {
            // If the signal handler cannot be installed, keep serving rather than exit.
            if tokio::signal::ctrl_c().await.is_err() {
                std::future::pending::<()>().await;
            }
        })
        .await
        .map_err(|e| format!("server: {e}"))
}
