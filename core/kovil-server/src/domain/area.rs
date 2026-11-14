//! Location service: GPS -> 시도/시군구/행정동 from our own boundary data (admin_dongs), plus dong lists and boundaries.
//!
//! Privacy: coordinates are personal location data (위치정보법). They are used for one lookup and never stored or
//! logged; responses are `Cache-Control: no-store`. No map provider is called and no provider output is stored.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use axum::Json;
use axum::extract::rejection::QueryRejection;
use axum::extract::{Path, Query, State};
use axum::http::header::CACHE_CONTROL;
use axum::response::{IntoResponse, Response};
use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use serde::{Deserialize, Serialize};
use sqlx::MySqlPool;

use crate::AppState;
use crate::api::{ApiError, ErrorCode, ok};
use crate::area_index::{AreaIndex, Dong, Match, parse_multipolygon};
use crate::i18n::Lang;

/// Required by the data licence (KOGL Type 1 + CC BY 4.0). Sent with every boundary.
pub const ATTRIBUTION: &str = "Boundaries: Statistics Korea SGIS (KOGL Type 1), modified by vuski/admdongkor (CC BY 4.0) and KOVIL";
const BOUNDARY_CACHE: &str = "public, max-age=86400";

#[derive(Debug, Clone)]
pub struct RegionInfo {
    pub code: String,
    pub name_ko: String,
    pub name_en: String,
    pub sido_code: String,
    pub sido_name_ko: String,
    pub sido_name_en: String,
    /// District and its province are both open for service.
    pub open: bool,
}

#[derive(Default)]
pub struct AreaData {
    pub index: AreaIndex,
    pub regions: HashMap<String, RegionInfo>,
}

/// Swappable snapshot: requests read the current Arc; a reload replaces it whole.
pub type SharedAreas = Arc<RwLock<Arc<AreaData>>>;

#[derive(sqlx::FromRow)]
struct DongRow {
    code: String,
    region_code: String,
    region_name_ko: String,
    region_name_en: String,
    region_active: bool,
    sido_code: String,
    sido_name_ko: String,
    sido_name_en: String,
    sido_active: bool,
    name_ko: String,
    name_en: String,
    center_lat: Decimal,
    center_lng: Decimal,
    min_lat: Decimal,
    min_lng: Decimal,
    max_lat: Decimal,
    max_lng: Decimal,
    boundary: String,
}

const LOAD: &str = "SELECT d.code, r.code AS region_code, r.name_ko AS region_name_ko, r.name_en AS region_name_en, \
       r.is_active AS region_active, p.code AS sido_code, p.name_ko AS sido_name_ko, p.name_en AS sido_name_en, \
       p.is_active AS sido_active, d.name_ko, d.name_en, d.center_lat, d.center_lng, \
       d.min_lat, d.min_lng, d.max_lat, d.max_lng, CAST(ST_AsGeoJSON(d.boundary) AS CHAR) AS boundary \
     FROM admin_dongs d JOIN regions r ON r.id = d.region_id JOIN regions p ON p.id = r.parent_id \
     WHERE d.is_active = 1 ORDER BY d.code";

fn f(d: Decimal) -> f64 {
    d.to_f64().unwrap_or(f64::NAN)
}

/// Reads every active dong with its district and province. Rows with unreadable geometry are skipped and counted.
pub async fn load(db: &MySqlPool) -> Result<(AreaData, usize), sqlx::Error> {
    let rows = sqlx::query_as::<_, DongRow>(LOAD).fetch_all(db).await?;
    let mut regions = HashMap::new();
    let mut dongs = Vec::with_capacity(rows.len());
    let mut skipped = 0;
    for r in rows {
        let Ok(polygons) = parse_multipolygon(&r.boundary) else {
            skipped += 1;
            continue;
        };
        regions.entry(r.region_code.clone()).or_insert_with(|| RegionInfo {
            code: r.region_code.clone(),
            name_ko: r.region_name_ko,
            name_en: r.region_name_en,
            sido_code: r.sido_code,
            sido_name_ko: r.sido_name_ko,
            sido_name_en: r.sido_name_en,
            open: r.region_active && r.sido_active,
        });
        dongs.push(Dong {
            code: r.code,
            region_code: r.region_code,
            name_ko: r.name_ko,
            name_en: r.name_en,
            center: (f(r.center_lng), f(r.center_lat)),
            bbox: (f(r.min_lng), f(r.min_lat), f(r.max_lng), f(r.max_lat)),
            polygons,
        });
    }
    Ok((AreaData { index: AreaIndex::new(dongs), regions }, skipped))
}

/// Loads and swaps in a fresh snapshot. On failure the old snapshot stays (stale beats empty).
pub async fn reload(db: &MySqlPool, shared: &SharedAreas) {
    match load(db).await {
        Ok((data, skipped)) => {
            if skipped > 0 {
                tracing::error!("admin_dongs: {skipped} rows with unreadable boundary skipped");
            }
            tracing::info!("admin_dongs loaded: {} dongs", data.index.len());
            match shared.write() {
                Ok(mut guard) => *guard = Arc::new(data),
                Err(_) => tracing::error!("admin_dongs snapshot lock poisoned; keeping previous snapshot"),
            }
        }
        Err(e) => tracing::error!("admin_dongs reload failed, keeping previous snapshot: {e}"),
    }
}

fn snapshot(s: &AppState) -> Arc<AreaData> {
    s.areas.read().map(|g| Arc::clone(&g)).unwrap_or_default()
}

#[derive(Debug, Serialize, PartialEq)]
pub struct Named {
    pub code: String,
    pub name: String,
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedArea {
    pub sido: Named,
    pub sigungu: Named,
    pub dong: Named,
    /// False when KOVIL has not opened this district yet; the app can say so instead of showing an empty map.
    pub service_open: bool,
    /// INSIDE = the point is in the dong; NEAREST = just outside every boundary (coast, harbour), snapped to the closest.
    pub r#match: &'static str,
}

fn pick(lang: &Lang, ko: &str, en: &str) -> String {
    // Same rule as the region tree: Korean for ko, the romanized name for every other language.
    if lang.is_korean() { ko.to_owned() } else { en.to_owned() }
}

pub fn to_resolved(data: &AreaData, dong: &Dong, m: Match, lang: &Lang) -> Option<ResolvedArea> {
    let region = data.regions.get(&dong.region_code)?;
    Some(ResolvedArea {
        sido: Named { code: region.sido_code.clone(), name: pick(lang, &region.sido_name_ko, &region.sido_name_en) },
        sigungu: Named { code: region.code.clone(), name: pick(lang, &region.name_ko, &region.name_en) },
        dong: Named { code: dong.code.clone(), name: pick(lang, &dong.name_ko, &dong.name_en) },
        service_open: region.open,
        r#match: match m {
            Match::Inside => "INSIDE",
            Match::Nearest => "NEAREST",
        },
    })
}

#[derive(Debug, Deserialize)]
pub struct Point {
    pub lat: f64,
    pub lng: f64,
}

pub async fn resolve(
    State(s): State<AppState>,
    lang: Lang,
    query: Result<Query<Point>, QueryRejection>,
) -> Result<Response, ApiError> {
    let p = match query {
        Ok(Query(p)) if p.lat.is_finite() && p.lng.is_finite() && p.lat.abs() <= 90.0 && p.lng.abs() <= 180.0 => p,
        _ => return Err(ApiError::new(&s, &lang, ErrorCode::InvalidRequest)),
    };
    let data = snapshot(&s);
    if data.index.is_empty() {
        return Err(ApiError::internal(&s, &lang, "admin_dongs index is empty (dml/08 not applied?)"));
    }
    let found = data.index.resolve(p.lng, p.lat).and_then(|(d, m)| to_resolved(&data, d, m, &lang));
    // No coordinates in logs or errors, on purpose.
    let area = found.ok_or_else(|| ApiError::new(&s, &lang, ErrorCode::NotFound))?;
    Ok(([(CACHE_CONTROL, "no-store")], ok(area)).into_response())
}

#[derive(Debug, Serialize, PartialEq)]
pub struct DongSummary {
    pub code: String,
    pub name: String,
    pub lat: f64,
    pub lng: f64,
}

pub async fn list_in_region(
    State(s): State<AppState>,
    lang: Lang,
    Path(region_code): Path<String>,
) -> Result<Json<crate::api::ApiResponse<Vec<DongSummary>>>, ApiError> {
    let data = snapshot(&s);
    let mut dongs: Vec<DongSummary> = data
        .index
        .in_region(&region_code)
        .map(|d| DongSummary { code: d.code.clone(), name: pick(&lang, &d.name_ko, &d.name_en), lat: d.center.1, lng: d.center.0 })
        .collect();
    if dongs.is_empty() {
        return Err(ApiError::new(&s, &lang, ErrorCode::NotFound));
    }
    dongs.sort_by(|a, b| a.code.cmp(&b.code));
    Ok(ok(dongs))
}

#[derive(Debug, Serialize)]
pub struct BoundaryFeature {
    pub r#type: &'static str,
    pub properties: BoundaryProps,
    pub geometry: Geometry,
}

#[derive(Debug, Serialize)]
pub struct BoundaryProps {
    pub code: String,
    pub name: String,
    pub attribution: &'static str,
}

#[derive(Debug, Serialize)]
pub struct Geometry {
    pub r#type: &'static str,
    pub coordinates: Vec<Vec<Vec<[f64; 2]>>>,
}

pub async fn boundary(State(s): State<AppState>, lang: Lang, Path(code): Path<String>) -> Result<Response, ApiError> {
    let data = snapshot(&s);
    let d = data.index.get(&code).ok_or_else(|| ApiError::new(&s, &lang, ErrorCode::NotFound))?;
    let coordinates = d
        .polygons
        .iter()
        .map(|poly| poly.iter().map(|ring| ring.iter().map(|&(x, y)| [x, y]).collect()).collect())
        .collect();
    let feature = BoundaryFeature {
        r#type: "Feature",
        properties: BoundaryProps { code: d.code.clone(), name: pick(&lang, &d.name_ko, &d.name_en), attribution: ATTRIBUTION },
        geometry: Geometry { r#type: "MultiPolygon", coordinates },
    };
    Ok(([(CACHE_CONTROL, BOUNDARY_CACHE)], ok(feature)).into_response())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn data() -> AreaData {
        let ring = vec![(126.97, 37.56), (126.99, 37.56), (126.99, 37.57), (126.97, 37.57), (126.97, 37.56)];
        let dong = Dong {
            code: "1114055000".into(),
            region_code: "11140".into(),
            name_ko: "명동".into(),
            name_en: "Myeong-dong".into(),
            center: (126.98, 37.565),
            bbox: (126.97, 37.56, 126.99, 37.57),
            polygons: vec![vec![ring]],
        };
        let region = RegionInfo {
            code: "11140".into(),
            name_ko: "중구".into(),
            name_en: "Jung-gu".into(),
            sido_code: "11".into(),
            sido_name_ko: "서울특별시".into(),
            sido_name_en: "Seoul".into(),
            open: true,
        };
        AreaData { index: AreaIndex::new(vec![dong]), regions: HashMap::from([("11140".into(), region)]) }
    }

    #[test]
    fn resolved_names_follow_language() {
        let d = data();
        let (dong, m) = d.index.resolve(126.978, 37.566).unwrap();
        let en = to_resolved(&d, dong, m, &Lang("vi".into())).unwrap();
        assert_eq!((en.dong.name.as_str(), en.sigungu.name.as_str(), en.sido.name.as_str()), ("Myeong-dong", "Jung-gu", "Seoul"));
        let ko = to_resolved(&d, dong, m, &Lang("ko".into())).unwrap();
        assert_eq!(ko.dong.name, "명동");
        assert_eq!(
            serde_json::to_value(&ko).unwrap(),
            serde_json::json!({"sido":{"code":"11","name":"서울특별시"},"sigungu":{"code":"11140","name":"중구"},
                               "dong":{"code":"1114055000","name":"명동"},"serviceOpen":true,"match":"INSIDE"})
        );
    }
}
