//! `POST /api/v1/listings/{listingUuid}/description/translate?language=xx`. Mirrors `ListingController.translateDescription`
//! and `ListingTranslationService`.
//!
//! Check order matches Spring: 401 (signed-in filter) -> 429 (limits run before binding) -> 400 binding -> 400 language
//! -> 404 listing. Vendor failure is never an error: the response is the Korean original with `translated=false`.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use axum::Json;
use axum::extract::rejection::PathRejection;
use axum::extract::{Path, RawQuery, State};
use serde::Serialize;

use crate::AppState;
use crate::api::{ApiError, ApiResponse, ErrorCode, ok};
use crate::auth::Authenticated;
use crate::i18n::Lang;
use crate::ratelimit::{Admission, GLOBAL_KEY};
use crate::translation;

const SOURCE_LANGUAGE: &str = "ko";
/// Rows the fake engine wrote; only the fake engine may serve them (Java `FAKE_ENGINE`).
const FAKE_ENGINE: &str = "fake";

/// A listing whose translation just failed the quality gate (or the vendor) is not retried for a while: otherwise every
/// request for it pays up to two vendor calls and one hard listing can drain the global daily budget.
const FAILURE_TTL: Duration = Duration::from_secs(15 * 60);
/// Bounds memory if many distinct listings fail at once; expired entries are dropped first.
const FAILURE_CAP: usize = 10_000;

type FailureKey = (i64, String, i32);
static RECENT_FAILURES: LazyLock<Mutex<HashMap<FailureKey, Instant>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn failed_recently(key: &FailureKey, now: Instant) -> bool {
    let map = RECENT_FAILURES.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    map.get(key).is_some_and(|at| now.duration_since(*at) < FAILURE_TTL)
}

fn remember_failure(key: FailureKey, now: Instant) {
    let mut map = RECENT_FAILURES.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if map.len() >= FAILURE_CAP {
        map.retain(|_, at| now.duration_since(*at) < FAILURE_TTL);
        if map.len() >= FAILURE_CAP {
            map.clear();
        }
    }
    map.insert(key, now);
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TranslationResponse {
    pub language_code: String,
    pub content: Option<String>,
    pub engine: Option<String>,
    pub cached: bool,
    pub translated: bool,
}

impl TranslationResponse {
    fn original(language_code: &str, korean: Option<String>) -> Self {
        Self { language_code: language_code.to_owned(), content: korean, engine: None, cached: false, translated: false }
    }

    fn translated(language_code: &str, content: String, engine: Option<String>, cached: bool) -> Self {
        Self { language_code: language_code.to_owned(), content: Some(content), engine, cached, translated: true }
    }
}

/// Publicly visible listing: ACTIVE or RESERVED, posted by an approved, active broker of an office.
const FIND_PUBLIC: &str = "SELECT l.id, l.description, l.description_version FROM listings l \
    WHERE l.uuid = ? AND l.status IN ('ACTIVE', 'RESERVED') \
    AND EXISTS (SELECT 1 FROM broker_profiles p \
        JOIN partner_divisions d ON d.user_id = p.user_id \
        JOIN users u ON u.id = p.user_id \
        WHERE p.office_id = l.office_id AND d.division = 'REAL_ESTATE_BROKER' AND d.status = 'APPROVED' \
          AND u.deleted_at IS NULL AND u.status = 'ACTIVE')";

#[derive(sqlx::FromRow)]
struct ListingRow {
    id: i64,
    description: Option<String>,
    description_version: i32,
}

#[derive(sqlx::FromRow)]
struct CachedRow {
    content: String,
    engine: Option<String>,
}

/// Repeated `language` keys join with commas, as Spring binds a String parameter. A missing key is a malformed request.
fn language_param(raw_query: Option<&str>) -> Option<String> {
    let values: Vec<String> = url::form_urlencoded::parse(raw_query?.as_bytes())
        .filter(|(key, _)| key.as_ref() == "language")
        .map(|(_, value)| value.into_owned())
        .collect();
    (!values.is_empty()).then(|| values.join(","))
}

/// Trimmed and lower-cased, as Java's `normalize`. None when blank: that is LANGUAGE_NOT_SUPPORTED, not a binding error.
fn normalize_language(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    (!trimmed.is_empty()).then(|| trimmed.to_lowercase())
}

/// Java `isUsable`: a fake row serves only while the fake engine is configured; any other row is served as-is.
fn is_usable(row_engine: Option<&str>, current_engine: &str) -> bool {
    row_engine != Some(FAKE_ENGINE) || current_engine == FAKE_ENGINE
}

/// Unique key `uq_ldt (listing_id, language_code, source_version)` raised: the row exists already, or a constraint failed.
fn is_integrity_violation(e: &sqlx::Error) -> bool {
    e.as_database_error().and_then(|d| d.code()).is_some_and(|code| code.starts_with("23"))
}

pub async fn translate(
    Authenticated(user): Authenticated,
    State(s): State<AppState>,
    lang: Lang,
    path: Result<Path<String>, PathRejection>,
    query: RawQuery,
) -> Result<Json<ApiResponse<TranslationResponse>>, ApiError> {
    check_rate_limits(&s, &lang, &user.user_uuid).await?;
    let err = |c| ApiError::new(&s, &lang, c);
    let Path(listing_uuid) = path.map_err(|_| err(ErrorCode::InvalidRequest))?;
    let raw = language_param(query.0.as_deref()).ok_or_else(|| err(ErrorCode::InvalidRequest))?;
    let target = normalize_language(&raw).ok_or_else(|| err(ErrorCode::LanguageNotSupported))?;
    translate_listing(&s, &lang, &listing_uuid, &target).await.map(ok)
}

/// Java order on this route: backstop, then the per-user translate budget, then the global cap. Each spends a unit.
async fn check_rate_limits(s: &AppState, lang: &Lang, user_uuid: &str) -> Result<(), ApiError> {
    let rules = &s.limiter.rules;
    let checks = [
        (&rules.default_backstop, user_uuid),
        (&rules.listing_translate, user_uuid),
        (&rules.listing_translate_global, GLOBAL_KEY),
    ];
    for (rule, subject) in checks {
        match s.limiter.consume(rule, subject).await {
            Admission::Allowed => {}
            // Redis is down: stop here, or every later policy waits out its own timeout on the same request.
            Admission::FailedOpen => break,
            Admission::Denied(retry_after) => return Err(ApiError::rate_limited(s, lang, retry_after)),
        }
    }
    Ok(())
}

async fn translate_listing(s: &AppState, lang: &Lang, uuid: &str, target: &str) -> Result<TranslationResponse, ApiError> {
    let db_err = |e: sqlx::Error| ApiError::internal(s, lang, e);
    if !language_is_active(s, target).await.map_err(db_err)? {
        return Err(ApiError::new(s, lang, ErrorCode::LanguageNotSupported));
    }
    let listing = sqlx::query_as::<_, ListingRow>(FIND_PUBLIC)
        .bind(uuid)
        .fetch_optional(&s.db)
        .await
        .map_err(db_err)?
        .ok_or_else(|| ApiError::new(s, lang, ErrorCode::ListingNotFound))?;
    match listing.description {
        Some(korean) if target != SOURCE_LANGUAGE && !korean.trim().is_empty() => {
            cached_or_fresh(s, lang, (listing.id, listing.description_version), target, &korean).await
        }
        original => Ok(TranslationResponse::original(target, original)),
    }
}

async fn language_is_active(s: &AppState, code: &str) -> Result<bool, sqlx::Error> {
    sqlx::query("SELECT 1 FROM languages WHERE code = ? AND active = 1")
        .bind(code)
        .fetch_optional(&s.db)
        .await
        .map(|row| row.is_some())
}

/// `(listing_id, source_version)` is the cache key: a bumped description version is a miss by construction.
async fn cached_or_fresh(
    s: &AppState,
    lang: &Lang,
    (listing_id, version): (i64, i32),
    target: &str,
    korean: &str,
) -> Result<TranslationResponse, ApiError> {
    let db_err = |e: sqlx::Error| ApiError::internal(s, lang, e);
    if let Some(row) = find_row(s, listing_id, target, version).await.map_err(db_err)? {
        if is_usable(row.engine.as_deref(), s.translator.name()) {
            return Ok(TranslationResponse::translated(target, row.content, row.engine, true));
        }
        delete_row(s, listing_id, target, version).await.map_err(db_err)?;
    }
    let key = (listing_id, target.to_owned(), version);
    if failed_recently(&key, Instant::now()) {
        return Ok(TranslationResponse::original(target, Some(korean.to_owned())));
    }
    let Some(text) = translation::translate_checked(s.translator.as_ref(), korean, target).await else {
        remember_failure(key, Instant::now());
        return Ok(TranslationResponse::original(target, Some(korean.to_owned())));
    };
    save(s, lang, listing_id, target, version, text).await
}

async fn find_row(s: &AppState, listing_id: i64, target: &str, version: i32) -> Result<Option<CachedRow>, sqlx::Error> {
    sqlx::query_as::<_, CachedRow>(
        "SELECT content, engine FROM listing_description_translations \
         WHERE listing_id = ? AND language_code = ? AND source_version = ?",
    )
    .bind(listing_id)
    .bind(target)
    .bind(version)
    .fetch_optional(&s.db)
    .await
}

async fn delete_row(s: &AppState, listing_id: i64, target: &str, version: i32) -> Result<(), sqlx::Error> {
    // The row must go: the unique key would block the new save, and a stale row would otherwise be served again.
    sqlx::query("DELETE FROM listing_description_translations WHERE listing_id = ? AND language_code = ? AND source_version = ?")
        .bind(listing_id)
        .bind(target)
        .bind(version)
        .execute(&s.db)
        .await
        .map(|_| ())
}

async fn save(
    s: &AppState,
    lang: &Lang,
    listing_id: i64,
    target: &str,
    version: i32,
    text: String,
) -> Result<TranslationResponse, ApiError> {
    let engine = s.translator.name();
    // Seoul wall time, as the other writes in this server (JDBC serverTimezone=Asia/Seoul).
    let inserted = sqlx::query(
        "INSERT INTO listing_description_translations \
         (listing_id, language_code, source_version, content, engine, created_at) \
         VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6) + INTERVAL 9 HOUR)",
    )
    .bind(listing_id)
    .bind(target)
    .bind(version)
    .bind(&text)
    .bind(engine)
    .execute(&s.db)
    .await;
    match inserted {
        Ok(_) => Ok(TranslationResponse::translated(target, text, Some(engine.to_owned()), false)),
        Err(e) if is_integrity_violation(&e) => {
            // Two requests raced: serve the stored winner. If there is none, the write failed for another
            // constraint; still serve the checked text, just uncached.
            let db_err = |e: sqlx::Error| ApiError::internal(s, lang, e);
            match find_row(s, listing_id, target, version).await.map_err(db_err)? {
                Some(winner) => Ok(TranslationResponse::translated(target, winner.content, winner.engine, false)),
                None => {
                    tracing::warn!(lang = target, "translation cache write failed; serving uncached text");
                    Ok(TranslationResponse::translated(target, text, Some(engine.to_owned()), false))
                }
            }
        }
        Err(e) => Err(ApiError::internal(s, lang, e)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failures_expire_and_are_per_listing_language_version() {
        let t0 = Instant::now();
        let key = (-42_i64, "vi".to_owned(), 3);
        assert!(!failed_recently(&key, t0));
        remember_failure(key.clone(), t0);
        assert!(failed_recently(&key, t0 + Duration::from_secs(60)));
        assert!(!failed_recently(&(-42, "vi".to_owned(), 4), t0), "a new description version is tried again");
        assert!(!failed_recently(&(-42, "ne".to_owned(), 3), t0));
        assert!(!failed_recently(&key, t0 + FAILURE_TTL), "retried after the TTL");
    }

    #[test]
    fn language_param_joins_repeats_and_ignores_other_keys() {
        assert_eq!(language_param(Some("language=en")).as_deref(), Some("en"));
        assert_eq!(language_param(Some("x=1&language=vi&language=ko")).as_deref(), Some("vi,ko"));
        assert_eq!(language_param(Some("language=%20ja%20")).as_deref(), Some(" ja "));
        assert_eq!(language_param(Some("language=")).as_deref(), Some(""));
        assert_eq!(language_param(Some("other=1")), None);
        assert_eq!(language_param(None), None);
    }

    #[test]
    fn language_normalises_and_rejects_blank() {
        assert_eq!(normalize_language("  EN ").as_deref(), Some("en"));
        assert_eq!(normalize_language("zh-TW").as_deref(), Some("zh-tw"));
        assert_eq!(normalize_language(""), None);
        assert_eq!(normalize_language("   "), None);
    }

    #[test]
    fn fake_rows_are_served_only_by_the_fake_engine() {
        assert!(is_usable(Some("openai"), "gemini"));
        assert!(is_usable(None, "openai"));
        assert!(!is_usable(Some("fake"), "openai"));
        assert!(is_usable(Some("fake"), "fake"));
    }

    #[test]
    fn original_response_shape_matches_java() {
        let v = serde_json::to_value(TranslationResponse::original("ko", Some("본문".into()))).unwrap();
        assert_eq!(
            v,
            serde_json::json!({"languageCode": "ko", "content": "본문", "engine": null, "cached": false, "translated": false})
        );
    }
}
