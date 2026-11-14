//! Access tokens issued by the Java app: verification, revocation and permissions.
//! Mirrors `infra/jwt/JwtTokenProvider`, `BlacklistedTokenStore`, `JwtAuthenticationFilter`, `UserRole`.
//!
//! Any bad, expired or revoked token answers 401 UNAUTHORIZED_ACCESS on protected routes, as Spring's entry point
//! does; a valid token without the permission answers 403 FORBIDDEN_ACCESS.

use std::time::Duration;

use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode};
use redis::aio::{ConnectionManager, ConnectionManagerConfig};
use serde::Deserialize;

use crate::AppState;
use crate::api::{ApiError, ErrorCode};
use crate::i18n::Lang;

const JTI_PREFIX: &str = "blacklist:jti:";
const USER_PREFIX: &str = "blacklist:user:";
/// Every authenticated request makes one Redis round trip; prod Java uses 500 ms too (application-prod.yml).
pub(crate) const REDIS_TIMEOUT: Duration = Duration::from_millis(500);
/// JJWT `Keys.hmacShaKeyFor` refuses keys under 256 bits.
const MIN_KEY_BYTES: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    User,
    Partner,
    Admin,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Permission {
    RentalRequestView,
    VerificationReview,
    CommunityModerate,
    UserManage,
    PartnerApprove,
    RoleAssign,
    MasterDataManage,
    NotificationManage,
    AppPolicyManage,
    ListingManage,
    ListingModerate,
    RentalRequestHandle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Division {
    RealEstateBroker,
}

impl Division {
    const ALL: [Division; 1] = [Division::RealEstateBroker];

    fn parse(name: &str) -> Option<Self> {
        (name == "REAL_ESTATE_BROKER").then_some(Self::RealEstateBroker)
    }

    fn grants(self, p: Permission) -> bool {
        match self {
            Self::RealEstateBroker => matches!(p, Permission::ListingManage | Permission::RentalRequestHandle),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Principal {
    pub user_uuid: String,
    pub role: Role,
    pub divisions: Vec<Division>,
    pub jti: Option<String>,
    pub issued_at: i64,
    pub expires_at: i64,
}

impl Principal {
    /// `UserRole.permissionsWith`: USER has none, PARTNER gets its approved divisions' permissions, ADMIN gets
    /// everything except what divisions grant (admins run the platform; they do not list homes).
    pub fn can(&self, p: Permission) -> bool {
        match self.role {
            Role::User => false,
            Role::Partner => self.divisions.iter().any(|d| d.grants(p)),
            Role::Admin => !Division::ALL.iter().any(|d| d.grants(p)),
        }
    }

    pub fn require(&self, s: &AppState, lang: &Lang, p: Permission) -> Result<(), ApiError> {
        if self.can(p) { Ok(()) } else { Err(ApiError::new(s, lang, ErrorCode::ForbiddenAccess)) }
    }
}

#[derive(Deserialize)]
struct Claims {
    sub: String,
    role: String,
    divisions: Option<Vec<serde_json::Value>>,
    jti: Option<String>,
    iat: i64,
    exp: i64,
}

pub struct JwtVerifier {
    key: DecodingKey,
    validation: Validation,
}

impl JwtVerifier {
    /// `secret` is base64 (standard alphabet), exactly as the Java app's `JWT_SECRET`.
    pub fn new(secret_b64: &str, issuer: &str) -> Result<Self, String> {
        use base64::Engine;
        use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
        // As lenient as java.util.Base64.getDecoder(): padding optional, non-zero trailing bits allowed.
        let lenient = GeneralPurpose::new(
            &base64::alphabet::STANDARD,
            GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent).with_decode_allow_trailing_bits(true),
        );
        let raw = lenient
            .decode(secret_b64.trim())
            .map_err(|_| "JWT_SECRET must be standard base64")?;
        if raw.len() < MIN_KEY_BYTES {
            return Err(format!("JWT_SECRET must decode to at least {MIN_KEY_BYTES} bytes"));
        }
        let mut validation = Validation::new(Algorithm::HS256);
        // JJWT signs with the strongest HMAC the key allows and verifies any HMAC the key is long enough for.
        validation.algorithms = [(Algorithm::HS256, 32), (Algorithm::HS384, 48), (Algorithm::HS512, 64)]
            .into_iter()
            .filter(|(_, min)| raw.len() >= *min)
            .map(|(a, _)| a)
            .collect();
        validation.leeway = 0; // JJWT default clock skew
        validation.validate_nbf = true;
        validation.set_issuer(&[issuer]);
        validation.set_required_spec_claims(&["exp", "iat", "iss", "sub"]);
        Ok(Self { key: DecodingKey::from_secret(&raw), validation })
    }

    /// None for every rejection: the reason is logged, never sent (Java filter only logs it too).
    pub fn verify(&self, token: &str) -> Option<Principal> {
        let claims = match decode::<Claims>(token, &self.key, &self.validation) {
            Ok(t) => t.claims,
            Err(e) => {
                tracing::warn!("jwt rejected: {:?}", e.kind());
                return None;
            }
        };
        let role = match claims.role.to_ascii_uppercase().as_str() {
            "USER" => Role::User,
            "PARTNER" => Role::Partner,
            "ADMIN" => Role::Admin,
            other => {
                tracing::warn!("jwt with retired or unknown role: {other}");
                return None;
            }
        };
        // An unknown division voids the token: dropping just that one would let token and DB disagree.
        let mut divisions = Vec::new();
        for d in claims.divisions.unwrap_or_default() {
            match d.as_str().and_then(Division::parse) {
                Some(d) if !divisions.contains(&d) => divisions.push(d),
                Some(_) => {}
                None => {
                    tracing::warn!("jwt with unknown partner division: {d}");
                    return None;
                }
            }
        }
        Some(Principal { user_uuid: claims.sub, role, divisions, jti: claims.jti, issued_at: claims.iat, expires_at: claims.exp })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rejection {
    None,
    BlacklistedToken,
    RevokedUser,
}

/// Logout (`blacklist:jti:<jti>`) and "kill every session of this user" (`blacklist:user:<uuid>` = cutoff epoch
/// seconds; tokens issued at or before it are dead). Same keys as Java, one MGET round trip.
#[derive(Clone)]
pub struct Revocations(ConnectionManager);

/// Lazy connection shared by revocation checks and rate limits. Nothing connects until the first command.
pub(crate) fn redis_manager(redis_url: &str) -> Result<ConnectionManager, String> {
    let client = redis::Client::open(redis_url).map_err(|e| format!("REDIS_URL: {e}"))?;
    let config = ConnectionManagerConfig::new()
        .set_connection_timeout(Some(REDIS_TIMEOUT))
        .set_response_timeout(Some(REDIS_TIMEOUT))
        // Reconnects happen in the background; a request must not wait out the default 6 retries.
        .set_number_of_retries(1);
    client.get_connection_manager_lazy(config).map_err(|e| format!("redis: {e}"))
}

impl Revocations {
    /// Lazy: the server starts and serves while Redis is down, like Spring's lazy Lettuce connection.
    pub fn lazy(redis_url: &str) -> Result<Self, String> {
        redis_manager(redis_url).map(Self)
    }

    /// Fail-open on Redis errors (PO decision 2026-09-17): signature checks still hold; the ERROR log is the
    /// only signal that logout and role revocation have stopped working.
    pub async fn check(&self, p: &Principal) -> Rejection {
        let keys = [format!("{JTI_PREFIX}{}", p.jti.as_deref().unwrap_or("")), format!("{USER_PREFIX}{}", p.user_uuid)];
        let mut conn = self.0.clone();
        // Hard ceiling per request, whatever the client's reconnect logic does.
        let mut cmd = redis::cmd("MGET");
        cmd.arg(&keys);
        // Bytes, not String: a non-UTF-8 value must still count as a hit, not fail the decode and slip through.
        let mget = cmd.query_async::<Vec<Option<Vec<u8>>>>(&mut conn);
        match tokio::time::timeout(REDIS_TIMEOUT, mget).await {
            Ok(Ok(v)) if v.len() == 2 => {
                let cutoff = v[1].as_deref().map(String::from_utf8_lossy);
                judge(p, v[0].as_ref().map(|_| ""), cutoff.as_deref())
            }
            Err(_) => {
                tracing::error!("[blacklist] Redis lookup timed out, skipping revocation check (fail-open)");
                Rejection::None
            }
            Ok(Ok(v)) => {
                tracing::error!("[blacklist] MGET returned {} values, skipping revocation check (fail-open)", v.len());
                Rejection::None
            }
            Ok(Err(e)) => {
                tracing::error!("[blacklist] Redis lookup failed, skipping revocation check (fail-open): {e}");
                Rejection::None
            }
        }
    }
}

pub fn judge(p: &Principal, jti_hit: Option<&str>, cutoff: Option<&str>) -> Rejection {
    if p.jti.is_some() && jti_hit.is_some() {
        return Rejection::BlacklistedToken;
    }
    let Some(cutoff) = cutoff else { return Rejection::None };
    match cutoff.parse::<i64>() {
        // `<=`: iat has second precision, so a token minted in the cutoff second is also dead.
        Ok(c) if p.issued_at <= c => Rejection::RevokedUser,
        Ok(_) => Rejection::None,
        Err(_) => {
            tracing::error!("[blacklist] cutoff is not a number, key clash suspected: {cutoff}");
            Rejection::None
        }
    }
}

/// Signature check plus revocation list. Default rejects every token (no secret configured = nobody gets in).
#[derive(Default)]
pub struct Auth {
    pub jwt: Option<JwtVerifier>,
    pub revocations: Option<Revocations>,
}

impl Auth {
    pub async fn authenticate(&self, bearer: &str) -> Option<Principal> {
        let p = self.jwt.as_ref()?.verify(bearer)?;
        if let Some(r) = &self.revocations {
            let rejection = r.check(&p).await;
            if rejection != Rejection::None {
                tracing::warn!("jwt rejected: {rejection:?}");
                return None;
            }
        }
        Some(p)
    }
}

/// Extractor for routes that need a signed-in user. Missing, bad or revoked token -> 401 UNAUTHORIZED_ACCESS.
pub struct Authenticated(pub Principal);

impl FromRequestParts<AppState> for Authenticated {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, s: &AppState) -> Result<Self, Self::Rejection> {
        let bearer = parts.headers.get(AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|h| h.strip_prefix("Bearer "));
        match bearer {
            Some(t) => s.auth.authenticate(t).await,
            None => None,
        }
        .map(Self)
        .ok_or_else(|| {
            let lang = Lang::parse(parts.headers.get(axum::http::header::ACCEPT_LANGUAGE).and_then(|v| v.to_str().ok()));
            ApiError::new(s, &lang, ErrorCode::UnauthorizedAccess)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn principal(role: Role, divisions: Vec<Division>) -> Principal {
        Principal { user_uuid: "u".into(), role, divisions, jti: Some("j".into()), issued_at: 100, expires_at: 200 }
    }

    #[test]
    fn permissions_follow_user_role_table() {
        let admin = principal(Role::Admin, vec![]);
        assert!(admin.can(Permission::AppPolicyManage) && admin.can(Permission::MasterDataManage));
        assert!(!admin.can(Permission::ListingManage) && !admin.can(Permission::RentalRequestHandle));
        assert!(!principal(Role::User, vec![Division::RealEstateBroker]).can(Permission::ListingManage));
        assert!(!principal(Role::Partner, vec![]).can(Permission::ListingManage));
        let broker = principal(Role::Partner, vec![Division::RealEstateBroker]);
        assert!(broker.can(Permission::ListingManage) && !broker.can(Permission::AppPolicyManage));
    }

    #[test]
    fn revocation_rules() {
        let p = principal(Role::User, vec![]);
        assert_eq!(judge(&p, None, None), Rejection::None);
        assert_eq!(judge(&p, Some("1"), None), Rejection::BlacklistedToken);
        assert_eq!(judge(&p, None, Some("100")), Rejection::RevokedUser); // same second is dead
        assert_eq!(judge(&p, None, Some("99")), Rejection::None);
        assert_eq!(judge(&p, None, Some("x")), Rejection::None); // fail-open on foreign values
        let no_jti = Principal { jti: None, ..p };
        assert_eq!(judge(&no_jti, Some("1"), None), Rejection::None);
    }

    #[test]
    fn rejects_weak_or_non_base64_secret() {
        assert!(JwtVerifier::new("c2hvcnQ=", "kovil").is_err());
        assert!(JwtVerifier::new("not base64!!", "kovil").is_err());
    }
}
