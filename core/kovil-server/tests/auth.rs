//! Auth core: Java-minted tokens verify here, and the 401/403/400 order on admin routes matches Spring.
//! Redis cases run only with KOVIL_TEST_REDIS_URL (CI sets it); everything else needs no services.

use std::path::Path;
use std::time::Duration;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use base64::Engine;
use http_body_util::BodyExt;
use jsonwebtoken::{Algorithm, EncodingKey, Header, encode};
use serde_json::{Value, json};
use sqlx::mysql::MySqlPoolOptions;
use tower::ServiceExt;

use kovil_server::auth::{Auth, Division, JwtVerifier, Revocations, Role};
use kovil_server::i18n::Messages;
use kovil_server::{AppState, router};

// Minted by JJWT 0.13 exactly as JwtTokenProvider.issueAccessToken does (scratch Mint.java), exp 2099-01-01.
const K32: &str = "a292aWwtcnVzdC1nb2xkZW4tdGVzdC1rZXktMzJieXQ=";
const K64: &str = "a292aWwtcnVzdC1nb2xkZW4tdGVzdC1rZXktNjQtYnl0ZXMtZm9yLWhzNTEyLXh4eHh4eHh4eHh4eHh4eHh4eA==";
const JAVA_ADMIN: &str = "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiJqdGktYWRtaW4iLCJpc3MiOiJrb3ZpbCIsInN1YiI6IjExMTExMTExLTExMTEtMTExMS0xMTExLTExMTExMTExMTExMSIsInJvbGUiOiJBRE1JTiIsImlhdCI6MTc2MzEwMDAwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.oFqpGERnDdB3TlZhy-ZhTSF2Wp__JhLAxY2Q9eNowi4";
const JAVA_PARTNER_HS512: &str = "eyJhbGciOiJIUzUxMiJ9.eyJqdGkiOiJqdGktcGFydG5lciIsImlzcyI6ImtvdmlsIiwic3ViIjoiMjIyMjIyMjItMjIyMi0yMjIyLTIyMjItMjIyMjIyMjIyMjIyIiwicm9sZSI6IlBBUlRORVIiLCJkaXZpc2lvbnMiOlsiUkVBTF9FU1RBVEVfQlJPS0VSIl0sImlhdCI6MTc2MzEwMDAwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.QkNX6sBoZ1YxWfsz_TPkPQayQxihwUiKkGrvYwQnLiWbO7qkhGzgEPQifrvWrLdX6FV1S7lVv-oPdOvI8IiMYQ";
const FAR: i64 = 4_102_444_800;

fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64
}

fn mint(claims: Value) -> String {
    let raw = base64::engine::general_purpose::STANDARD.decode(K32).unwrap();
    encode(&Header::new(Algorithm::HS256), &claims, &EncodingKey::from_secret(&raw)).unwrap()
}

fn token(role: &str, jti: &str, sub: &str) -> String {
    mint(json!({"jti": jti, "iss": "kovil", "sub": sub, "role": role, "iat": now() - 5, "exp": now() + 600}))
}

fn app(revocations: Option<Revocations>) -> axum::Router {
    // Nothing listens on port 9: a request that gets past auth fails at the DB with 500.
    let db = MySqlPoolOptions::new()
        .acquire_timeout(Duration::from_millis(300))
        .connect_lazy("mysql://u:p@127.0.0.1:9/none")
        .expect("lazy pool");
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/messages");
    let auth = Auth { jwt: Some(JwtVerifier::new(K32, "kovil").unwrap()), revocations };
    router(AppState::new(db, Messages::load(&dir).expect("bundles")).with_auth(auth))
}

async fn call(app: axum::Router, method: &str, uri: &str, bearer: Option<&str>, body: Option<&str>) -> (StatusCode, Value) {
    let mut req = Request::builder().method(method).uri(uri).header("Accept-Language", "ko");
    if let Some(t) = bearer {
        req = req.header("Authorization", format!("Bearer {t}"));
    }
    if body.is_some() {
        req = req.header("Content-Type", "application/json");
    }
    let res = app.oneshot(req.body(body.map(|b| Body::from(b.to_owned())).unwrap_or_default()).unwrap()).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

const LIST: &str = "/api/v1/admin/app-update-policies/STUDENT";

#[test]
fn java_minted_tokens_verify() {
    let admin = JwtVerifier::new(K32, "kovil").unwrap().verify(JAVA_ADMIN).expect("JJWT HS256 token");
    assert_eq!((admin.user_uuid.as_str(), admin.role, admin.jti.as_deref()), ("11111111-1111-1111-1111-111111111111", Role::Admin, Some("jti-admin")));
    assert_eq!((admin.issued_at, admin.expires_at), (1_763_100_000, FAR));
    let partner = JwtVerifier::new(K64, "kovil").unwrap().verify(JAVA_PARTNER_HS512).expect("JJWT HS512 token");
    assert_eq!((partner.role, partner.divisions.as_slice()), (Role::Partner, &[Division::RealEstateBroker][..]));
    // Unpadded secret works, as with java.util.Base64.
    assert!(JwtVerifier::new(K32.trim_end_matches('='), "kovil").unwrap().verify(JAVA_ADMIN).is_some());
    // Same token, other key or issuer: rejected.
    assert!(JwtVerifier::new(K64, "kovil").unwrap().verify(JAVA_ADMIN).is_none());
    assert!(JwtVerifier::new(K32, "other").unwrap().verify(JAVA_ADMIN).is_none());
    // A 32-byte key cannot verify HS512, as JJWT refuses a key too weak for the header's algorithm.
    assert!(JwtVerifier::new(K32, "kovil").unwrap().verify(JAVA_PARTNER_HS512).is_none());
}

#[test]
fn bad_claims_void_the_token() {
    let v = JwtVerifier::new(K32, "kovil").unwrap();
    let base = |extra: Value| {
        let mut c = json!({"jti": "j", "iss": "kovil", "sub": "u", "role": "USER", "iat": now(), "exp": now() + 60});
        c.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
        mint(c)
    };
    assert!(v.verify(&base(json!({}))).is_some());
    assert_eq!(v.verify(&base(json!({"role": "user"}))).unwrap().role, Role::User); // case-insensitive like UserRole.from
    assert!(v.verify(&base(json!({"role": "BROKER"}))).is_none()); // retired role
    assert!(v.verify(&base(json!({"role": "PARTNER", "divisions": ["NOPE"]}))).is_none());
    assert!(v.verify(&base(json!({"role": "PARTNER", "divisions": "REAL_ESTATE_BROKER"}))).is_none()); // not a list
    assert!(v.verify(&base(json!({"divisions": null}))).is_some());
    assert!(v.verify(&base(json!({"exp": now() - 1}))).is_none());
    assert!(v.verify(&base(json!({"nbf": now() + 60}))).is_none());
    assert!(v.verify(&base(json!({"iss": "evil"}))).is_none());
    let unsigned = format!("eyJhbGciOiJub25lIn0.{}.", JAVA_ADMIN.split('.').nth(1).unwrap());
    assert!(v.verify(&unsigned).is_none());
}

#[tokio::test]
async fn admin_route_order_is_401_then_400_then_403() {
    let (st, body) = call(app(None), "GET", LIST, None, None).await;
    assert_eq!((st, body["errorCode"].as_str()), (StatusCode::UNAUTHORIZED, Some("UNAUTHORIZED_ACCESS")));
    assert_eq!(body["message"], "로그인이 필요해요.");
    let (st, _) = call(app(None), "GET", LIST, Some("garbage"), None).await;
    assert_eq!(st, StatusCode::UNAUTHORIZED);
    let user = token("USER", "j1", "u1");
    let (st, body) = call(app(None), "GET", "/api/v1/admin/app-update-policies/nope", Some(&user), None).await;
    assert_eq!((st, body["errorCode"].as_str()), (StatusCode::BAD_REQUEST, Some("INVALID_REQUEST")));
    let (st, body) = call(app(None), "GET", LIST, Some(&user), None).await;
    assert_eq!((st, body["errorCode"].as_str()), (StatusCode::FORBIDDEN, Some("FORBIDDEN_ACCESS")));
    let (st, _) = call(app(None), "PUT", "/api/v1/admin/app-update-policies/STUDENT/IOS", Some(&user), Some("{bad")).await;
    assert_eq!(st, StatusCode::BAD_REQUEST);
    let (st, body) = call(app(None), "PUT", "/api/v1/admin/app-update-policies/STUDENT/IOS", Some(&user), Some("{}")).await;
    assert_eq!(body["errorCode"], "FORBIDDEN_ACCESS", "{st}");
    let broker = mint(json!({"jti": "j4", "iss": "kovil", "sub": "p1", "role": "PARTNER", "divisions": ["REAL_ESTATE_BROKER"],
                             "iat": now(), "exp": now() + 60}));
    let (st, _) = call(app(None), "GET", LIST, Some(&broker), None).await;
    assert_eq!(st, StatusCode::FORBIDDEN, "a broker can list homes but not edit app policy");
    let admin = token("ADMIN", "j5", "a1");
    let res = app(None)
        .oneshot(Request::get(LIST).header("Authorization", format!("bearer {admin}")).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::UNAUTHORIZED, "scheme is case-sensitive, as in JwtAuthenticationFilter");
    // Admin passes auth and reaches the (absent) DB.
    let (st, _) = call(app(None), "GET", LIST, Some(&token("ADMIN", "j2", "a1")), None).await;
    assert_eq!(st, StatusCode::INTERNAL_SERVER_ERROR);
}

#[tokio::test]
async fn redis_down_fails_open() {
    let dead = Revocations::lazy("redis://127.0.0.1:9").unwrap();
    let (st, _) = call(app(Some(dead)), "GET", LIST, Some(&token("ADMIN", "j3", "a1")), None).await;
    assert_eq!(st, StatusCode::INTERNAL_SERVER_ERROR, "signature check alone must still let the admin through");
    let started = std::time::Instant::now();
    let dead = Revocations::lazy("redis://127.0.0.1:9").unwrap();
    call(app(Some(dead)), "GET", LIST, Some(&token("ADMIN", "j6", "a1")), None).await;
    assert!(started.elapsed() < Duration::from_millis(1500), "Redis outage must not stall requests: {:?}", started.elapsed());
}

#[tokio::test]
async fn revoked_tokens_are_rejected() {
    let Ok(url) = std::env::var("KOVIL_TEST_REDIS_URL") else {
        eprintln!("KOVIL_TEST_REDIS_URL not set; skipping");
        return;
    };
    let client = redis::Client::open(url.as_str()).unwrap();
    let mut conn = client.get_multiplexed_async_connection().await.unwrap();
    let suffix = now();
    let (jti, user) = (format!("rust-test-jti-{suffix}"), format!("rust-test-user-{suffix}"));
    let _: () = redis::cmd("SET").arg(format!("blacklist:jti:{jti}")).arg("1").arg("EX").arg(60).query_async(&mut conn).await.unwrap();
    let revs = || Some(Revocations::lazy(&url).unwrap());
    let (st, _) = call(app(revs()), "GET", LIST, Some(&token("ADMIN", &jti, "other")), None).await;
    assert_eq!(st, StatusCode::UNAUTHORIZED, "logged-out jti");
    let (st, _) = call(app(revs()), "GET", LIST, Some(&token("ADMIN", "fresh", &user)), None).await;
    assert_eq!(st, StatusCode::INTERNAL_SERVER_ERROR, "no revocation yet");
    let _: () = redis::cmd("SET").arg(format!("blacklist:user:{user}")).arg(now().to_string()).arg("EX").arg(60).query_async(&mut conn).await.unwrap();
    let (st, _) = call(app(revs()), "GET", LIST, Some(&token("ADMIN", "fresh", &user)), None).await;
    assert_eq!(st, StatusCode::UNAUTHORIZED, "issued before the user cutoff");
}
