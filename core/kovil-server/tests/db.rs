//! Real-MySQL tests: apply the Java app's Flyway files V1..Vn to a scratch database, then call the Rust endpoints.
//! Runs only when KOVIL_TEST_MYSQL_URL is set (e.g. `mysql://root:root@127.0.0.1:3306`); CI sets it.
//! The database `kovil_rust_test` is dropped and recreated on every run. Never point this at a real server.

use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::Value;
use sqlx::mysql::MySqlPoolOptions;
use sqlx::{AssertSqlSafe, MySqlPool};
use tower::ServiceExt;

use kovil_server::i18n::Messages;
use kovil_server::translation::{Engine, EngineFuture};
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
    listing_translation(&db).await;
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

/// Stands in for the vendor: counts calls and answers a fixed text.
struct Scripted {
    calls: Arc<AtomicUsize>,
    reply: &'static str,
}

impl Engine for Scripted {
    fn name(&self) -> &'static str {
        "scripted"
    }

    fn complete<'a>(&'a self, _system: &'a str, _text: &'a str) -> EngineFuture<'a> {
        Box::pin(async move {
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok(self.reply.to_owned())
        })
    }
}

const TR_KEY: &str = "a292aWwtcnVzdC1nb2xkZW4tdGVzdC1rZXktMzJieXQ=";

/// Signed-in tenant token, minted with the same key the app verifies with.
fn user_token() -> String {
    use base64::Engine;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
    let raw = base64::engine::general_purpose::STANDARD.decode(TR_KEY).unwrap();
    jsonwebtoken::encode(
        &jsonwebtoken::Header::default(),
        &serde_json::json!({"jti": "db-tr", "iss": "kovil", "sub": "db-tenant-uuid", "role": "USER", "iat": now, "exp": now + 600}),
        &jsonwebtoken::EncodingKey::from_secret(&raw),
    )
    .unwrap()
}

fn translate_app(db: &MySqlPool, engine: Arc<dyn Engine>) -> axum::Router {
    use kovil_server::auth::{Auth, JwtVerifier};
    let auth = Auth { jwt: Some(JwtVerifier::new(TR_KEY, "kovil").unwrap()), revocations: None };
    let messages = Messages::load(&repo().join("src/main/resources/messages")).unwrap();
    router(AppState::new(db.clone(), messages).with_auth(auth).with_translator(engine))
}

async fn post_as_user(app: &axum::Router, uri: &str) -> (StatusCode, Value) {
    let req = Request::post(uri).header("Authorization", format!("Bearer {}", user_token())).body(Body::empty()).unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

async fn count_rows(db: &MySqlPool, listing_id: i64, lang: &str, version: i32) -> i64 {
    sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM listing_description_translations WHERE listing_id = ? AND language_code = ? AND source_version = ?",
    )
    .bind(listing_id)
    .bind(lang)
    .bind(version)
    .fetch_one(db)
    .await
    .unwrap()
}

const TR_LISTING: i64 = 9401;
const TR_UUID: &str = "66666666-6666-4666-8666-666666666666";
const KOREAN_V1: &str = "보증금 1,000,000원, 월세 50만원. 전세 계약 가능.";
const KOREAN_V2: &str = "보증금 1,000,000원, 월세 50만원. 반려동물 가능.";
const ENGLISH: &str = "Deposit 1,000,000 won, monthly rent 500,000 won. Lease contract available.";

/// Listing translation cache: miss, hit, version bump, stale fake row, gate rejection, visibility and language checks.
async fn listing_translation(db: &MySqlPool) {
    sqlx::query("INSERT INTO users (id, uuid, role, status) VALUES (9301, '33333333-3333-4333-8333-333333333333', 'PARTNER', 'ACTIVE')")
        .execute(db)
        .await
        .expect("insert broker user");
    sqlx::query("INSERT INTO brokerage_offices (id, uuid, reg_no, name, ceo_name, address, tel) VALUES (9302, '44444444-4444-4444-8444-444444444444', 'TR-TEST-0001', '테스트중개', '홍길동', '서울 강남', '02-000-0000')")
        .execute(db)
        .await
        .expect("insert office");
    sqlx::query("INSERT INTO broker_profiles (user_id, office_id) VALUES (9301, 9302)").execute(db).await.expect("broker profile");
    sqlx::query("INSERT INTO partner_divisions (uuid, user_id, division, status) VALUES ('55555555-5555-4555-8555-555555555555', 9301, 'REAL_ESTATE_BROKER', 'APPROVED')")
        .execute(db)
        .await
        .expect("approved division");
    sqlx::query(
        "INSERT INTO listings (id, uuid, listing_no, office_id, created_by_user_id, category, room_type, title, description, \
         description_version, selling_type, deposit, monthly_rent, exclusive_area, real_lat, real_lng, display_lat, display_lng, \
         address_jibun, status) VALUES (?, ?, 'LS-TEST-0001', 9302, 9301, 'APT', 'ONE_ROOM', '테스트', ?, 1, 'MONTHLY_RENT', \
         1000000, 500000, 20.00, 37.5000000, 127.0000000, 37.5000000, 127.0000000, '서울 강남', 'ACTIVE')",
    )
    .bind(TR_LISTING)
    .bind(TR_UUID)
    .bind(KOREAN_V1)
    .execute(db)
    .await
    .expect("insert listing");

    let calls = Arc::new(AtomicUsize::new(0));
    let good = Arc::new(Scripted { calls: calls.clone(), reply: ENGLISH });
    let app = translate_app(db, good);
    let en = format!("/api/v1/listings/{TR_UUID}/description/translate?language=en");

    // Miss: one vendor call, stored under the engine name.
    let (status, body) = post_as_user(&app, &en).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(
        body["data"],
        serde_json::json!({"languageCode": "en", "content": ENGLISH, "engine": "scripted", "cached": false, "translated": true})
    );
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert_eq!(count_rows(db, TR_LISTING, "en", 1).await, 1);

    // Hit: same answer from the table, vendor not called again.
    let (_, body) = post_as_user(&app, &en).await;
    assert_eq!(body["data"]["cached"], true, "{body}");
    assert_eq!(body["data"]["content"], ENGLISH);
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    // Korean target: the original, never a vendor call.
    let (_, body) = post_as_user(&app, &format!("/api/v1/listings/{TR_UUID}/description/translate?language=KO")).await;
    assert_eq!(body["data"], serde_json::json!({"languageCode": "ko", "content": KOREAN_V1, "engine": null, "cached": false, "translated": false}));
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    // The broker edits the description: version 2 is a miss; the version 1 row stays.
    sqlx::query("UPDATE listings SET description = ?, description_version = description_version + 1 WHERE id = ?")
        .bind(KOREAN_V2)
        .bind(TR_LISTING)
        .execute(db)
        .await
        .unwrap();
    let (_, body) = post_as_user(&app, &en).await;
    assert_eq!(body["data"]["cached"], false, "{body}");
    assert_eq!(calls.load(Ordering::SeqCst), 2);
    assert_eq!(count_rows(db, TR_LISTING, "en", 1).await, 1);
    assert_eq!(count_rows(db, TR_LISTING, "en", 2).await, 1);

    // A fake row left by the Java dev stack is replaced, not served, once a real engine is configured.
    sqlx::query("INSERT INTO listing_description_translations (listing_id, language_code, source_version, content, engine, created_at) VALUES (?, 'vi', 2, '[FAKE vi] x', 'fake', NOW(6))")
        .bind(TR_LISTING)
        .execute(db)
        .await
        .unwrap();
    let (_, body) = post_as_user(&app, &format!("/api/v1/listings/{TR_UUID}/description/translate?language=vi")).await;
    assert_eq!(body["data"]["cached"], false, "{body}");
    assert_eq!(body["data"]["engine"], "scripted", "{body}");
    assert_eq!(count_rows(db, TR_LISTING, "vi", 2).await, 1);

    // Vendor changes a deposit: the original is shown, nothing is stored, and the vendor was asked twice.
    sqlx::query("UPDATE listings SET description_version = 3 WHERE id = ?").bind(TR_LISTING).execute(db).await.unwrap();
    let bad_calls = Arc::new(AtomicUsize::new(0));
    let bad = Arc::new(Scripted { calls: bad_calls.clone(), reply: "Deposit 100,000 won, monthly rent 500,000 won." });
    let (status, body) = post_as_user(&translate_app(db, bad), &en).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"], serde_json::json!({"languageCode": "en", "content": KOREAN_V2, "engine": null, "cached": false, "translated": false}));
    assert_eq!(bad_calls.load(Ordering::SeqCst), 2);
    assert_eq!(count_rows(db, TR_LISTING, "en", 3).await, 0);

    // Hidden listing: 404 even with a valid language.
    sqlx::query("UPDATE listings SET status = 'HIDDEN' WHERE id = ?").bind(TR_LISTING).execute(db).await.unwrap();
    let (status, body) = post_as_user(&app, &en).await;
    assert_eq!((status, body["errorCode"].clone()), (StatusCode::NOT_FOUND, Value::from("LISTING_NOT_FOUND")));
    sqlx::query("UPDATE listings SET status = 'ACTIVE' WHERE id = ?").bind(TR_LISTING).execute(db).await.unwrap();

    // Language with no active row: 400, and the listing is never looked up.
    let (status, body) = post_as_user(&app, &format!("/api/v1/listings/{TR_UUID}/description/translate?language=xx")).await;
    assert_eq!((status, body["errorCode"].clone()), (StatusCode::BAD_REQUEST, Value::from("LANGUAGE_NOT_SUPPORTED")));
}
