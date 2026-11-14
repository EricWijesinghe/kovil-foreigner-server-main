//! Response envelope and errors. Mirrors `common/dto/response/ApiResponse.java` and `GlobalExceptionHandler`.

use axum::Json;
use axum::http::{HeaderValue, StatusCode, header::RETRY_AFTER};
use axum::response::{IntoResponse, Response};
use serde::Serialize;

use crate::i18n::Lang;
use crate::AppState;

/// `{ success, data, message, errorCode, validationErrors }`. Nulls are written, not omitted: the Java
/// `@Schema` text says they are dropped, but no Jackson NON_NULL config exists, so stock Jackson sends them.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ApiResponse<T> {
    pub success: bool,
    pub data: Option<T>,
    pub message: Option<String>,
    pub error_code: Option<&'static str>,
    /// Field-level validation failures. No endpoint ported so far produces them.
    pub validation_errors: Option<Vec<serde_json::Value>>,
}

pub fn ok<T: Serialize>(data: T) -> Json<ApiResponse<T>> {
    Json(ApiResponse { success: true, data: Some(data), message: None, error_code: None, validation_errors: None })
}

/// Error codes this server can emit. Code strings match `CommonErrorCode`, `SecurityErrorCode` and the domain
/// error enums; default messages are the base (`errors.properties`) text.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    InvalidRequest,
    NotFound,
    InternalServerError,
    UnauthorizedAccess,
    ForbiddenAccess,
    AppUpdatePlatformInvalid,
    AppUpdateVersionInvalid,
    AppUpdateMinAboveLatest,
    AppUpdateStoreUrlInvalid,
    RateLimited,
    ListingNotFound,
    LanguageNotSupported,
}

impl ErrorCode {
    pub fn code(self) -> &'static str {
        match self {
            Self::InvalidRequest => "INVALID_REQUEST",
            Self::NotFound => "NOT_FOUND",
            Self::InternalServerError => "INTERNAL_SERVER_ERROR",
            Self::UnauthorizedAccess => "UNAUTHORIZED_ACCESS",
            Self::ForbiddenAccess => "FORBIDDEN_ACCESS",
            Self::AppUpdatePlatformInvalid => "APP_UPDATE_PLATFORM_INVALID",
            Self::AppUpdateVersionInvalid => "APP_UPDATE_VERSION_INVALID",
            Self::AppUpdateMinAboveLatest => "APP_UPDATE_MIN_ABOVE_LATEST",
            Self::AppUpdateStoreUrlInvalid => "APP_UPDATE_STORE_URL_INVALID",
            Self::RateLimited => "RATE_LIMITED",
            Self::ListingNotFound => "LISTING_NOT_FOUND",
            Self::LanguageNotSupported => "LANGUAGE_NOT_SUPPORTED",
        }
    }

    pub fn status(self) -> StatusCode {
        match self {
            Self::InvalidRequest => StatusCode::BAD_REQUEST,
            Self::NotFound => StatusCode::NOT_FOUND,
            Self::InternalServerError => StatusCode::INTERNAL_SERVER_ERROR,
            Self::UnauthorizedAccess => StatusCode::UNAUTHORIZED,
            Self::ForbiddenAccess => StatusCode::FORBIDDEN,
            Self::AppUpdatePlatformInvalid => StatusCode::NOT_FOUND,
            Self::AppUpdateVersionInvalid => StatusCode::BAD_REQUEST,
            Self::AppUpdateMinAboveLatest => StatusCode::BAD_REQUEST,
            Self::AppUpdateStoreUrlInvalid => StatusCode::BAD_REQUEST,
            Self::RateLimited => StatusCode::TOO_MANY_REQUESTS,
            Self::ListingNotFound => StatusCode::NOT_FOUND,
            Self::LanguageNotSupported => StatusCode::BAD_REQUEST,
        }
    }

    fn default_message(self) -> &'static str {
        match self {
            Self::InvalidRequest => "The request is not valid.",
            Self::NotFound => "The requested information could not be found.",
            Self::InternalServerError => "An internal server error occurred.",
            Self::UnauthorizedAccess => "Sign-in is required.",
            Self::ForbiddenAccess => "You do not have permission to access this.",
            Self::AppUpdatePlatformInvalid => "Unsupported platform.",
            Self::AppUpdateVersionInvalid => "Version must be numbers in 1.2.3 form.",
            Self::AppUpdateMinAboveLatest => "Minimum supported version cannot be higher than the latest version.",
            Self::AppUpdateStoreUrlInvalid => "Store URL must start with https://.",
            Self::RateLimited => "Too many requests. Please try again shortly.",
            Self::ListingNotFound => "Listing not found.",
            Self::LanguageNotSupported => "That language is not supported.",
        }
    }
}

/// A failure, already localized. Build with [`ApiError::new`] so the message follows `Accept-Language`.
#[derive(Debug)]
pub struct ApiError {
    code: ErrorCode,
    message: String,
    /// Seconds until a rate-limited caller may retry; sent as the `Retry-After` header.
    retry_after: Option<u64>,
}

impl ApiError {
    pub fn new(state: &AppState, lang: &Lang, code: ErrorCode) -> Self {
        Self { code, message: state.messages.resolve(code.code(), &lang.0, code.default_message()), retry_after: None }
    }

    pub fn rate_limited(state: &AppState, lang: &Lang, retry_after: u64) -> Self {
        Self { retry_after: Some(retry_after), ..Self::new(state, lang, ErrorCode::RateLimited) }
    }

    /// Logs the cause (never sent to the client) and returns a generic 500.
    pub fn internal(state: &AppState, lang: &Lang, cause: impl std::fmt::Display) -> Self {
        tracing::error!("unexpected error: {cause}");
        Self::new(state, lang, ErrorCode::InternalServerError)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body: ApiResponse<()> = ApiResponse {
            success: false,
            data: None,
            message: Some(self.message),
            error_code: Some(self.code.code()),
            validation_errors: None,
        };
        let mut res = (self.code.status(), Json(body)).into_response();
        if let Some(secs) = self.retry_after
            && let Ok(v) = HeaderValue::from_str(&secs.to_string())
        {
            res.headers_mut().insert(RETRY_AFTER, v);
        }
        res
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn success_writes_null_fields_like_jackson() {
        let v = serde_json::to_value(ok(vec![1]).0).unwrap();
        assert_eq!(
            v,
            serde_json::json!({"success": true, "data": [1], "message": null, "errorCode": null, "validationErrors": null})
        );
    }
}
