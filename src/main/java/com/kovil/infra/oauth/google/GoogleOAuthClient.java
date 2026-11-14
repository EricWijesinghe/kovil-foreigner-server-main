package com.kovil.infra.oauth.google;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.OAuthClient;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import com.kovil.infra.oauth.google.dto.GoogleTokenResponse;
import com.kovil.infra.oauth.google.dto.GoogleUserInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class GoogleOAuthClient implements OAuthClient {

    private final GoogleOAuthProperties properties;
    private final GoogleTokenClient tokenClient;
    private final GoogleUserInfoClient userInfoClient;

    @Override
    public AuthType provider() {
        return AuthType.GOOGLE;
    }

    @Override
    public String buildAuthorizationUri(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                // ★ PKCE — 구글이 code 를 이 challenge 에 묶는다. discovery 의
                //   code_challenge_methods_supported 가 ["plain","S256"] 로 지원을 광고한다(실측).
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                // ★ encode() 없이 toUriString() 하면 값 안의 공백·특수문자가 그대로 남는다.
                // 실사고(2026-08-22): 구글 scope "openid email profile" 의 공백이 인코딩되지 않아
                // authorize URL 이 깨졌다(브라우저·curl 이 거부). 지금 scope 가 한 단어인
                // 프로바이더도 값이 늘면 같은 사고가 나므로 셋 다 인코딩한다.
                .encode().build().toUriString();
    }

    @Override
    public OAuthUserInfo getUserInfo(String code, String state, String userPayload,
                                     String codeVerifier) {
        // code_verifier 가 있어야 구글이 인가 때 받은 challenge 와 대조한다 —
        // 이게 빠지면 훔친 code 를 남의 verifier 로 바꿔치기할 수 있다.
        GoogleTokenResponse token = tokenClient.getToken(Map.of(
                "grant_type", "authorization_code",
                "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(),
                "redirect_uri", properties.redirectUri(),
                "code_verifier", codeVerifier,
                "code", code));

        GoogleUserInfoResponse userInfo = userInfoClient.getUserInfo("Bearer " + token.accessToken());
        // name은 profile scope가 있어도 보장되지 않는다 — 오면 쓰고 없으면 null이다.
        return new OAuthUserInfo(userInfo.sub(), userInfo.email(), userInfo.name());
    }
}
