//! Router behaviour that needs no database: error envelope, localization, validation before any query.

use std::path::Path;
use std::time::Duration;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sqlx::mysql::MySqlPoolOptions;
use tower::ServiceExt;

use kovil_server::i18n::Messages;
use kovil_server::{AppState, router};

fn app() -> axum::Router {
    // Nothing listens on port 9: any query fails fast, which proves validation runs before the DB.
    let db = MySqlPoolOptions::new()
        .acquire_timeout(Duration::from_millis(300))
        .connect_lazy("mysql://u:p@127.0.0.1:9/none")
        .expect("lazy pool");
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/messages");
    router(AppState::new(db, Messages::load(&dir).expect("bundles")))
}

async fn call(method: &str, uri: &str, lang: Option<&str>) -> (StatusCode, Value) {
    let mut req = Request::builder().method(method).uri(uri);
    if let Some(l) = lang {
        req = req.header("Accept-Language", l);
    }
    let res = app().oneshot(req.body(Body::empty()).unwrap()).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn unknown_path_is_localized_not_found() {
    let (status, body) = call("GET", "/api/v1/nope", Some("ko-KR,ko;q=0.9")).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_eq!(body, json!({"success": false, "message": "요청하신 정보를 찾을 수 없어요.", "errorCode": "NOT_FOUND", "data": null, "validationErrors": null}));
}

#[tokio::test]
async fn wrong_method_is_not_found_like_java() {
    let (status, body) = call("POST", "/api/v1/languages", None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_eq!(body["errorCode"], "NOT_FOUND");
    assert_eq!(body["message"], "The requested information could not be found.");
}

#[tokio::test]
async fn unsupported_language_falls_back_to_english() {
    let (_, body) = call("GET", "/x", Some("fr")).await;
    assert_eq!(body["message"], "The requested information could not be found.");
}

#[tokio::test]
async fn universities_missing_param_is_invalid_request() {
    let (status, body) = call("GET", "/api/v1/universities?swLat=37.4&swLng=126.8&neLat=37.7", None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["errorCode"], "INVALID_REQUEST");
}

#[tokio::test]
async fn universities_non_number_is_invalid_request() {
    let (status, _) = call("GET", "/api/v1/universities?swLat=abc&swLng=126.8&neLat=37.7&neLng=127.2", None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn universities_out_of_range_is_invalid_request() {
    let (status, body) = call("GET", "/api/v1/universities?swLat=37.4&swLng=126.8&neLat=91&neLng=127.2", Some("ko")).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["message"], "올바르지 않은 요청이에요.");
}

#[tokio::test]
async fn db_failure_is_generic_500_without_internals() {
    let (status, body) = call("GET", "/api/v1/languages", None).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(body, json!({"success": false, "message": "An internal server error occurred.", "errorCode": "INTERNAL_SERVER_ERROR", "data": null, "validationErrors": null}));
}

#[tokio::test]
async fn health_reports_down_without_db() {
    let (status, body) = call("GET", "/actuator/health", None).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(body, json!({"status": "DOWN"}));
}

#[tokio::test]
async fn subway_bad_bounds_is_invalid_request() {
    let (status, body) = call("GET", "/api/v1/transit/subway-stations?swLat=37.4&swLng=126.8&neLat=37.7", None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["errorCode"], "INVALID_REQUEST");
}

#[tokio::test]
async fn app_version_fails_open_when_db_is_down() {
    let (status, body) = call("GET", "/api/v1/app-version/check?platform=ios&version=0.0.1", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["data"], json!({"verdict": "OK", "latestVersion": null, "storeUrl": null}));
}

#[tokio::test]
async fn app_version_garbage_query_is_still_ok() {
    let (status, body) = call("GET", "/api/v1/app-version/check?app=%ZZ&app=x", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["data"]["verdict"], "OK");
}

#[tokio::test]
async fn area_resolve_rejects_bad_coordinates_before_anything_else() {
    for q in ["lat=91&lng=127", "lat=37.5", "lat=abc&lng=127", "lat=NaN&lng=127", "lat=37.5&lng=inf"] {
        let (status, body) = call("GET", &format!("/api/v1/areas/resolve?{q}"), None).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{q}");
        assert_eq!(body["errorCode"], "INVALID_REQUEST");
    }
}

#[tokio::test]
async fn area_resolve_without_loaded_index_is_500_not_a_wrong_answer() {
    let (status, body) = call("GET", "/api/v1/areas/resolve?lat=37.5&lng=127.0", None).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(body["errorCode"], "INTERNAL_SERVER_ERROR");
}

#[tokio::test]
async fn unknown_dong_boundary_is_not_found() {
    let (status, _) = call("GET", "/api/v1/areas/dongs/0000000000/boundary", None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}
