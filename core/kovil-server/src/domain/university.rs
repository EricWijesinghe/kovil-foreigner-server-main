//! `GET /api/v1/universities?swLat&swLng&neLat&neLng`. Mirrors `domain/university` (UniversityQueryService.findWithin).

use axum::Json;
use axum::extract::rejection::QueryRejection;
use axum::extract::{Query, State};
use rust_decimal::Decimal;
use serde::Serialize;

use crate::api::{ApiError, ApiResponse, ok};
use crate::geo::{self, Bounds};
use crate::i18n::Lang;
use crate::AppState;

pub const MAX_RESULTS: i64 = 200;

#[derive(Debug, sqlx::FromRow)]
pub struct UniversityRow {
    pub code: String,
    pub name_ko: String,
    pub name_en: String,
    pub short_name_ko: Option<String>,
    pub short_name_en: Option<String>,
    pub campus_name_ko: Option<String>,
    pub campus_name_en: Option<String>,
    pub lat: Decimal,
    pub lng: Decimal,
    pub region_code: Option<String>,
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UniversityResponse {
    pub code: String,
    pub name: String,
    pub short_name: Option<String>,
    pub campus_name: Option<String>,
    pub lat: Decimal,
    pub lng: Decimal,
    pub region_code: Option<String>,
}

pub fn to_response(u: UniversityRow, lang: &Lang) -> UniversityResponse {
    let ko = lang.is_korean();
    UniversityResponse {
        code: u.code,
        name: if ko { u.name_ko } else { u.name_en },
        short_name: if ko { u.short_name_ko } else { u.short_name_en },
        campus_name: if ko { u.campus_name_ko } else { u.campus_name_en },
        lat: u.lat,
        lng: u.lng,
        region_code: u.region_code,
    }
}

// region_code: only for an active district whose province is also active (Java `openRegionCodesOf`).
const FIND_WITHIN: &str = "SELECT u.code, u.name_ko, u.name_en, u.short_name_ko, u.short_name_en, \
       u.campus_name_ko, u.campus_name_en, u.lat, u.lng, \
       CASE WHEN p.id IS NULL THEN NULL ELSE r.code END AS region_code \
     FROM universities u \
     LEFT JOIN regions r ON r.id = u.region_id AND r.is_active = 1 AND r.parent_id IS NOT NULL \
     LEFT JOIN regions p ON p.id = r.parent_id AND p.is_active = 1 \
     WHERE u.is_active = 1 AND u.lat BETWEEN ? AND ? AND u.lng BETWEEN ? AND ? \
     ORDER BY (u.lat - ?) * (u.lat - ?) + (u.lng - ?) * (u.lng - ?) * ?, u.code \
     LIMIT ?";

pub async fn find_within(
    State(s): State<AppState>,
    lang: Lang,
    query: Result<Query<Bounds>, QueryRejection>,
) -> Result<Json<ApiResponse<Vec<UniversityResponse>>>, ApiError> {
    let b = geo::parse_bounds(&s, &lang, query)?;
    let (c_lat, c_lng, weight) = geo::center(&b);
    let rows = sqlx::query_as::<_, UniversityRow>(FIND_WITHIN)
        .bind(b.sw_lat)
        .bind(b.ne_lat)
        .bind(b.sw_lng)
        .bind(b.ne_lng)
        .bind(c_lat)
        .bind(c_lat)
        .bind(c_lng)
        .bind(c_lng)
        .bind(weight)
        .bind(MAX_RESULTS)
        .fetch_all(&s.db)
        .await
        .map_err(|e| ApiError::internal(&s, &lang, e))?;
    Ok(ok(rows.into_iter().map(|u| to_response(u, &lang)).collect()))
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    fn d(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    #[test]
    fn korean_fields_only_for_ko() {
        let row = || UniversityRow {
            code: "0000001".into(),
            name_ko: "서울대학교".into(),
            name_en: "Seoul National University".into(),
            short_name_ko: Some("서울대".into()),
            short_name_en: None,
            campus_name_ko: None,
            campus_name_en: None,
            lat: d("37.4596000"),
            lng: d("126.9519000"),
            region_code: Some("11620".into()),
        };
        assert_eq!(to_response(row(), &Lang("ko".into())).short_name.as_deref(), Some("서울대"));
        let en = to_response(row(), &Lang("ja".into()));
        assert_eq!(en.name, "Seoul National University");
        assert_eq!(en.short_name, None);
        let v = serde_json::to_value(&en).unwrap();
        assert_eq!(v["lat"], serde_json::json!(37.4596));
        assert_eq!(v["regionCode"], "11620");
    }
}
