//! `GET /api/v1/admin/app-update-policies/{app}` and `PUT .../{app}/{platform}` (PERM_APP_POLICY_MANAGE).
//! Mirrors `AdminAppUpdateController` / `AdminAppUpdateService`.
//!
//! Check order matches Spring: 401 (filter) -> 400 path/body binding -> 403 (@PreAuthorize runs after binding)
//! -> 404 unknown platform -> 400 validation.

use axum::Json;
use axum::extract::rejection::{JsonRejection, PathRejection};
use axum::extract::{Path, State};
use serde::{Deserialize, Serialize};

use super::app_version::parse_version;
use crate::AppState;
use crate::api::{ApiError, ApiResponse, ErrorCode, ok};
use crate::auth::{Authenticated, Permission};
use crate::i18n::Lang;

const APPS: [&str; 2] = ["STUDENT", "PARTNER"];
/// Static SQL (sqlx refuses runtime-built strings). Jackson writes LocalDateTime as ISO without zone; the column
/// has no fraction.
macro_rules! select {
    ($where:literal) => {
        concat!(
            "SELECT id, app, platform, min_supported_version, latest_version, store_url, ",
            "DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s') AS updated_at FROM app_update_policies ",
            $where
        )
    };
}

#[derive(Debug, sqlx::FromRow, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AdminPolicy {
    #[serde(skip)]
    pub id: i64,
    pub app: String,
    pub platform: String,
    pub min_supported_version: String,
    pub latest_version: String,
    pub store_url: String,
    pub updated_at: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateRequest {
    pub min_supported_version: Option<String>,
    pub latest_version: Option<String>,
    pub store_url: Option<String>,
}

/// Spring's String -> enum binding: trimmed, case-sensitive.
fn app_kind(raw: &str) -> Option<&'static str> {
    APPS.into_iter().find(|a| *a == raw.trim())
}

/// Returns the values to store: (min, latest, storeUrl), all trimmed.
pub fn validate(req: &UpdateRequest) -> Result<(String, String, String), ErrorCode> {
    let min = req.min_supported_version.as_deref().map(str::trim);
    let latest = req.latest_version.as_deref().map(str::trim);
    let (Some(min), Some(latest)) = (min, latest) else { return Err(ErrorCode::AppUpdateVersionInvalid) };
    let (Some(lo), Some(hi)) = (parse_version(min), parse_version(latest)) else {
        return Err(ErrorCode::AppUpdateVersionInvalid);
    };
    // Saving min > latest would trap even up-to-date users on a forced screen with nothing to download.
    if lo > hi {
        return Err(ErrorCode::AppUpdateMinAboveLatest);
    }
    let store = req.store_url.as_deref().unwrap_or("").trim();
    if !store.is_empty() && !store.starts_with("https://") {
        return Err(ErrorCode::AppUpdateStoreUrlInvalid);
    }
    Ok((min.to_owned(), latest.to_owned(), store.to_owned()))
}

pub async fn list(
    Authenticated(user): Authenticated,
    State(s): State<AppState>,
    lang: Lang,
    path: Result<Path<String>, PathRejection>,
) -> Result<Json<ApiResponse<Vec<AdminPolicy>>>, ApiError> {
    let err = |c| ApiError::new(&s, &lang, c);
    let app = path.ok().and_then(|Path(a)| app_kind(&a)).ok_or_else(|| err(ErrorCode::InvalidRequest))?;
    user.require(&s, &lang, Permission::AppPolicyManage)?;
    let rows = sqlx::query_as::<_, AdminPolicy>(select!("WHERE app = ? ORDER BY platform"))
        .bind(app)
        .fetch_all(&s.db)
        .await
        .map_err(|e| ApiError::internal(&s, &lang, e))?;
    Ok(ok(rows))
}

pub async fn update(
    Authenticated(user): Authenticated,
    State(s): State<AppState>,
    lang: Lang,
    path: Result<Path<(String, String)>, PathRejection>,
    body: Result<Json<UpdateRequest>, JsonRejection>,
) -> Result<Json<ApiResponse<AdminPolicy>>, ApiError> {
    let err = |c| ApiError::new(&s, &lang, c);
    let (app, platform) = match path {
        Ok(Path((a, p))) => (app_kind(&a).ok_or_else(|| err(ErrorCode::InvalidRequest))?, p.trim().to_uppercase()),
        Err(_) => return Err(err(ErrorCode::InvalidRequest)),
    };
    let Json(req) = body.map_err(|_| err(ErrorCode::InvalidRequest))?;
    user.require(&s, &lang, Permission::AppPolicyManage)?;
    let db_err = |e: sqlx::Error| ApiError::internal(&s, &lang, e);
    let row = sqlx::query_as::<_, AdminPolicy>(select!("WHERE app = ? AND platform = ?"))
        .bind(app)
        .bind(&platform)
        .fetch_optional(&s.db)
        .await
        .map_err(db_err)?
        .ok_or_else(|| err(ErrorCode::AppUpdatePlatformInvalid))?;
    let (min, latest, store) = validate(&req).map_err(err)?;
    // Datetimes are stored as Seoul wall time (JDBC serverTimezone=Asia/Seoul); Korea has no DST.
    sqlx::query(
        "UPDATE app_update_policies SET min_supported_version = ?, latest_version = ?, store_url = ?, \
         updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR WHERE id = ?",
    )
    .bind(min)
    .bind(latest)
    .bind(store)
    .bind(row.id)
    .execute(&s.db)
    .await
    .map_err(db_err)?;
    s.app_policies.clear();
    tracing::info!(admin = user.user_uuid, app, platform, "app update policy changed");
    // Unlike Java (which answers with the pre-flush updatedAt), this returns the row as stored.
    let saved = sqlx::query_as::<_, AdminPolicy>(select!("WHERE id = ?"))
        .bind(row.id)
        .fetch_one(&s.db)
        .await
        .map_err(db_err)?;
    Ok(ok(saved))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(min: Option<&str>, latest: Option<&str>, url: Option<&str>) -> UpdateRequest {
        UpdateRequest {
            min_supported_version: min.map(Into::into),
            latest_version: latest.map(Into::into),
            store_url: url.map(Into::into),
        }
    }

    #[test]
    fn validation_matches_admin_service() {
        assert_eq!(validate(&req(Some(" 1.0.0 "), Some("1.2"), None)), Ok(("1.0.0".into(), "1.2".into(), String::new())));
        assert_eq!(validate(&req(None, Some("1.2.0"), None)), Err(ErrorCode::AppUpdateVersionInvalid));
        assert_eq!(validate(&req(Some("v1"), Some("1.2.0"), None)), Err(ErrorCode::AppUpdateVersionInvalid));
        assert_eq!(validate(&req(Some("2.0.0"), Some("1.9.9"), None)), Err(ErrorCode::AppUpdateMinAboveLatest));
        assert_eq!(validate(&req(Some("1.0.0"), Some("1.0.0"), Some("http://x"))), Err(ErrorCode::AppUpdateStoreUrlInvalid));
        assert_eq!(validate(&req(Some("1.0.0"), Some("1.0.0"), Some(" https://x "))).unwrap().2, "https://x");
    }

    #[test]
    fn app_binding_is_trimmed_and_case_sensitive() {
        assert_eq!(app_kind(" STUDENT "), Some("STUDENT"));
        assert_eq!(app_kind("student"), None);
    }
}
