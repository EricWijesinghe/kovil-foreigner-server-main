package com.kovil.infra.oauth;

import com.kovil.infra.oauth.dto.OAuthUserInfo;
import com.kovil.infra.oauth.google.GoogleOAuthClient;
import com.kovil.infra.oauth.google.GoogleOAuthProperties;
import com.kovil.infra.oauth.google.GoogleTokenClient;
import com.kovil.infra.oauth.google.GoogleUserInfoClient;
import com.kovil.infra.oauth.google.dto.GoogleTokenResponse;
import com.kovil.infra.oauth.google.dto.GoogleUserInfoResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * PKCE 결속의 **provider 쪽 절반**을 고정한다.
 *
 * 실사고(2026-09-01): challenge 를 인가 URL 에만 싣고 code_verifier 를 토큰 교환에서 빠뜨려,
 * 가로챈 피해자 code 를 공격자 자신의 verifier 로 교환해 계정을 탈취할 수 있었다.
 * PkceValidator 의 서버 자체 대조는 이것을 막지 못한다 — 공격자가 우리 authorize 에서 자기
 * verifier·challenge 짝을 새로 발급받으면 그 대조는 그대로 통과한다.
 *
 * 즉 **결속을 실제로 거는 것은 구글이고, 그 통로는 아래 한 줄뿐이다.** 인가 URL 쪽 절반은
 * AuthorizationUriEncodingTest 가 잡고 있었으나 교환 쪽 절반은 아무것도 잡고 있지 않아,
 * code_verifier 를 지워도 전 테스트가 그린이었다. 그 구멍을 여기서 막는다.
 */
class GooglePkceExchangeTest {

    private static final String VERIFIER = "kA7s-Rj2xQ0pLmVn8YcTbW3eZfHgUiJoKpLqMrNsTuV";

    private static GoogleOAuthProperties properties() {
        return new GoogleOAuthProperties("client-id", "secret",
                "https://wjfapi.example.test/oauth/google/callback",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo");
    }

    @Test
    @DisplayName("토큰 교환 본문에 code_verifier 가 실려 나간다")
    void tokenExchangeCarriesCodeVerifier() {
        GoogleTokenClient tokenClient = mock(GoogleTokenClient.class);
        GoogleUserInfoClient userInfoClient = mock(GoogleUserInfoClient.class);

        AtomicReference<Map<String, Object>> form = new AtomicReference<>();
        given(tokenClient.getToken(anyMap())).willAnswer(invocation -> {
            form.set(invocation.getArgument(0));
            return new GoogleTokenResponse("access-token", "Bearer", null, 3600L);
        });
        given(userInfoClient.getUserInfo("Bearer access-token"))
                .willReturn(new GoogleUserInfoResponse("sub-1", "probe@example.test", "Probe"));

        OAuthUserInfo info = new GoogleOAuthClient(properties(), tokenClient, userInfoClient)
                .getUserInfo("auth-code", "state-1", null, VERIFIER);

        // ★ 이 두 줄이 사라지면 훔친 code 를 남의 verifier 로 교환할 수 있게 된다.
        assertThat(form.get()).containsEntry("code_verifier", VERIFIER);
        assertThat(form.get()).containsEntry("code", "auth-code");
        assertThat(info.providerUserId()).isEqualTo("sub-1");
    }
}
