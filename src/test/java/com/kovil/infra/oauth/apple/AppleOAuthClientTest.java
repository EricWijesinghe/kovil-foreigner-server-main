package com.kovil.infra.oauth.apple;

import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.oauth.OAuthProviderProperties;
import com.kovil.infra.oauth.PkceValidator;
import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/*
 * 웹 Apple 로그인. Apple 이 code_challenge 를 받지 않으므로 code 와 verifier 의 결속은 nonce 로 건다.
 * challenge 가 인가 URL 의 nonce 로 나가고, 교환 응답 id_token 의 nonce 가 verifier 에서 계산한 challenge 와
 * 같아야 통과한다. 둘 중 하나라도 빠지면 훔친 code 를 공격자 자신의 state · verifier 로 바꿔 낼 수 있다.
 */
class AppleOAuthClientTest {

    private static final String WEB_ID = "com.kovil.web";
    private static final String REDIRECT_URI = "https://api.kovil.invalid/oauth/apple/callback";
    // RFC 7636 짝(OAuthAuthServiceTest 와 같은 값)
    private static final String VERIFIER = "wonjoomin-foreigner-pkce-test-verifier-0123456789";
    private static final String CHALLENGE = "EI7uJUdGyTH_Hx7H28FU3awPzco6gxxv10wChDLeuPg";

    private final Encryptor encryptor = mock(Encryptor.class);
    private final AppleTokenExchanger exchanger = mock(AppleTokenExchanger.class);
    private final AppleIdTokenVerifier verifier = mock(AppleIdTokenVerifier.class);
    private final AppleCallbackNameStore nameStore = mock(AppleCallbackNameStore.class);

    private static AppleOAuthProperties properties(String webClientId, String redirectUri) {
        return new AppleOAuthProperties(false, "com.kovil.app", webClientId, "TEAMID1234", "KEYID12345", "pem",
                redirectUri, "https://appleid.apple.com/auth/authorize",
                "https://appleid.apple.invalid/auth/token", "https://appleid.apple.invalid/auth/keys",
                "https://appleid.apple.invalid/auth/revoke");
    }

    private AppleOAuthClient client(AppleOAuthProperties properties, List<AuthType> enabled) {
        return new AppleOAuthClient(encryptor, new PkceValidator(), properties, exchanger, verifier, nameStore,
                new OAuthProviderProperties(enabled));
    }

    private AppleOAuthClient client() {
        return client(properties(WEB_ID, REDIRECT_URI), List.of(AuthType.GOOGLE, AuthType.APPLE));
    }

    @Test
    @DisplayName("인가 URL 은 Services ID · Return URL · form_post · state 에 challenge 를 nonce 로 싣는다")
    void authorization_uri_binds_challenge_as_nonce() {
        String uri = client().buildAuthorizationUri("state-1", CHALLENGE);

        Map<String, List<String>> query = UriComponentsBuilder.fromUriString(uri).build().getQueryParams();
        assertThat(uri).startsWith("https://appleid.apple.com/auth/authorize?");
        assertThat(query.get("client_id")).containsExactly(WEB_ID);
        assertThat(query.get("response_mode")).containsExactly("form_post");
        assertThat(query.get("response_type")).containsExactly("code");
        assertThat(query.get("state")).containsExactly("state-1");
        assertThat(query.get("nonce")).containsExactly(CHALLENGE);
        assertThat(uri).contains("scope=name%20email").doesNotContain(" ");
        assertThat(query.get("redirect_uri")).containsExactly(REDIRECT_URI);
        assertThatCode(() -> URI.create(uri)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("교환한 id_token 을 verifier 의 challenge · Services ID 로 검증하고 토큰 암호문 · client_id 를 싣는다")
    void user_info_is_verified_against_the_verifier() {
        given(exchanger.exchangeWeb("code-1"))
                .willReturn(new AppleTokenResponse("at", "Bearer", "id.token.jwt", 3600L, "r.web-refresh"));
        given(verifier.verify("id.token.jwt", CHALLENGE, WEB_ID))
                .willReturn(new AppleIdTokenClaims("001234.abc", "x@privaterelay.appleid.com", true, true));
        given(nameStore.consume("state-1")).willReturn("Van A NGUYEN");
        given(encryptor.encrypt("r.web-refresh")).willReturn("v1:web");

        OAuthUserInfo info = client().getUserInfo("code-1", "state-1", null, VERIFIER);

        assertThat(info.providerUserId()).isEqualTo("001234.abc");
        assertThat(info.email()).isEqualTo("x@privaterelay.appleid.com");
        assertThat(info.name()).isEqualTo("Van A NGUYEN");
        assertThat(info.encryptedRefreshToken()).isEqualTo("v1:web");
        assertThat(info.refreshTokenClientId()).isEqualTo(WEB_ID);
    }

    // 훔친 code 는 피해자의 challenge 를 nonce 로 품고 있다. 공격자 자신의 verifier 로 바꿔 내면 nonce 가 어긋난다.
    @Test
    @DisplayName("id_token 검증이 실패하면 로그인되지 않고 토큰도 저장하지 않는다")
    void verification_failure_stops_login() {
        given(exchanger.exchangeWeb("stolen-code"))
                .willReturn(new AppleTokenResponse("at", "Bearer", "victim.id.token", 3600L, "r.victim"));
        given(verifier.verify("victim.id.token", CHALLENGE, WEB_ID))
                .willThrow(new OAuthException(AuthErrorCode.OAUTH_TOKEN_INVALID));

        assertThatThrownBy(() -> client().getUserInfo("stolen-code", "attacker-state", null, VERIFIER))
                .isInstanceOf(OAuthException.class);
        then(encryptor).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예전 클라이언트가 user 원문을 보내면 그 이름을 먼저 쓴다")
    void client_payload_wins_over_stored_name() {
        given(exchanger.exchangeWeb("code-1"))
                .willReturn(new AppleTokenResponse("at", "Bearer", "id.token.jwt", 3600L, "r.web-refresh"));
        given(verifier.verify("id.token.jwt", CHALLENGE, WEB_ID))
                .willReturn(new AppleIdTokenClaims("001234.abc", "a@b.com", true, false));
        given(nameStore.parseName("{\"name\":{\"firstName\":\"Lan\"}}")).willReturn("Lan");
        given(nameStore.consume("state-1")).willReturn("Stored Name");

        assertThat(client().getUserInfo("code-1", "state-1", "{\"name\":{\"firstName\":\"Lan\"}}", VERIFIER).name())
                .isEqualTo("Lan");
    }

    // 목록에 APPLE 을 넣고 값을 빠뜨리면 웹 Apple 로그인이 전부 실패한다. 배포 헬스체크에서 걸려야 한다.
    @Test
    @DisplayName("웹을 켰는데 Services ID · Return URL 이 비면 부팅을 멈춘다 · 꺼져 있으면 비어도 된다")
    void enabled_without_web_values_fails_boot() {
        assertThatThrownBy(() -> client(properties("", REDIRECT_URI), List.of(AuthType.APPLE)).requireCredentials())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client(properties(WEB_ID, null), List.of(AuthType.APPLE)).requireCredentials())
                .isInstanceOf(IllegalStateException.class);

        client(properties(null, null), List.of(AuthType.GOOGLE)).requireCredentials();
    }
}
