//! `GET /api/v1/app-version/check?app&platform&version`. Mirrors `domain/appversion` (AppVersionService.check).
//!
//! Fail-open by contract: every binary already in the stores calls this, so bad input, a missing policy, a broken
//! policy or a DB failure all answer `OK` with HTTP 200. This endpoint never returns 400.

use std::collections::HashMap;
use std::sync::RwLock;
use std::time::{Duration, Instant};

use axum::Json;
use axum::extract::rejection::QueryRejection;
use axum::extract::{Query, State};
use serde::Serialize;

use crate::AppState;
use crate::api::{ApiResponse, ok};

const CACHE_TTL: Duration = Duration::from_secs(60);
const PLATFORMS: [&str; 2] = ["IOS", "ANDROID"];
const APPS: [&str; 2] = ["STUDENT", "PARTNER"];
const DEFAULT_APP: &str = "STUDENT";

#[derive(Debug, Clone, sqlx::FromRow)]
pub struct PolicyRow {
    pub app: String,
    pub platform: String,
    pub min_supported_version: String,
    pub latest_version: String,
    pub store_url: String,
}

/// (app, platform) as stored, e.g. ("STUDENT", "IOS").
pub type Policies = HashMap<(String, String), PolicyRow>;

/// Policies for 60 s (Java `AppVersionService.cached`). A failed read is not cached, so the next call retries.
#[derive(Default)]
pub struct PolicyCache(RwLock<Option<(Instant, Policies)>>);

impl PolicyCache {
    /// After an admin edit, so this instance applies it on the next check (Java evicts after commit).
    pub fn clear(&self) {
        if let Ok(mut guard) = self.0.write() {
            *guard = None;
        }
    }
}

#[derive(Debug, Default)]
pub struct CheckParams {
    pub app: Option<String>,
    pub platform: Option<String>,
    pub version: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Verdict {
    Force,
    Recommend,
    Ok,
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AppVersionCheckResponse {
    pub verdict: Verdict,
    pub latest_version: Option<String>,
    pub store_url: Option<String>,
}

/// "1.2.3", "1.2", "1.2.3-beta" -> [1,2,3] / [1,2,0] / [1,2,3]. Anything else -> None (Java `parse`).
// shortcut: ASCII digits only; Java's Character.isDigit also accepts other Unicode digits. Upgrade if a binary ever sends them.
pub fn parse_version(raw: &str) -> Option<[i64; 3]> {
    let t = raw.trim();
    if !t.starts_with(|c: char| c.is_ascii_digit()) {
        return None;
    }
    let end = t.find(|c: char| !(c.is_ascii_digit() || c == '.')).unwrap_or(t.len());
    let core = &t[..end];
    if core.ends_with('.') || core.contains("..") {
        return None;
    }
    let mut out = [0i64; 3];
    for (slot, part) in out.iter_mut().zip(core.split('.')) {
        *slot = part.parse().ok()?;
    }
    Some(out)
}

pub fn check(p: &CheckParams, policies: &Policies) -> AppVersionCheckResponse {
    let pass = |latest: Option<String>, store: Option<String>| AppVersionCheckResponse { verdict: Verdict::Ok, latest_version: latest, store_url: store };
    let app = match p.app.as_deref().map(str::trim) {
        None | Some("") => DEFAULT_APP.to_owned(),
        Some(a) => a.to_uppercase(),
    };
    let platform = p.platform.as_deref().map(|s| s.trim().to_uppercase()).unwrap_or_default();
    if !APPS.contains(&app.as_str()) || !PLATFORMS.contains(&platform.as_str()) {
        return pass(None, None);
    }
    let Some(policy) = policies.get(&(app, platform.clone())) else {
        return pass(None, None);
    };
    let store = Some(policy.store_url.trim()).filter(|s| !s.is_empty()).map(str::to_owned);
    let latest = Some(policy.latest_version.clone());
    let current = p.version.as_deref().and_then(parse_version);
    let (Some(cur), Some(min), Some(max)) = (current, parse_version(&policy.min_supported_version), parse_version(&policy.latest_version)) else {
        tracing::warn!(platform, version = ?p.version, min = policy.min_supported_version, latest = policy.latest_version, "version unparseable, passing OK");
        return pass(latest, store);
    };
    if min > max {
        tracing::error!(platform, min = policy.min_supported_version, latest = policy.latest_version, "min > latest misconfiguration, suppressing");
        return pass(latest, store);
    }
    let verdict = if cur < min {
        Verdict::Force
    } else if cur < max {
        Verdict::Recommend
    } else {
        Verdict::Ok
    };
    // A forced screen with nowhere to go is a lockout.
    if verdict != Verdict::Ok && store.is_none() {
        tracing::error!(platform, ?verdict, "policy has no store URL, suppressing");
        return pass(latest, store);
    }
    AppVersionCheckResponse { verdict, latest_version: latest, store_url: store }
}

async fn policies(s: &AppState) -> Policies {
    if let Ok(guard) = s.app_policies.0.read()
        && let Some((at, p)) = guard.as_ref()
        && at.elapsed() < CACHE_TTL
    {
        return p.clone();
    }
    let rows = sqlx::query_as::<_, PolicyRow>(
        "SELECT app, platform, min_supported_version, latest_version, store_url FROM app_update_policies ORDER BY id",
    )
    .fetch_all(&s.db)
    .await;
    match rows {
        Ok(rows) => {
            let mut map = Policies::new();
            for r in rows {
                map.entry((r.app.clone(), r.platform.clone())).or_insert(r);
            }
            if let Ok(mut guard) = s.app_policies.0.write() {
                *guard = Some((Instant::now(), map.clone()));
            }
            map
        }
        Err(e) => {
            tracing::error!("app_update_policies read failed, passing everyone OK: {e}");
            Policies::new()
        }
    }
}

impl CheckParams {
    /// Repeated keys are comma-joined, as Spring binds `?version=1.0&version=2.0` to `"1.0,2.0"`.
    pub fn from_pairs(pairs: &[(String, String)]) -> Self {
        let get = |key: &str| {
            let vals: Vec<&str> = pairs.iter().filter(|(k, _)| k == key).map(|(_, v)| v.as_str()).collect();
            (!vals.is_empty()).then(|| vals.join(","))
        };
        Self { app: get("app"), platform: get("platform"), version: get("version") }
    }
}

pub async fn check_handler(
    State(s): State<AppState>,
    query: Result<Query<Vec<(String, String)>>, QueryRejection>,
) -> Json<ApiResponse<AppVersionCheckResponse>> {
    let params = query.map(|Query(pairs)| CheckParams::from_pairs(&pairs)).unwrap_or_default();
    ok(check(&params, &policies(&s).await))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policies(app: &str, platform: &str, min: &str, latest: &str, url: &str) -> Policies {
        let row = PolicyRow {
            app: app.into(),
            platform: platform.into(),
            min_supported_version: min.into(),
            latest_version: latest.into(),
            store_url: url.into(),
        };
        HashMap::from([((app.to_owned(), platform.to_owned()), row)])
    }

    fn params(app: Option<&str>, platform: Option<&str>, version: Option<&str>) -> CheckParams {
        CheckParams { app: app.map(Into::into), platform: platform.map(Into::into), version: version.map(Into::into) }
    }

    const URL: &str = "https://apps.apple.com/app/id1";

    #[test]
    fn parse_matches_java() {
        assert_eq!(parse_version("1.2.3"), Some([1, 2, 3]));
        assert_eq!(parse_version(" 1.2 "), Some([1, 2, 0]));
        assert_eq!(parse_version("2"), Some([2, 0, 0]));
        assert_eq!(parse_version("1.2.3-beta (45)"), Some([1, 2, 3]));
        assert_eq!(parse_version("1.2.3.4"), Some([1, 2, 3]));
        for bad in ["", "v1.2", "1.", "1..2", ".1", "99999999999999999999.0"] {
            assert_eq!(parse_version(bad), None, "{bad}");
        }
    }

    #[test]
    fn verdicts() {
        let p = policies("STUDENT", "IOS", "1.1.0", "1.3.0", URL);
        let v = |ver| check(&params(None, Some("ios"), Some(ver)), &p).verdict;
        assert_eq!(v("1.0.9"), Verdict::Force);
        assert_eq!(v("1.1.0"), Verdict::Recommend);
        assert_eq!(v("1.2.99"), Verdict::Recommend);
        assert_eq!(v("1.3.0"), Verdict::Ok);
        assert_eq!(v("2.0"), Verdict::Ok);
        let r = check(&params(None, Some("ios"), Some("1.0.0")), &p);
        assert_eq!((r.latest_version.as_deref(), r.store_url.as_deref()), (Some("1.3.0"), Some(URL)));
    }

    #[test]
    fn fail_open_cases() {
        let p = policies("PARTNER", "ANDROID", "2.0.0", "2.0.0", URL);
        let ok_none = AppVersionCheckResponse { verdict: Verdict::Ok, latest_version: None, store_url: None };
        // Missing app means STUDENT, which has no policy here.
        assert_eq!(check(&params(None, Some("android"), Some("1.0")), &p), ok_none);
        assert_eq!(check(&params(Some("ADMIN"), Some("android"), Some("1.0")), &p), ok_none);
        assert_eq!(check(&params(Some("partner"), Some("web"), Some("1.0")), &p), ok_none);
        assert_eq!(check(&params(Some("partner"), None, Some("1.0")), &p), ok_none);
        assert_eq!(check(&params(Some(" partner "), Some("android"), Some("1.0")), &p).verdict, Verdict::Force);
        // Unreadable client version: OK, but latest/store still returned.
        let r = check(&params(Some("partner"), Some("android"), Some("abc")), &p);
        assert_eq!((r.verdict, r.latest_version.as_deref()), (Verdict::Ok, Some("2.0.0")));
    }

    #[test]
    fn misconfigured_policies_are_suppressed() {
        let inverted = policies("STUDENT", "IOS", "2.0.0", "1.0.0", URL);
        assert_eq!(check(&params(None, Some("ios"), Some("0.1")), &inverted).verdict, Verdict::Ok);
        let no_url = policies("STUDENT", "IOS", "2.0.0", "2.0.0", "  ");
        let r = check(&params(None, Some("ios"), Some("0.1")), &no_url);
        assert_eq!((r.verdict, r.store_url), (Verdict::Ok, None));
    }

    #[test]
    fn repeated_params_join_like_spring() {
        let pairs: Vec<(String, String)> =
            [("version", "0.1"), ("version", "0.1"), ("platform", "ios")].iter().map(|(k, v)| ((*k).into(), (*v).into())).collect();
        let p = CheckParams::from_pairs(&pairs);
        assert_eq!((p.version.as_deref(), p.platform.as_deref(), p.app.as_deref()), (Some("0.1,0.1"), Some("ios"), None));
        // "0.1,0.1" parses as 0.1 (stops at ','), so the verdict is still judged.
        let pol = policies("STUDENT", "IOS", "1.0.0", "1.0.0", URL);
        assert_eq!(check(&p, &pol).verdict, Verdict::Force);
    }

    #[test]
    fn json_shape() {
        let r = check(&params(None, Some("ios"), Some("1.0.0")), &policies("STUDENT", "IOS", "1.1.0", "1.3.0", URL));
        assert_eq!(
            serde_json::to_value(&r).unwrap(),
            serde_json::json!({"verdict": "FORCE", "latestVersion": "1.3.0", "storeUrl": URL})
        );
    }
}
