package com.kovil.infra.oauth.apple;

import com.kovil.domain.auth.exception.OAuthException;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.codec.Decoder;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 앱이 보낸 identityToken 은 제3자(앱)를 거쳐 온 토큰이다. 서명 · iss · aud · exp · nonce 중 하나라도 빠지면
 * 남이 만든 토큰이나 다른 앱이 받은 토큰으로 로그인된다. JWKS 는 HttpServer 스텁으로, 토큰은 직접 만든 RSA 키로 서명한다.
 */
class AppleIdTokenVerifierTest {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String BUNDLE_ID = "com.kovil.app";
    private static final String WEB_ID = "com.kovil.web";
    private static final String NONCE = "Yp3t1oO2c4m8bQkXvL0aZr6eWn9sHgDfJuKiTyM5qE7";
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    private static final KeyPair KEY_A = rsa();
    private static final KeyPair KEY_B = rsa();

    private HttpServer server;
    private final AtomicInteger keyRequests = new AtomicInteger();
    private final AtomicReference<String> jwks = new AtomicReference<>();
    private final AtomicInteger jwksStatus = new AtomicInteger(200);
    private final MutableClock clock = new MutableClock(T0);

    @BeforeEach
    void startStub() throws Exception {
        jwks.set(set(jwk("kid-a", KEY_A)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/keys", exchange -> {
            keyRequests.incrementAndGet();
            byte[] body = jwks.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(jwksStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private AppleIdTokenVerifierImpl verifier() {
        AppleKeysClient keysClient = Feign.builder()
                .contract(new SpringMvcContract())
                .decoder(new Decoder.Default())
                .target(AppleKeysClient.class,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/keys");
        return new AppleIdTokenVerifierImpl(properties(true, BUNDLE_ID), keysClient,
                JsonMapper.builder().build(), clock);
    }

    private static AppleOAuthProperties properties(boolean nativeEnabled, String clientId) {
        return new AppleOAuthProperties(nativeEnabled, clientId, WEB_ID, "TEAMID1234", "KEYID12345", "pem",
                "https://api.kovil.invalid/oauth/apple/callback", "https://appleid.apple.invalid/auth/authorize",
                "https://appleid.apple.invalid/auth/token", "https://appleid.apple.invalid/auth/keys",
                "https://appleid.apple.invalid/auth/revoke");
    }

    private String token(String kid, KeyPair key, Consumer<JwtBuilder> custom) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(kid).and()
                .issuer(ISSUER)
                .audience().add(BUNDLE_ID).and()
                .subject("001234.abcdef.0001")
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(10))))
                .claim("nonce", NONCE)
                .claim("email", "x1y2z3@privaterelay.appleid.com")
                .claim("email_verified", true)
                .claim("is_private_email", "true");
        custom.accept(builder);
        return builder.signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private String token(Consumer<JwtBuilder> custom) {
        return token("kid-a", KEY_A, custom);
    }

    private String validToken() {
        return token(b -> { });
    }

    private void assertRejected(String identityToken, String nonce, String code) {
        AppleIdTokenVerifierImpl verifier = verifier();
        assertThatThrownBy(() -> verifier.verify(identityToken, nonce, BUNDLE_ID))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("정상 토큰이면 sub · 이메일 · 두 플래그를 돌려준다")
    void valid_token_passes() {
        AppleIdTokenClaims claims = verifier().verify(validToken(), NONCE, BUNDLE_ID);

        assertThat(claims.sub()).isEqualTo("001234.abcdef.0001");
        assertThat(claims.email()).isEqualTo("x1y2z3@privaterelay.appleid.com");
        assertThat(claims.emailVerified()).isTrue();
        assertThat(claims.privateEmail()).isTrue();
    }

    @Nested
    @DisplayName("서명")
    class Signature {

        // 공개 JWKS 의 kid 만 베껴 자기 키로 서명한 토큰 — 서명을 안 보면 아무 sub 로나 로그인된다.
        @Test
        @DisplayName("같은 kid 를 단 다른 키의 서명은 거절한다")
        void forged_signature_is_rejected() {
            assertRejected(token("kid-a", KEY_B, b -> { }), NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("서명 부분을 바꾸면 거절한다")
        void tampered_signature_is_rejected() {
            String valid = validToken();
            String tampered = valid.substring(0, valid.lastIndexOf('.') + 1) + "AAAA" + valid.substring(valid.lastIndexOf('.') + 5);
            assertRejected(tampered, NONCE, "OAUTH_TOKEN_INVALID");
        }

        // 서명 없는 토큰(alg=none)을 받아 주면 검증 전체가 무의미하다.
        @Test
        @DisplayName("서명 없는 토큰은 거절한다")
        void unsigned_token_is_rejected() {
            String unsigned = Jwts.builder().header().keyId("kid-a").and()
                    .issuer(ISSUER).audience().add(BUNDLE_ID).and().subject("sub")
                    .expiration(Date.from(T0.plusSeconds(600))).claim("nonce", NONCE).compact();
            assertRejected(unsigned, NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("RS256 이 아닌 알고리즘은 같은 키로 서명돼도 거절한다")
        void other_algorithm_is_rejected() {
            String rs512 = Jwts.builder().header().keyId("kid-a").and()
                    .issuer(ISSUER).audience().add(BUNDLE_ID).and().subject("sub")
                    .expiration(Date.from(T0.plusSeconds(600))).claim("nonce", NONCE)
                    .signWith(KEY_A.getPrivate(), Jwts.SIG.RS512).compact();
            assertRejected(rs512, NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("JWT 형식이 아니면 거절한다")
        void garbage_is_rejected() {
            assertRejected("not-a-jwt", NONCE, "OAUTH_TOKEN_INVALID");
            assertRejected("a.b.c", NONCE, "OAUTH_TOKEN_INVALID");
        }
    }

    @Nested
    @DisplayName("공개키 받기")
    class Keys {

        @Test
        @DisplayName("받은 키는 다시 받지 않고 쓴다")
        void keys_are_cached() {
            AppleIdTokenVerifierImpl verifier = verifier();
            verifier.verify(validToken(), NONCE, BUNDLE_ID);
            verifier.verify(validToken(), NONCE, BUNDLE_ID);

            assertThat(keyRequests).hasValue(1);
        }

        // Apple 이 키를 돌리면 처음 보는 kid 가 온다. 다시 받지 않으면 돌린 순간부터 모든 로그인이 막힌다.
        @Test
        @DisplayName("모르는 kid 가 오면 한 번 다시 받아 검증한다")
        void unknown_kid_refetches_once() {
            AppleIdTokenVerifierImpl verifier = verifier();
            verifier.verify(validToken(), NONCE, BUNDLE_ID);
            jwks.set(set(jwk("kid-a", KEY_A), jwk("kid-b", KEY_B)));
            clock.advance(Duration.ofMinutes(2));

            AppleIdTokenClaims claims = verifier.verify(token("kid-b", KEY_B, b -> { }), NONCE, BUNDLE_ID);

            assertThat(claims.sub()).isEqualTo("001234.abcdef.0001");
            assertThat(keyRequests).hasValue(2);
        }

        // 아무 kid 나 박은 토큰을 연달아 보내 Apple 로 요청을 증폭시키지 못하게 한다.
        @Test
        @DisplayName("다시 받기는 1분에 한 번이다 — 그 안의 모르는 kid 는 받지 않고 거절한다")
        void refetch_is_rate_limited() {
            AppleIdTokenVerifierImpl verifier = verifier();
            verifier.verify(validToken(), NONCE, BUNDLE_ID);
            jwks.set(set(jwk("kid-a", KEY_A), jwk("kid-b", KEY_B)));
            String rotated = token("kid-b", KEY_B, b -> { });

            clock.advance(Duration.ofSeconds(30));
            assertThatThrownBy(() -> verifier.verify(rotated, NONCE, BUNDLE_ID)).isInstanceOf(OAuthException.class);
            assertThatThrownBy(() -> verifier.verify(token("kid-zzz", KEY_B, b -> { }), NONCE, BUNDLE_ID))
                    .isInstanceOf(OAuthException.class);
            assertThat(keyRequests).hasValue(1);

            clock.advance(Duration.ofSeconds(31));
            assertThat(verifier.verify(token("kid-b", KEY_B, b -> { }), NONCE, BUNDLE_ID).sub()).isNotBlank();
            assertThat(keyRequests).hasValue(2);
        }

        @Test
        @DisplayName("다시 받아도 없는 kid 는 거절한다")
        void unknown_kid_after_refetch_is_rejected() {
            assertRejected(token("kid-zzz", KEY_A, b -> { }), NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("하루가 지나면 키를 새로 받는다")
        void keys_expire_after_a_day() {
            AppleIdTokenVerifierImpl verifier = verifier();
            verifier.verify(validToken(), NONCE, BUNDLE_ID);
            clock.advance(Duration.ofHours(24));

            verifier.verify(validToken(), NONCE, BUNDLE_ID);

            assertThat(keyRequests).hasValue(2);
        }

        // 사용자 탓이 아니므로 "다시 시도" 가 아니라 "잠시 뒤" 다.
        @Test
        @DisplayName("받아 둔 키가 없는데 Apple 이 실패하면 OAUTH_PROVIDER_UNAVAILABLE 이다")
        void keys_endpoint_down_is_unavailable() {
            jwksStatus.set(500);
            assertRejected(validToken(), NONCE, "OAUTH_PROVIDER_UNAVAILABLE");
        }

        // 키가 없는 동안에도 1분 간격을 지켜야 Apple 장애 때 요청마다 락 안에서 Feign 시한을 기다리지 않는다.
        @Test
        @DisplayName("받아 둔 키가 없을 때도 다시 받기는 1분에 한 번이다 · 그 안은 바로 OAUTH_PROVIDER_UNAVAILABLE")
        void empty_keys_refetch_is_rate_limited() {
            AppleIdTokenVerifierImpl verifier = verifier();
            jwksStatus.set(500);
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> verifier.verify(validToken(), NONCE, BUNDLE_ID))
                        .isInstanceOf(OAuthException.class)
                        .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                        .isEqualTo("OAUTH_PROVIDER_UNAVAILABLE");
            }
            assertThat(keyRequests).hasValue(1);

            jwksStatus.set(200);
            clock.advance(Duration.ofSeconds(61));
            assertThat(verifier.verify(validToken(), NONCE, BUNDLE_ID).sub()).isNotBlank();
            assertThat(keyRequests).hasValue(2);
        }

        @Test
        @DisplayName("받아 둔 키가 있으면 다시 받기가 실패해도 그 키로 검증한다")
        void stale_keys_survive_outage() {
            AppleIdTokenVerifierImpl verifier = verifier();
            verifier.verify(validToken(), NONCE, BUNDLE_ID);
            jwksStatus.set(500);
            clock.advance(Duration.ofHours(25));

            assertThat(verifier.verify(validToken(), NONCE, BUNDLE_ID).sub()).isNotBlank();
            assertThat(keyRequests).hasValue(2);
        }
    }

    @Nested
    @DisplayName("클레임")
    class ClaimChecks {

        @Test
        @DisplayName("발급처가 Apple 이 아니면 거절한다")
        void wrong_issuer_is_rejected() {
            assertRejected(token(b -> b.issuer("https://evil.example.test")), NONCE, "OAUTH_TOKEN_INVALID");
        }

        // 웹(Services ID) · Expo Go 가 받은 토큰. 그 경로가 기대한 client_id 의 토큰만 통과해야 한다.
        @Test
        @DisplayName("aud 가 기대한 client_id 가 아니면 거절한다 · 웹 토큰을 네이티브로, Expo Go")
        void wrong_audience_is_rejected() {
            assertRejected(token(b -> b.audience().single(WEB_ID)), NONCE, "OAUTH_TOKEN_INVALID");
            assertRejected(token(b -> b.audience().single("host.exp.Exponent")), NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("웹 경로는 Services ID 토큰을 받고 번들 ID 토큰은 거절한다")
        void web_audience_is_services_id() {
            AppleIdTokenVerifierImpl verifier = verifier();

            assertThat(verifier.verify(token(b -> b.audience().single(WEB_ID)), NONCE, WEB_ID).sub())
                    .isEqualTo("001234.abcdef.0001");
            assertThatThrownBy(() -> verifier.verify(validToken(), NONCE, WEB_ID))
                    .isInstanceOf(OAuthException.class);
        }

        @Test
        @DisplayName("만료된 토큰은 거절한다")
        void expired_token_is_rejected() {
            String expired = token(b -> b.expiration(Date.from(T0.minusSeconds(61))));
            assertRejected(expired, NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("시계 오차 60초 안의 만료는 통과한다")
        void clock_skew_is_tolerated() {
            String justExpired = token(b -> b.expiration(Date.from(T0.minusSeconds(30))));
            assertThat(verifier().verify(justExpired, NONCE, BUNDLE_ID).sub()).isNotBlank();
        }

        // jjwt 는 exp 가 있을 때만 본다 — 없는 토큰은 영원히 통한다.
        @Test
        @DisplayName("exp 가 없는 토큰은 거절한다")
        void missing_expiration_is_rejected() {
            assertRejected(token(b -> b.expiration(null)), NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("sub 가 없으면 거절한다")
        void missing_subject_is_rejected() {
            assertRejected(token(b -> b.subject(null)), NONCE, "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("nonce 클레임이 없으면 거절한다")
        void missing_nonce_claim_is_rejected() {
            assertRejected(token(b -> b.claims().delete("nonce")), NONCE, "OAUTH_TOKEN_INVALID");
        }

        // 다른 로그인 시도의 토큰을 가져와 이번 nonce 로 보내는 재전송.
        @Test
        @DisplayName("nonce 가 소모한 값과 다르면 거절한다")
        void mismatched_nonce_is_rejected() {
            assertRejected(validToken(), "another-nonce-value", "OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("nonce 클레임이 원문의 SHA-256(hex) 이어도 통과한다")
        void hashed_nonce_claim_passes() {
            String hashed = token(b -> b.claim("nonce", sha256Hex(NONCE)));
            assertThat(verifier().verify(hashed, NONCE, BUNDLE_ID).sub()).isNotBlank();
        }

        @Test
        @DisplayName("email_verified · is_private_email 은 문자열로 와도 읽는다")
        void flags_accept_strings_and_booleans() {
            AppleIdTokenClaims claims = verifier().verify(
                    token(b -> b.claim("email_verified", "true").claim("is_private_email", false)), NONCE, BUNDLE_ID);

            assertThat(claims.emailVerified()).isTrue();
            assertThat(claims.privateEmail()).isFalse();
        }

        @Test
        @DisplayName("이메일 · 플래그가 없으면 null 이다")
        void missing_email_is_null() {
            AppleIdTokenClaims claims = verifier().verify(token(b -> b.claims()
                    .delete("email").delete("email_verified").delete("is_private_email")), NONCE, BUNDLE_ID);

            assertThat(claims.email()).isNull();
            assertThat(claims.emailVerified()).isNull();
            assertThat(claims.privateEmail()).isNull();
        }
    }

    /*
     * 서버 간 알림. 공개 주소라 누구나 POST 할 수 있다. 서명이 빠지면 아무나 남의 세션을 끊는다.
     * 문서 예시는 events 가 객체지만 실제로는 문자열로 감싸 온다는 보고가 있어 둘 다 고정한다.
     */
    @Nested
    @DisplayName("서버 간 알림")
    class Events {

        private String event(String kid, KeyPair key, Consumer<JwtBuilder> custom) {
            JwtBuilder builder = Jwts.builder()
                    .header().keyId(kid).and()
                    .issuer(ISSUER)
                    .audience().add(BUNDLE_ID).and()
                    .issuedAt(Date.from(clock.instant()))
                    .id("jti-1")
                    .claim("events", Map.of("type", "consent-revoked", "sub", "001234.abcdef.0001",
                            "event_time", 1762732800));
            custom.accept(builder);
            return builder.signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
        }

        private void assertEventRejected(String payload) {
            AppleIdTokenVerifierImpl verifier = verifier();
            assertThatThrownBy(() -> verifier.verifyEvent(payload))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("events 가 객체면 종류와 sub 를 읽는다 · exp 가 없어도 된다")
        void object_events_are_read() {
            AppleAccountEvent event = verifier().verifyEvent(event("kid-a", KEY_A, b -> { }));

            assertThat(event.type()).isEqualTo("consent-revoked");
            assertThat(event.sub()).isEqualTo("001234.abcdef.0001");
            assertThat(event.endsConsent()).isTrue();
        }

        @Test
        @DisplayName("events 가 JSON 문자열이어도 읽는다")
        void string_events_are_read() {
            String payload = event("kid-a", KEY_A, b -> b.claim("events",
                    "{\"type\":\"email-disabled\",\"sub\":\"001234.abcdef.0001\",\"is_private_email\":\"true\"}"));

            AppleAccountEvent event = verifier().verifyEvent(payload);

            assertThat(event.type()).isEqualTo("email-disabled");
            assertThat(event.endsConsent()).isFalse();
        }

        @Test
        @DisplayName("aud 는 번들 ID · Services ID 어느 쪽이어도 받고 남의 앱이면 거절한다")
        void audience_must_be_ours() {
            assertThat(verifier().verifyEvent(event("kid-a", KEY_A, b -> b.audience().single(WEB_ID))).sub())
                    .isNotBlank();
            assertEventRejected(event("kid-a", KEY_A, b -> b.audience().single("com.other.app")));
        }

        @Test
        @DisplayName("서명 · 발급처가 틀리면 거절한다")
        void forged_event_is_rejected() {
            assertEventRejected(event("kid-a", KEY_B, b -> { }));
            assertEventRejected(event("kid-a", KEY_A, b -> b.issuer("https://evil.example.test")));
            assertEventRejected("not-a-jwt");
        }

        @Test
        @DisplayName("events 나 그 안의 종류 · sub 가 없으면 거절한다")
        void missing_event_fields_are_rejected() {
            assertEventRejected(event("kid-a", KEY_A, b -> b.claims().delete("events")));
            assertEventRejected(event("kid-a", KEY_A, b -> b.claim("events", Map.of("type", "consent-revoked"))));
        }

        @Test
        @DisplayName("세션을 끊는 종류는 consent-revoked · account-delete(d) 뿐이다")
        void only_revocations_end_consent() {
            assertThat(new AppleAccountEvent("account-delete", "s").endsConsent()).isTrue();
            assertThat(new AppleAccountEvent("account-deleted", "s").endsConsent()).isTrue();
            assertThat(new AppleAccountEvent("email-enabled", "s").endsConsent()).isFalse();
            assertThat(new AppleAccountEvent("something-new", "s").endsConsent()).isFalse();
        }
    }

    // 키 없이 켜지면 모든 Apple 로그인이 조용히 실패한다. 배포 헬스체크에서 걸려야 이전 이미지로 돌아간다.
    @Test
    @DisplayName("네이티브를 켰는데 번들 ID 가 비면 부팅을 멈춘다 — 꺼져 있으면 비어도 된다")
    void enabled_without_credentials_fails_boot() {
        AppleIdTokenVerifierImpl enabled = new AppleIdTokenVerifierImpl(properties(true, ""), null,
                JsonMapper.builder().build(), clock);
        assertThatThrownBy(enabled::requireCredentials).isInstanceOf(IllegalStateException.class);

        new AppleIdTokenVerifierImpl(properties(false, ""), null, JsonMapper.builder().build(), clock)
                .requireCredentials();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jwk(String kid, KeyPair key) {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + base64Url(publicKey.getModulus()) + "\",\"e\":\"" + base64Url(publicKey.getPublicExponent()) + "\"}";
    }

    private static String set(String... keys) {
        return "{\"keys\":[" + String.join(",", keys) + "]}";
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
