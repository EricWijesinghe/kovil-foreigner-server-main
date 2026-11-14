//! Real-MySQL tests: apply the Java app's Flyway files V1..Vn to a scratch database, then call the Rust endpoints.
//! Runs only when KOVIL_TEST_MYSQL_URL is set (e.g. `mysql://root:root@127.0.0.1:3306`); CI sets it.
//! The database `kovil_rust_test` is dropped and recreated on every run. Never point this at a real server.

use std::path::Path;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::Value;
use sqlx::mysql::MySqlPoolOptions;
use sqlx::{AssertSqlSafe, MySqlPool};
use tower::ServiceExt;

use kovil_server::i18n::Messages;
use kovil_server::{AppState, router};

const TEST_DB: &str = "kovil_rust_test";

fn repo() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn version(name: &str) -> u32 {
    name.strip_prefix('V').and_then(|s| s.split("__").next()).and_then(|v| v.parse().ok()).unwrap_or(u32::MAX)
}

async fn fresh_db(server: &str) -> MySqlPool {
    let admin = MySqlPool::connect(server).await.expect("connect to test MySQL server");
    for stmt in [format!("DROP DATABASE IF EXISTS {TEST_DB}"), format!("CREATE DATABASE {TEST_DB} CHARACTER SET utf8mb4")] {
        sqlx::query(AssertSqlSafe(stmt)).execute(&admin).await.expect("recreate test db");
    }
    let pool = MySqlPoolOptions::new()
        .max_connections(2)
        .connect(&format!("{}/{TEST_DB}", server.trim_end_matches('/')))
        .await
        .expect("connect to test db");
    let dir = repo().join("src/main/resources/db/migration");
    let mut files: Vec<_> = std::fs::read_dir(&dir).expect("migration dir").map(|e| e.expect("entry").path()).collect();
    files.sort_by_key(|p| version(p.file_name().and_then(|n| n.to_str()).unwrap_or_default()));
    for f in files {
        let sql = std::fs::read_to_string(&f).expect("read migration");
        sqlx::raw_sql(AssertSqlSafe(sql)).execute(&pool).await.unwrap_or_else(|e| panic!("{}: {e}", f.display()));
    }
    pool
}

async fn get(app: &axum::Router, uri: &str, lang: &str) -> (StatusCode, Value) {
    let req = Request::get(uri).header("Accept-Language", lang).body(Body::empty()).unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

fn has_hangul(s: &str) -> bool {
    s.chars().any(|c| ('\u{AC00}'..='\u{D7A3}').contains(&c))
}

#[tokio::test]
async fn endpoints_against_real_schema() {
    let Ok(server) = std::env::var("KOVIL_TEST_MYSQL_URL") else {
        eprintln!("skipped: KOVIL_TEST_MYSQL_URL not set");
        return;
    };
    let db = fresh_db(&server).await;
    let messages = Messages::load(&repo().join("src/main/resources/messages")).unwrap();
    let app = router(AppState::new(db.clone(), messages));

    let (status, body) = get(&app, "/api/v1/languages", "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let langs = body["data"].as_array().unwrap();
    assert!(langs.iter().any(|l| l["code"] == "ko"));
    assert_eq!(langs.iter().filter(|l| l["isDefault"] == true).count(), 1);

    let (status, body) = get(&app, "/api/v1/countries", "ko-KR").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let countries = body["data"].as_array().unwrap();
    assert!(countries.iter().all(|c| c["name"].as_str().is_some_and(|n| !n.is_empty())));
    assert!(countries.iter().any(|c| has_hangul(c["name"].as_str().unwrap())), "ko translations applied");

    let (status, body) = get(&app, "/api/v1/regions", "ko").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let tree = body["data"].as_array().unwrap();
    assert!(!tree.is_empty());
    assert!(tree.iter().all(|p| has_hangul(p["name"].as_str().unwrap())));
    assert!(tree.iter().flat_map(|p| p["children"].as_array().unwrap()).all(|d| d["children"] == serde_json::json!([])));

    universities(&app, &db).await;
    subway(&app, &db).await;
    app_version(&app, &db).await;
    admin_app_update(&db).await;
    areas(&db).await;
}

async fn universities(app: &axum::Router, db: &MySqlPool) {
    let (district_id, district_code, parent_id): (i64, String, i64) =
        sqlx::query_as("SELECT id, code, parent_id FROM regions WHERE parent_id IS NOT NULL AND is_active = 1 ORDER BY id LIMIT 1")
            .fetch_one(db)
            .await
            .expect("an active district");
    sqlx::query(
        "INSERT INTO universities (code, name_ko, name_en, short_name_ko, address_road, lat, lng, region_id) VALUES \
         ('T000001', '가까운대학교', 'Near University', '가까대', 'addr', 37.5500000, 127.0000000, ?), \
         ('T000002', '먼대학교', 'Far University', NULL, 'addr', 37.6900000, 127.1900000, NULL), \
         ('T000003', '밖대학교', 'Outside University', NULL, 'addr', 35.1000000, 129.0000000, NULL)",
    )
    .bind(district_id)
    .execute(db)
    .await
    .expect("insert universities");

    let uri = "/api/v1/universities?swLat=37.4&swLng=126.8&neLat=37.7&neLng=127.2";
    let (status, body) = get(app, uri, "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let found = body["data"].as_array().unwrap();
    assert_eq!(found.iter().map(|u| u["code"].as_str().unwrap()).collect::<Vec<_>>(), ["T000001", "T000002"], "nearest first, outside excluded");
    assert_eq!(found[0]["name"], "Near University");
    assert_eq!(found[0]["shortName"], Value::Null);
    assert_eq!(found[0]["regionCode"], district_code.as_str());
    assert_eq!(found[0]["lat"], serde_json::json!(37.55));

    let (_, body) = get(app, uri, "ko").await;
    assert_eq!(body["data"][0]["shortName"], "가까대");

    // Province closed -> its district code must not leak.
    sqlx::query("UPDATE regions SET is_active = 0 WHERE id = ?").bind(parent_id).execute(db).await.unwrap();
    let (_, body) = get(app, uri, "en").await;
    assert_eq!(body["data"][0]["regionCode"], Value::Null);
}

async fn subway(app: &axum::Router, db: &MySqlPool) {
    sqlx::query(
        "INSERT INTO subway_lines (id, code, name_ko, name_en, color, is_active, sort_order) VALUES          (9001, 'T-2', '2호선', 'Line 2', '#00A84D', 1, 2), (9002, 'T-SB', '신분당선', 'Shinbundang', '#D4003B', 1, 1),          (9003, 'T-OLD', '폐선', 'Closed', '#000000', 0, 0)",
    )
    .execute(db)
    .await
    .expect("insert lines");
    sqlx::query(
        "INSERT INTO subway_stations (id, code, name_ko, name_en, lat, lng) VALUES          (9101, 'TST01', '강남', 'Gangnam', 37.5500000, 127.0000000), (9102, 'TST02', '먼역', 'Far', 37.6900000, 127.1900000)",
    )
    .execute(db)
    .await
    .expect("insert stations");
    sqlx::query(
        "INSERT INTO subway_station_lines (station_id, line_id, sort_order) VALUES          (9101, 9001, 1), (9101, 9002, 0), (9101, 9003, 2)",
    )
    .execute(db)
    .await
    .expect("insert station lines");

    let uri = "/api/v1/transit/subway-stations?swLat=37.4&swLng=126.8&neLat=37.7&neLng=127.2";
    let (status, body) = get(app, uri, "ko").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let found = body["data"].as_array().unwrap();
    let ours: Vec<&Value> = found.iter().filter(|s| s["code"].as_str().unwrap().starts_with("TST")).collect();
    assert_eq!(ours.iter().map(|s| s["code"].as_str().unwrap()).collect::<Vec<_>>(), ["TST01", "TST02"]);
    assert_eq!(ours[0]["name"], "강남");
    // Station order first (SB sort 0, then 2호선), closed line dropped.
    assert_eq!(ours[0]["lines"], serde_json::json!([
        {"code": "T-SB", "name": "신분당선", "color": "#D4003B"},
        {"code": "T-2", "name": "2호선", "color": "#00A84D"}
    ]));
    assert_eq!(ours[1]["lines"], serde_json::json!([]));
}

async fn app_version(app: &axum::Router, db: &MySqlPool) {
    // Seeds (V17, V29) have empty store URLs, so every verdict is suppressed to OK.
    let (status, body) = get(app, "/api/v1/app-version/check?platform=ios&version=0.0.1", "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"], serde_json::json!({"verdict": "OK", "latestVersion": "1.0.0", "storeUrl": null}));

    sqlx::query("UPDATE app_update_policies SET min_supported_version = '2.0.0', latest_version = '2.1.0',                  store_url = 'https://play.google.com/store/apps/details?id=x' WHERE app = 'PARTNER' AND platform = 'ANDROID'")
        .execute(db)
        .await
        .unwrap();
    // A fresh router has a cold cache, so it sees the update immediately.
    let fresh = router(AppState::new(db.clone(), Messages::load(&repo().join("src/main/resources/messages")).unwrap()));
    let (_, body) = get(&fresh, "/api/v1/app-version/check?app=PARTNER&platform=android&version=1.9.9", "en").await;
    assert_eq!(body["data"]["verdict"], "FORCE");
    let (_, body) = get(&fresh, "/api/v1/app-version/check?app=PARTNER&platform=android&version=2.0.5", "en").await;
    assert_eq!(body["data"]["verdict"], "RECOMMEND");
}

async fn areas(db: &MySqlPool) {
    // Two dongs in one district, inserted through the same ST_GeomFromGeoJSON path dml/08 uses.
    let (district_id, district_code): (i64, String) =
        sqlx::query_as("SELECT id, code FROM regions WHERE parent_id IS NOT NULL AND is_active = 1 ORDER BY id LIMIT 1")
            .fetch_one(db)
            .await
            .unwrap();
    for (code, ko, en, x0) in [("9999000001", "가동", "Ga-dong", 127.00), ("9999000002", "나동", "Na-dong", 127.01)] {
        let x1 = x0 + 0.01;
        let geo = format!(r#"{{"type":"MultiPolygon","coordinates":[[[[{x0},37.5],[{x1},37.5],[{x1},37.51],[{x0},37.51],[{x0},37.5]]]]}}"#);
        sqlx::query(
            "INSERT INTO admin_dongs (code, region_id, name_ko, name_en, center_lat, center_lng, min_lat, min_lng, max_lat, max_lng,              boundary, source_version) VALUES (?, ?, ?, ?, 37.505, ?, 37.5, ?, 37.51, ?, ST_GeomFromGeoJSON(?, 2, 4326), 'test')",
        )
        .bind(code)
        .bind(district_id)
        .bind(ko)
        .bind(en)
        .bind(x0 + 0.005)
        .bind(x0)
        .bind(x1)
        .bind(geo)
        .execute(db)
        .await
        .expect("insert admin_dong");
    }
    let state = AppState::new(db.clone(), Messages::load(&repo().join("src/main/resources/messages")).unwrap());
    kovil_server::domain::area::reload(db, &state.areas).await;
    let areas = state.areas.clone();
    let app = router(state);

    // Point inside the second square: lng order must survive ST_AsGeoJSON (MySQL stores 4326 lat-first internally).
    let (status, body) = get(&app, "/api/v1/areas/resolve?lat=37.505&lng=127.015", "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["dong"], serde_json::json!({"code": "9999000002", "name": "Na-dong"}));
    assert_eq!(body["data"]["sigungu"]["code"], district_code.as_str());
    assert_eq!(body["data"]["match"], "INSIDE");

    let (_, body) = get(&app, "/api/v1/areas/resolve?lat=37.505&lng=127.005", "ko").await;
    assert_eq!(body["data"]["dong"]["name"], "가동");

    // Tokyo: nowhere near any dong.
    let (status, _) = get(&app, "/api/v1/areas/resolve?lat=35.68&lng=139.69", "en").await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, body) = get(&app, &format!("/api/v1/regions/{district_code}/dongs"), "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"].as_array().unwrap().len(), 2);

    let (status, body) = get(&app, "/api/v1/areas/dongs/9999000001/boundary", "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["geometry"]["type"], "MultiPolygon");
    assert_eq!(body["data"]["geometry"]["coordinates"][0][0][0], serde_json::json!([127.0, 37.5]));
    assert!(body["data"]["properties"]["attribution"].as_str().unwrap().contains("SGIS"));

    // Reload picks up changed rows...
    sqlx::query("UPDATE admin_dongs SET name_en = 'Na-dong Renamed' WHERE code = '9999000002'").execute(db).await.unwrap();
    kovil_server::domain::area::reload(db, &areas).await;
    let (_, body) = get(&app, "/api/v1/areas/resolve?lat=37.505&lng=127.015", "en").await;
    assert_eq!(body["data"]["dong"]["name"], "Na-dong Renamed");

    // ...and a failed reload keeps serving the previous snapshot (stale beats empty).
    let dead = MySqlPoolOptions::new()
        .acquire_timeout(std::time::Duration::from_millis(300))
        .connect_lazy("mysql://u:p@127.0.0.1:9/none")
        .unwrap();
    kovil_server::domain::area::reload(&dead, &areas).await;
    let (status, body) = get(&app, "/api/v1/areas/resolve?lat=37.505&lng=127.015", "en").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["dong"]["code"], "9999000002");
}

/// Admin policy edit end to end: list shape, validation order, write, and cache eviction on this instance.
async fn admin_app_update(db: &MySqlPool) {
    use base64::Engine;
    use kovil_server::auth::{Auth, JwtVerifier};
    const KEY: &str = "a292aWwtcnVzdC1nb2xkZW4tdGVzdC1rZXktMzJieXQ=";
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
    let raw = base64::engine::general_purpose::STANDARD.decode(KEY).unwrap();
    let admin = jsonwebtoken::encode(
        &jsonwebtoken::Header::default(),
        &serde_json::json!({"jti": "db-admin", "iss": "kovil", "sub": "admin-uuid", "role": "ADMIN", "iat": now, "exp": now + 600}),
        &jsonwebtoken::EncodingKey::from_secret(&raw),
    )
    .unwrap();
    let auth = Auth { jwt: Some(JwtVerifier::new(KEY, "kovil").unwrap()), revocations: None };
    let app = router(AppState::new(db.clone(), Messages::load(&repo().join("src/main/resources/messages")).unwrap()).with_auth(auth));
    let send = |method: &str, uri: &str, body: Option<&str>| {
        let mut req = Request::builder().method(method).uri(uri).header("Authorization", format!("Bearer {admin}"));
        if body.is_some() {
            req = req.header("Content-Type", "application/json");
        }
        let req = req.body(body.map(|b| Body::from(b.to_owned())).unwrap_or_default()).unwrap();
        let app = app.clone();
        async move {
            let res = app.oneshot(req).await.unwrap();
            let status = res.status();
            let bytes = res.into_body().collect().await.unwrap().to_bytes();
            (status, serde_json::from_slice::<Value>(&bytes).unwrap())
        }
    };

    let (status, body) = send("GET", "/api/v1/admin/app-update-policies/STUDENT", None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let rows = body["data"].as_array().unwrap();
    assert_eq!(rows.iter().map(|r| r["platform"].as_str().unwrap()).collect::<Vec<_>>(), ["ANDROID", "IOS"]);
    assert!(rows[0].get("id").is_none(), "internal id is not exposed");
    let ts = rows[0]["updatedAt"].as_str().unwrap();
    assert!(ts.len() == 19 && ts.as_bytes()[10] == b'T', "ISO LocalDateTime like Jackson: {ts}");

    // Warm this instance's cache, then edit: the next check must see the edit at once.
    let (_, body) = get(&app, "/api/v1/app-version/check?platform=ios&version=1.0.0", "en").await;
    assert_eq!(body["data"]["verdict"], "OK");
    let put = "/api/v1/admin/app-update-policies/STUDENT/ios";
    let (status, body) = send("PUT", "/api/v1/admin/app-update-policies/STUDENT/WEB", Some("{}")).await;
    assert_eq!((status, body["errorCode"].as_str()), (StatusCode::NOT_FOUND, Some("APP_UPDATE_PLATFORM_INVALID")), "platform before validation");
    let (status, body) = send("PUT", put, Some(r#"{"minSupportedVersion":"3.0.0","latestVersion":"2.0.0"}"#)).await;
    assert_eq!((status, body["errorCode"].as_str()), (StatusCode::BAD_REQUEST, Some("APP_UPDATE_MIN_ABOVE_LATEST")));
    let edit = r#"{"minSupportedVersion":" 1.5.0 ","latestVersion":"2.0.0","storeUrl":" https://apps.apple.com/app/id1 "}"#;
    let (status, body) = send("PUT", put, Some(edit)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(
        (&body["data"]["minSupportedVersion"], &body["data"]["storeUrl"], &body["data"]["platform"]),
        (&Value::from("1.5.0"), &Value::from("https://apps.apple.com/app/id1"), &Value::from("IOS"))
    );
    let (_, body) = get(&app, "/api/v1/app-version/check?platform=ios&version=1.0.0", "en").await;
    assert_eq!(body["data"]["verdict"], "FORCE", "cache evicted after the edit");
}
