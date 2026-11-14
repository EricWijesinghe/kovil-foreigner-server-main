//! `GET /api/v1/languages`. Mirrors `domain/language` (LanguageQueryService.findActiveLanguages).

use axum::Json;
use axum::extract::State;
use serde::Serialize;

use crate::api::{ApiError, ApiResponse, ok};
use crate::i18n::Lang;
use crate::AppState;

#[derive(Debug, Serialize, sqlx::FromRow, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LanguageResponse {
    pub code: String,
    pub native_name: String,
    pub english_name: String,
    pub is_default: bool,
}

pub async fn list(State(s): State<AppState>, lang: Lang) -> Result<Json<ApiResponse<Vec<LanguageResponse>>>, ApiError> {
    sqlx::query_as::<_, LanguageResponse>(
        "SELECT code, native_name, english_name, is_default FROM languages \
         WHERE active = 1 ORDER BY sort_order, code",
    )
    .fetch_all(&s.db)
    .await
    .map(ok)
    .map_err(|e| ApiError::internal(&s, &lang, e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_shape_matches_java_record() {
        let r = LanguageResponse { code: "ko".into(), native_name: "한국어".into(), english_name: "Korean".into(), is_default: true };
        assert_eq!(
            serde_json::to_value(&r).unwrap(),
            serde_json::json!({"code": "ko", "nativeName": "한국어", "englishName": "Korean", "isDefault": true})
        );
    }
}
