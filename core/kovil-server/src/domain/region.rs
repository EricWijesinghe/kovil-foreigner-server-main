//! `GET /api/v1/regions`. Mirrors `domain/region` (RegionQueryService.findActiveRegionTree).

use std::collections::HashMap;

use axum::Json;
use axum::extract::State;
use serde::Serialize;

use crate::api::{ApiError, ApiResponse, ok};
use crate::i18n::Lang;
use crate::AppState;

#[derive(Debug, Clone, sqlx::FromRow)]
pub struct RegionRow {
    pub id: i64,
    pub parent_id: Option<i64>,
    pub code: String,
    pub name_ko: String,
    pub name_en: String,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct RegionResponse {
    pub code: String,
    pub name: String,
    /// Always an array; empty for districts.
    pub children: Vec<RegionResponse>,
}

/// Two-level tree from active rows in display order. A district whose province is inactive
/// is dropped with it, so an unopened province never leaks through its districts.
pub fn build_tree(active: Vec<RegionRow>, lang: &Lang) -> Vec<RegionResponse> {
    // Romanized names are the international form: Korean only for ko, English for every other language.
    let name = |r: &RegionRow| if lang.is_korean() { r.name_ko.clone() } else { r.name_en.clone() };
    let mut children: HashMap<i64, Vec<RegionResponse>> = HashMap::new();
    let mut roots = Vec::new();
    for r in &active {
        match r.parent_id {
            None => roots.push(r),
            Some(p) => children.entry(p).or_default().push(RegionResponse { code: r.code.clone(), name: name(r), children: vec![] }),
        }
    }
    roots
        .into_iter()
        .map(|r| RegionResponse { code: r.code.clone(), name: name(r), children: children.remove(&r.id).unwrap_or_default() })
        .collect()
}

pub async fn list(State(s): State<AppState>, lang: Lang) -> Result<Json<ApiResponse<Vec<RegionResponse>>>, ApiError> {
    let rows = sqlx::query_as::<_, RegionRow>(
        "SELECT id, parent_id, code, name_ko, name_en FROM regions WHERE is_active = 1 ORDER BY sort_order, code",
    )
    .fetch_all(&s.db)
    .await
    .map_err(|e| ApiError::internal(&s, &lang, e))?;
    Ok(ok(build_tree(rows, &lang)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(id: i64, parent: Option<i64>, code: &str) -> RegionRow {
        RegionRow { id, parent_id: parent, code: code.into(), name_ko: format!("ko{code}"), name_en: format!("en{code}") }
    }

    #[test]
    fn builds_tree_in_order_and_drops_orphans() {
        // 99 is a district of an inactive province (id 9 not in the active list).
        let rows = vec![r(1, None, "11"), r(10, Some(1), "11010"), r(2, None, "26"), r(99, Some(9), "41000"), r(11, Some(1), "11020")];
        let tree = build_tree(rows, &Lang("en".into()));
        assert_eq!(tree.len(), 2);
        assert_eq!(tree[0].children.iter().map(|c| c.code.as_str()).collect::<Vec<_>>(), ["11010", "11020"]);
        assert!(tree[1].children.is_empty());
        assert_eq!(tree[0].name, "en11");
    }

    #[test]
    fn korean_only_for_ko() {
        assert_eq!(build_tree(vec![r(1, None, "11")], &Lang("ko".into()))[0].name, "ko11");
        assert_eq!(build_tree(vec![r(1, None, "11")], &Lang("vi".into()))[0].name, "en11");
    }

    #[test]
    fn district_children_serialize_as_empty_array() {
        let v = serde_json::to_value(build_tree(vec![r(1, None, "11")], &Lang("en".into()))).unwrap();
        assert_eq!(v, serde_json::json!([{"code": "11", "name": "en11", "children": []}]));
    }
}
