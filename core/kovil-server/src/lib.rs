//! KOVIL API server: Rust rewrite of the Spring Boot app, one domain at a time.
//! Reads the same MySQL schema. Flyway migrations stay owned by the Java app until cutover.

pub mod api;
pub mod area_index;
pub mod auth;
pub mod config;
pub mod domain;
pub mod geo;
pub mod i18n;

use std::sync::Arc;

use axum::Router;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, put};
use sqlx::MySqlPool;

use api::{ApiError, ErrorCode};
use i18n::{Lang, Messages};

#[derive(Clone)]
pub struct AppState {
    pub db: MySqlPool,
    pub messages: Arc<Messages>,
    pub app_policies: Arc<domain::app_version::PolicyCache>,
    /// Starts empty; main loads it and reloads it periodically (dml is applied after the app starts).
    pub areas: domain::area::SharedAreas,
    /// Default rejects every token; main installs the real verifier and revocation list.
    pub auth: Arc<auth::Auth>,
}

impl AppState {
    pub fn new(db: MySqlPool, messages: Messages) -> Self {
        Self { db, messages: Arc::new(messages), app_policies: Arc::default(), areas: Arc::default(), auth: Arc::default() }
    }

    pub fn with_auth(self, auth: auth::Auth) -> Self {
        Self { auth: Arc::new(auth), ..self }
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/api/v1/languages", get(domain::language::list))
        .route("/api/v1/countries", get(domain::country::list))
        .route("/api/v1/regions", get(domain::region::list))
        .route("/api/v1/universities", get(domain::university::find_within))
        .route("/api/v1/transit/subway-stations", get(domain::transit::find_within))
        .route("/api/v1/app-version/check", get(domain::app_version::check_handler))
        .route("/api/v1/areas/resolve", get(domain::area::resolve))
        .route("/api/v1/areas/dongs/{code}/boundary", get(domain::area::boundary))
        .route("/api/v1/regions/{code}/dongs", get(domain::area::list_in_region))
        .route("/api/v1/admin/app-update-policies/{app}", get(domain::app_update_admin::list))
        .route("/api/v1/admin/app-update-policies/{app}/{platform}", put(domain::app_update_admin::update))
        .route("/actuator/health", get(health))
        // Unknown path and wrong method both answer NOT_FOUND, as GlobalExceptionHandler.handleNoResource does.
        .fallback(not_found)
        .method_not_allowed_fallback(not_found)
        .with_state(state)
}

async fn not_found(State(s): State<AppState>, lang: Lang) -> ApiError {
    ApiError::new(&s, &lang, ErrorCode::NotFound)
}

async fn health(State(s): State<AppState>) -> Response {
    match sqlx::query("SELECT 1").execute(&s.db).await {
        Ok(_) => axum::Json(serde_json::json!({"status": "UP"})).into_response(),
        Err(e) => {
            tracing::warn!("health check failed: {e}");
            (StatusCode::SERVICE_UNAVAILABLE, axum::Json(serde_json::json!({"status": "DOWN"}))).into_response()
        }
    }
}
