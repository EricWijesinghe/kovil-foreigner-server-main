//! `GET /api/v1/countries`. Mirrors `domain/country` (CountryQueryService.findActiveCountries).

use std::collections::HashMap;

use axum::Json;
use axum::extract::State;
use serde::Serialize;

use crate::api::{ApiError, ApiResponse, ok};
use crate::i18n::Lang;
use crate::AppState;

#[derive(Debug, sqlx::FromRow)]
pub struct CountryRow {
    pub id: i64,
    pub code: String,
    pub name_en: String,
    pub flag_emoji: Option<String>,
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CountryResponse {
    pub code: String,
    pub name: String,
    pub flag_emoji: Option<String>,
}

/// Translated name if one exists for the language, else the English name: never empty.
pub fn merge(countries: Vec<CountryRow>, names: &HashMap<i64, String>) -> Vec<CountryResponse> {
    countries
        .into_iter()
        .map(|c| CountryResponse { name: names.get(&c.id).cloned().unwrap_or(c.name_en), code: c.code, flag_emoji: c.flag_emoji })
        .collect()
}

pub async fn list(State(s): State<AppState>, lang: Lang) -> Result<Json<ApiResponse<Vec<CountryResponse>>>, ApiError> {
    let fail = |e: sqlx::Error| ApiError::internal(&s, &lang, e);
    // One read per table, merged in memory: master data, a few hundred rows (same reason as Java: avoid N+1).
    let names: Vec<(i64, String)> = sqlx::query_as("SELECT country_id, name FROM country_translations WHERE language_code = ?")
        .bind(&lang.0)
        .fetch_all(&s.db)
        .await
        .map_err(fail)?;
    let mut by_id = HashMap::with_capacity(names.len());
    for (id, name) in names {
        by_id.entry(id).or_insert(name);
    }
    let countries = sqlx::query_as::<_, CountryRow>(
        "SELECT id, code, name_en, flag_emoji FROM countries WHERE is_active = 1 ORDER BY sort_order, name_en",
    )
    .fetch_all(&s.db)
    .await
    .map_err(fail)?;
    Ok(ok(merge(countries, &by_id)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: i64, code: &str, en: &str) -> CountryRow {
        CountryRow { id, code: code.into(), name_en: en.into(), flag_emoji: None }
    }

    #[test]
    fn translation_wins_and_english_is_fallback() {
        let names = HashMap::from([(1, "베트남".to_owned())]);
        let out = merge(vec![row(1, "VN", "Vietnam"), row(2, "NP", "Nepal")], &names);
        assert_eq!(out[0].name, "베트남");
        assert_eq!(out[1].name, "Nepal");
        assert_eq!(out[1].code, "NP");
    }

    #[test]
    fn null_flag_is_serialized_as_null() {
        let v = serde_json::to_value(&merge(vec![row(2, "NP", "Nepal")], &HashMap::new())[0]).unwrap();
        assert_eq!(v, serde_json::json!({"code": "NP", "name": "Nepal", "flagEmoji": null}));
    }
}
