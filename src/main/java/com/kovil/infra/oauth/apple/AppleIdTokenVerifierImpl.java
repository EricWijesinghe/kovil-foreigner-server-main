package com.kovil.infra.oauth.apple;

import com.kovil.domain.auth.exception.OAuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_TOKEN_INVALID;

// 키는 kid 별로 메모리에 둔다. Apple 이 키를 돌리면 처음 보는 kid 가 오므로 그때 한 번 다시 받는다.
// 다시 받기는 최소 1분 간격이다. 아무 kid 나 박은 토큰을 연달아 보내 Apple 로 요청을 증폭시키지 못하게.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class AppleIdTokenVerifierImpl implements AppleIdTokenVerifier {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String ALGORITHM = "RS256";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration MIN_REFETCH_INTERVAL = Duration.ofMinutes(1);

    private final AppleOAuthProperties properties;
    private final AppleKeysClient keysClient;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    private Map<String, PublicKey> keys = Map.of();
    private Instant fetchedAt;
    private Instant lastAttemptAt;

    // 키 없이 켜지면 모든 Apple 로그인이 조용히 실패한다. 부팅에서 멈춰 배포 헬스체크가 실패하게.
    @PostConstruct
    void requireCredentials() {
        if (!properties.nativeEnabled()) {
            return;
        }
        if (isBlank(properties.clientId()) || isBlank(properties.teamId())
                || isBlank(properties.keyId()) || isBlank(properties.privateKey())) {
            throw new IllegalStateException(
                    "oauth.apple.native-enabled=true 인데 APPLE_CLIENT_ID · TEAM_ID · KEY_ID · PRIVATE_KEY 중 빈 값이 있다");
        }
    }

    @Override
    public AppleIdTokenClaims verify(String idToken, String nonce, String audience) {
        if (isBlank(idToken) || isBlank(nonce) || isBlank(audience)) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        Claims claims;
        try {
            claims = parser(idToken)
                    // 네이티브는 번들 ID, 웹은 Services ID 여야 한다. Expo Go(host.exp.Exponent) · 다른 앱이 받은 토큰을 여기서 끊는다.
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.info("[oauth] Apple id_token 거절 {}", e.getClass().getSimpleName());
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }

        // jjwt 는 exp 가 있을 때만 본다. 없는 토큰은 영원히 통하므로 직접 막는다.
        if (claims.getExpiration() == null || isBlank(claims.getSubject())) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        if (!nonceMatches(claims.get("nonce"), nonce)) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        Object email = claims.get("email");
        return new AppleIdTokenClaims(claims.getSubject(),
                email instanceof String value && !value.isBlank() ? value : null,
                flag(claims.get("email_verified")),
                flag(claims.get("is_private_email")));
    }

    // 알림에는 exp 가 없다(Apple 예시도 iat 만 있다). 같은 알림이 다시 와도 처리 결과가 같아 iat 로 막지 않는다.
    @Override
    public AppleAccountEvent verifyEvent(String payload) {
        if (isBlank(payload)) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        Claims claims;
        try {
            claims = parser(payload).build().parseSignedClaims(payload).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.info("[oauth] Apple 알림 거절 {}", e.getClass().getSimpleName());
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        Set<String> audiences = claims.getAudience();
        boolean ours = audiences != null && audiences.stream()
                .anyMatch(aud -> aud.equals(properties.clientId()) || aud.equals(properties.webClientId()));
        if (!ours) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        JsonNode events = eventsOf(claims.get("events"));
        String type = events.path("type").asString("");
        String sub = events.path("sub").asString("");
        if (type.isBlank() || sub.isBlank()) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        return new AppleAccountEvent(type, sub);
    }

    private JwtParserBuilder parser(String jws) {
        return Jwts.parser()
                .verifyWith(keyFor(signingKeyId(jws)))
                .requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(CLOCK_SKEW.toSeconds());
    }

    // 문서 예시는 객체인데, 실제 알림은 JSON 을 문자열로 한 번 더 감싸 보낸다는 보고가 있어 둘 다 받는다.
    private JsonNode eventsOf(Object events) {
        try {
            if (events instanceof String text) {
                return jsonMapper.readTree(text);
            }
            if (events instanceof Map<?, ?> map) {
                return jsonMapper.valueToTree(map);
            }
        } catch (RuntimeException e) {
            log.info("[oauth] Apple 알림 events 를 읽지 못함");
        }
        throw new OAuthException(OAUTH_TOKEN_INVALID);
    }

    // jjwt 의 verifyWith 는 키 종류만 맞으면 RS384 · PS256 도 받는다. Apple 이 쓰는 RS256 하나로 좁힌다.
    private String signingKeyId(String identityToken) {
        try {
            String[] parts = identityToken.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("JWS 형식이 아니다");
            }
            JsonNode header = jsonMapper.readTree(
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            String kid = header.path("kid").asString("");
            if (!ALGORITHM.equals(header.path("alg").asString("")) || kid.isBlank()) {
                throw new IllegalArgumentException("alg · kid 가 맞지 않는다");
            }
            return kid;
        } catch (RuntimeException e) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
    }

    private synchronized PublicKey keyFor(String kid) {
        Instant now = clock.instant();
        // 키가 하나도 없을 때도 1분 간격을 지킨다. 안 지키면 Apple 장애 동안 요청마다 이 락 안에서 Feign 시한만큼 기다려
        // 비로그인 알림 경로로 요청 스레드를 다 묶을 수 있다.
        if (keys.isEmpty()) {
            if (!canRefetch(now)) {
                throw new OAuthException(OAUTH_PROVIDER_UNAVAILABLE);
            }
            refresh(now);
        } else if (!now.isBefore(fetchedAt.plus(CACHE_TTL)) && canRefetch(now)) {
            refresh(now);
        }
        PublicKey key = keys.get(kid);
        if (key == null && canRefetch(now)) {
            refresh(now);
            key = keys.get(kid);
        }
        if (key == null) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        return key;
    }

    private boolean canRefetch(Instant now) {
        return lastAttemptAt == null || !now.isBefore(lastAttemptAt.plus(MIN_REFETCH_INTERVAL));
    }

    // 받아 둔 키가 있으면 실패해도 그 키로 계속 검증한다. 하나도 없을 때만 장애로 돌려준다.
    private void refresh(Instant now) {
        lastAttemptAt = now;
        try {
            keys = parse(keysClient.getKeys());
            fetchedAt = now;
        } catch (RuntimeException e) {
            log.warn("[oauth] Apple 공개키 받기 실패 {}", e.getClass().getSimpleName());
            if (keys.isEmpty()) {
                throw new OAuthException(OAUTH_PROVIDER_UNAVAILABLE);
            }
        }
    }

    private static Map<String, PublicKey> parse(String json) {
        JwkSet set = Jwks.setParser().build().parse(json);
        Map<String, PublicKey> parsed = new HashMap<>();
        for (Jwk<?> jwk : set.getKeys()) {
            if (jwk.getId() != null && jwk.toKey() instanceof PublicKey publicKey) {
                parsed.put(jwk.getId(), publicKey);
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException("JWKS 에 공개키가 없다");
        }
        return Map.copyOf(parsed);
    }

    // TODO(실기기에서 클레임 값이 확인되면 한쪽만 남긴다): Apple 문서가 nonce 클레임이 넘긴 원문인지 SHA-256(hex)인지 밝히지 않아 둘 다 받는다.
    private static boolean nonceMatches(Object claim, String nonce) {
        if (!(claim instanceof String value)) {
            return false;
        }
        byte[] actual = value.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, nonce.getBytes(StandardCharsets.UTF_8))
                || MessageDigest.isEqual(actual, sha256Hex(nonce).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Apple 은 이 둘을 불리언으로도, "true" · "false" 문자열로도 준다.
    private static Boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
