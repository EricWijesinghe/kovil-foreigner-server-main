package com.kovil.infra.oauth.apple;

import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_TOKEN_INVALID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class AppleTokenExchangerImpl implements AppleTokenExchanger {

    private final AppleOAuthProperties properties;
    private final AppleTokenClient tokenClient;
    private final AppleRevokeClient revokeClient;
    private final AppleClientSecretFactory clientSecretFactory;
    private final JsonMapper jsonMapper;

    @Override
    public String exchange(String authorizationCode, String expectedSub) {
        AppleTokenResponse token;
        try {
            // redirect_uri 는 싣지 않는다. 인가 요청에 넣었을 때만 보내는 값인데 네이티브 인가에는 없다.
            token = tokenClient.getToken(Map.of(
                    "grant_type", "authorization_code",
                    "client_id", properties.clientId(),
                    "client_secret", clientSecretFactory.create(properties.clientId()),
                    "code", authorizationCode));
        } catch (FeignException e) {
            throw translate("code 교환", e);
        }
        if (token == null || isBlank(token.refreshToken()) || isBlank(token.idToken())) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        // 이 id_token 은 Apple 과 TLS 로 직접 받은 것이라 서명을 다시 보지 않는다(OIDC Core 3.1.3.7).
        // 대신 앱이 보낸 identityToken 과 같은 사람인지 sub 로 맞춘다. 남의 code 를 섞어 보내면 여기서 끊긴다.
        if (!expectedSub.equals(subjectOf(token.idToken()))) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        return token.refreshToken();
    }

    @Override
    public AppleTokenResponse exchangeWeb(String authorizationCode) {
        AppleTokenResponse token;
        try {
            token = tokenClient.getToken(Map.of(
                    "grant_type", "authorization_code",
                    "client_id", properties.webClientId(),
                    "client_secret", clientSecretFactory.create(properties.webClientId()),
                    "redirect_uri", properties.redirectUri(),
                    "code", authorizationCode));
        } catch (FeignException e) {
            throw translate("웹 code 교환", e);
        }
        if (token == null || isBlank(token.refreshToken()) || isBlank(token.idToken())) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        return token;
    }

    @Override
    public void revoke(String refreshToken, String clientId) {
        try {
            revokeClient.revoke(Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecretFactory.create(clientId),
                    "token", refreshToken,
                    "token_type_hint", "refresh_token"));
        } catch (FeignException e) {
            log.warn("[oauth] Apple 토큰 철회 실패 status={}", e.status());
        }
    }

    // 예외 객체를 찍지 않는다. Feign 메시지에 응답 본문이 섞인다.
    private OAuthException translate(String what, FeignException e) {
        log.warn("[oauth] Apple {} 실패 status={}", what, e.status());
        if (e instanceof RetryableException || e.status() >= 500) {
            return new OAuthException(OAUTH_PROVIDER_UNAVAILABLE);
        }
        return new OAuthException(OAUTH_TOKEN_INVALID);
    }

    private String subjectOf(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            JsonNode claims = jsonMapper.readTree(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return claims.path("sub").asString(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
