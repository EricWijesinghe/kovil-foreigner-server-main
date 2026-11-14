package com.kovil.infra.oauth;

import com.kovil.infra.oauth.facebook.FacebookOAuthClient;
import com.kovil.infra.oauth.facebook.FacebookOAuthProperties;
import com.kovil.infra.oauth.google.GoogleOAuthClient;
import com.kovil.infra.oauth.google.GoogleOAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * authorize URL 은 **바로 브라우저에 넘어가는 문자열**이다 — 값 안의 공백이 인코딩되지 않으면
 * URL 자체가 깨져 로그인 화면이 뜨지 않는다.
 *
 * 실사고(2026-08-22): 구글 scope "openid email profile" 의 공백이 그대로 남아 클라이언트가
 * URL 을 거부했다. 서버 응답은 200 이라 로그만 봐서는 정상으로 보였다 — 그래서 여기서 고정한다.
 */
class AuthorizationUriEncodingTest {

    private static final String CHALLENGE = "EI7uJUdGyTH_Hx7H28FU3awPzco6gxxv10wChDLeuPg";
    private static final String STATE = "state-with-none";

    @Test
    @DisplayName("구글 authorize URL 은 공백 없이 파싱 가능한 URI 다")
    void googleAuthorizationUriIsParseable() {
        GoogleOAuthClient client = new GoogleOAuthClient(
                new GoogleOAuthProperties("client-id", "secret",
                        "https://wjfapi.example.test/oauth/google/callback",
                        "https://accounts.google.com/o/oauth2/v2/auth",
                        "https://oauth2.googleapis.com/token",
                        "https://openidconnect.googleapis.com/v1/userinfo"),
                null, null);

        String uri = client.buildAuthorizationUri(STATE, CHALLENGE);

        assertThat(uri).doesNotContain(" ");
        assertThat(uri).contains("scope=openid%20email%20profile");
        assertThatCode(() -> URI.create(uri)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("페이스북 authorize URL 도 같은 규칙을 지킨다")
    void facebookAuthorizationUriIsParseable() {
        FacebookOAuthClient client = new FacebookOAuthClient(
                new FacebookOAuthProperties("client-id", "secret",
                        "https://wjfapi.example.test/oauth/facebook/callback",
                        "https://www.facebook.com/v25.0/dialog/oauth",
                        "https://graph.facebook.com/v25.0/oauth/access_token",
                        "https://graph.facebook.com/v25.0/me"),
                null, null);

        String uri = client.buildAuthorizationUri(STATE, CHALLENGE);

        assertThat(uri).doesNotContain(" ");
        assertThatCode(() -> URI.create(uri)).doesNotThrowAnyException();
    }

    // ★ 구글이 code 를 challenge 에 묶게 하려면 인가 URL 에 실려야 한다.
    //   빠지면 훔친 code 를 남의 verifier 로 교환할 수 있다(실증 2026-09-01).
    @Test
    @DisplayName("구글 인가 URL 에 PKCE 파라미터가 실린다")
    void google_authorization_uri_carries_pkce() {
        GoogleOAuthClient client = new GoogleOAuthClient(
                new GoogleOAuthProperties("client-id", "secret",
                        "https://wjfapi.example.test/oauth/google/callback",
                        "https://accounts.google.com/o/oauth2/v2/auth",
                        "https://oauth2.googleapis.com/token",
                        "https://openidconnect.googleapis.com/v1/userinfo"),
                null, null);

        String uri = client.buildAuthorizationUri(STATE, CHALLENGE);

        assertThat(uri).contains("code_challenge=" + CHALLENGE);
        assertThat(uri).contains("code_challenge_method=S256");
    }
}
