//! Map-viewport queries shared by the university and subway layers. Mirrors Java `common/util/MapCenter`
//! plus the per-service `requireSane` checks.

use std::str::FromStr;

use axum::extract::Query;
use axum::extract::rejection::QueryRejection;
use rust_decimal::{Decimal, RoundingStrategy};
use serde::Deserialize;

use crate::AppState;
use crate::api::{ApiError, ErrorCode};
use crate::i18n::Lang;

const MAX_SCALE: u32 = 15;
const LNG_WEIGHT_SCALE: u32 = 10;

/// `?swLat&swLng&neLat&neLng`, all required.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Bounds {
    pub sw_lat: Decimal,
    pub sw_lng: Decimal,
    pub ne_lat: Decimal,
    pub ne_lng: Decimal,
}

/// Missing, non-numeric, off-globe or absurdly precise coordinates are all `INVALID_REQUEST`, before any query.
pub fn parse_bounds(s: &AppState, lang: &Lang, q: Result<Query<Bounds>, QueryRejection>) -> Result<Bounds, ApiError> {
    match q {
        Ok(Query(b)) if is_sane(&b) => Ok(b),
        _ => Err(ApiError::new(s, lang, ErrorCode::InvalidRequest)),
    }
}

pub fn is_sane(b: &Bounds) -> bool {
    let ok = |v: Decimal, limit: i64| v.scale() <= MAX_SCALE && v.abs() <= Decimal::from(limit);
    ok(b.sw_lat, 90) && ok(b.ne_lat, 90) && ok(b.sw_lng, 180) && ok(b.ne_lng, 180)
}

/// Box centre and cos²(lat) longitude weight for nearest-first ordering (Java `MapCenter.of`).
/// The weight goes through the f64's shortest decimal form, like `BigDecimal.valueOf(double)`, then HALF_UP to 10 places.
pub fn center(b: &Bounds) -> (Decimal, Decimal, Decimal) {
    let two = Decimal::TWO;
    let (lat, lng) = ((b.sw_lat + b.ne_lat) / two, (b.sw_lng + b.ne_lng) / two);
    let lat_f: f64 = lat.try_into().unwrap_or(0.0);
    let cos = lat_f.to_radians().cos();
    let weight = Decimal::from_str(&(cos * cos).to_string())
        .or_else(|_| Decimal::from_scientific(&format!("{:e}", cos * cos)))
        .unwrap_or(Decimal::ONE)
        .round_dp_with_strategy(LNG_WEIGHT_SCALE, RoundingStrategy::MidpointAwayFromZero);
    (lat, lng, weight)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    fn bounds(a: &str, b: &str, c: &str, e: &str) -> Bounds {
        Bounds { sw_lat: d(a), sw_lng: d(b), ne_lat: d(c), ne_lng: d(e) }
    }

    #[test]
    fn sanity_limits() {
        assert!(is_sane(&bounds("37.4", "126.8", "37.7", "127.2")));
        assert!(is_sane(&bounds("-90", "-180", "90", "180")));
        assert!(!is_sane(&bounds("90.0000001", "126.8", "37.7", "127.2")));
        assert!(!is_sane(&bounds("37.4", "126.8", "37.7", "180.5")));
        assert!(!is_sane(&bounds("37.1234567890123456", "126.8", "37.7", "127.2")));
    }

    #[test]
    fn center_and_weight_match_java() {
        let (lat, lng, w) = center(&bounds("37.4", "126.8", "37.7", "127.2"));
        assert_eq!(lat, d("37.55"));
        assert_eq!(lng, d("127.0"));
        // Expected values printed by the Java MapCenter.of formula (JDK run, 2026-11-13).
        assert_eq!(w, d("0.6285663966"));
        assert_eq!(center(&bounds("0", "0", "0", "0")).2, d("1.0000000000"));
        assert_eq!(center(&bounds("33.1", "0", "38.6", "0")).2, d("0.6569962280"));
        assert_eq!(center(&bounds("-89.9", "0", "89.95", "0")).2, d("0.9999998096"));
        assert_eq!(center(&bounds("90", "0", "90", "0")).2, d("0.0000000000"));
    }
}
