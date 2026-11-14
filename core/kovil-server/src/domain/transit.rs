//! `GET /api/v1/transit/subway-stations?swLat&swLng&neLat&neLng`. Mirrors `domain/transit` (SubwayStationQueryService.findWithin).

use std::collections::HashMap;

use axum::Json;
use axum::extract::rejection::QueryRejection;
use axum::extract::{Query, State};
use rust_decimal::Decimal;
use serde::Serialize;
use sqlx::{MySql, QueryBuilder};

use crate::AppState;
use crate::api::{ApiError, ApiResponse, ok};
use crate::geo::{self, Bounds};
use crate::i18n::Lang;

pub const MAX_RESULTS: i64 = 300;

#[derive(Debug, sqlx::FromRow)]
pub struct StationRow {
    pub id: i64,
    pub code: String,
    pub name_ko: String,
    pub name_en: String,
    pub lat: Decimal,
    pub lng: Decimal,
}

#[derive(Debug, sqlx::FromRow)]
pub struct LineRow {
    pub station_id: i64,
    pub code: String,
    pub name_ko: String,
    pub name_en: String,
    pub color: String,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct Line {
    pub code: String,
    pub name: String,
    /// `#RRGGBB`
    pub color: String,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct SubwayStationResponse {
    pub code: String,
    pub name: String,
    pub lat: Decimal,
    pub lng: Decimal,
    /// Marker dot order. A transfer station is one station with several lines; empty if none are active.
    pub lines: Vec<Line>,
}

/// Joins lines (already in station, sort order) onto stations (already nearest-first). Korean names only for ko.
pub fn assemble(stations: Vec<StationRow>, lines: Vec<LineRow>, lang: &Lang) -> Vec<SubwayStationResponse> {
    let ko = lang.is_korean();
    let mut by_station: HashMap<i64, Vec<Line>> = HashMap::new();
    for l in lines {
        let name = if ko { l.name_ko } else { l.name_en };
        by_station.entry(l.station_id).or_default().push(Line { code: l.code, name, color: l.color });
    }
    stations
        .into_iter()
        .map(|s| SubwayStationResponse {
            lines: by_station.remove(&s.id).unwrap_or_default(),
            code: s.code,
            name: if ko { s.name_ko } else { s.name_en },
            lat: s.lat,
            lng: s.lng,
        })
        .collect()
}

const FIND_WITHIN: &str = "SELECT id, code, name_ko, name_en, lat, lng FROM subway_stations \
     WHERE is_active = 1 AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? \
     ORDER BY (lat - ?) * (lat - ?) + (lng - ?) * (lng - ?) * ?, code \
     LIMIT ?";

async fn lines_of(db: &sqlx::MySqlPool, station_ids: &[i64]) -> Result<Vec<LineRow>, sqlx::Error> {
    let mut q: QueryBuilder<MySql> = QueryBuilder::new(
        "SELECT sl.station_id, l.code, l.name_ko, l.name_en, l.color \
         FROM subway_station_lines sl JOIN subway_lines l ON l.id = sl.line_id \
         WHERE l.is_active = 1 AND sl.station_id IN (",
    );
    let mut ids = q.separated(", ");
    for id in station_ids {
        ids.push_bind(*id);
    }
    q.push(") ORDER BY sl.station_id, sl.sort_order, l.sort_order");
    q.build_query_as::<LineRow>().fetch_all(db).await
}

pub async fn find_within(
    State(s): State<AppState>,
    lang: Lang,
    query: Result<Query<Bounds>, QueryRejection>,
) -> Result<Json<ApiResponse<Vec<SubwayStationResponse>>>, ApiError> {
    let b = geo::parse_bounds(&s, &lang, query)?;
    let (c_lat, c_lng, weight) = geo::center(&b);
    let fail = |e: sqlx::Error| ApiError::internal(&s, &lang, e);
    let stations = sqlx::query_as::<_, StationRow>(FIND_WITHIN)
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
        .map_err(fail)?;
    if stations.is_empty() {
        return Ok(ok(vec![]));
    }
    let ids: Vec<i64> = stations.iter().map(|st| st.id).collect();
    let lines = lines_of(&s.db, &ids).await.map_err(fail)?;
    Ok(ok(assemble(stations, lines, &lang)))
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    fn st(id: i64, code: &str) -> StationRow {
        StationRow {
            id,
            code: code.into(),
            name_ko: format!("{code}역"),
            name_en: format!("{code} Stn"),
            lat: Decimal::from_str("37.5").unwrap(),
            lng: Decimal::from_str("127.0").unwrap(),
        }
    }

    fn ln(station_id: i64, code: &str) -> LineRow {
        LineRow { station_id, code: code.into(), name_ko: format!("{code}호선"), name_en: format!("Line {code}"), color: "#00A84D".into() }
    }

    #[test]
    fn transfer_station_keeps_line_order_and_station_order() {
        let out = assemble(vec![st(2, "ST0002"), st(1, "ST0001")], vec![ln(1, "1"), ln(2, "2"), ln(2, "SB")], &Lang("en".into()));
        assert_eq!(out.iter().map(|s| s.code.as_str()).collect::<Vec<_>>(), ["ST0002", "ST0001"]);
        assert_eq!(out[0].lines.iter().map(|l| l.code.as_str()).collect::<Vec<_>>(), ["2", "SB"]);
        assert_eq!(out[1].lines[0].name, "Line 1");
    }

    #[test]
    fn station_without_active_lines_gets_empty_array() {
        let v = serde_json::to_value(assemble(vec![st(1, "ST0001")], vec![], &Lang("ko".into()))).unwrap();
        assert_eq!(v, serde_json::json!([{"code": "ST0001", "name": "ST0001역", "lat": 37.5, "lng": 127.0, "lines": []}]));
    }
}
