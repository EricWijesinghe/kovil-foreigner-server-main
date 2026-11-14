package com.kovil.infra.oauth.apple;

import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Retryer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/*
 * Apple code 교환과 철회의 본문을 고정한다.
 * - 네이티브의 client_id 는 번들 ID 다. Services ID 를 쓰면 네이티브 code 가 invalid_client 로 거절된다.
 * - 네이티브는 redirect_uri 를 싣지 않는다. 인가 요청에 넣었을 때만 보내는 값이다.
 * - 웹은 반대로 Services ID 와 인가 요청에 넣은 redirect_uri 를 싣는다.
 * - client_secret 의 sub 와 철회의 client_id 는 그 토큰을 낸 쪽 값이다.
 * - 교환 응답의 sub 가 앱이 보낸 identityToken 의 sub 와 같아야 한다. 남의 code 를 섞으면 여기서 끊긴다.
 * 본문 모양은 HttpServer 스텁으로(TokenRequestBodyTest 와 같은 모양), 응답 해석은 목 Feign 으로 본다.
 */
class AppleNativeExchangeTest {

    private static final String BUNDLE_ID = "com.kovil.app";
    private static final String WEB_ID = "com.kovil.web";
    private static final String REDIRECT_URI = "https://api.kovil.invalid/oauth/apple/callback";
    private static final String SUB = "001234.abcdef.0001";

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            try (InputStream in = exchange.getRequestBody()) {
                capturedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            // revoke 는 200 에 본문이 없다. 오류 상태도 본문 없이 돌려준다.
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static AppleOAuthProperties properties() {
        return new AppleOAuthProperties(true, BUNDLE_ID, WEB_ID, "TEAMID1234", "KEYID12345", "pem",
                REDIRECT_URI, "https://appleid.apple.invalid/auth/authorize",
                "https://appleid.apple.invalid/auth/token", "https://appleid.apple.invalid/auth/keys",
                "https://appleid.apple.invalid/auth/revoke");
    }

    private static AppleClientSecretFactory secretFactory() {
        AppleClientSecretFactory factory = mock(AppleClientSecretFactory.class);
        given(factory.create(BUNDLE_ID)).willReturn("client-secret-jwt");
        given(factory.create(WEB_ID)).willReturn("web-client-secret-jwt");
        return factory;
    }

    private <T> T feign(Class<T> type, String url) {
        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new SpringEncoder(new NoConverters()))
                .decoder((response, t) -> null)
                // 스프링 Feign 의 기본값과 같다. feign 기본 Retryer 는 연결 실패를 다섯 번 다시 한다.
                .retryer(Retryer.NEVER_RETRY)
                .target(type, url);
    }

    private String stubUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private AppleTokenExchangerImpl exchanger(AppleTokenClient tokenClient, AppleRevokeClient revokeClient) {
        return new AppleTokenExchangerImpl(properties(), tokenClient, revokeClient, secretFactory(),
                JsonMapper.builder().build());
    }

    private static String idToken(String sub) {
        String payload = "{\"iss\":\"https://appleid.apple.com\",\"aud\":\"" + BUNDLE_ID + "\",\"sub\":\"" + sub + "\"}";
        return "eyJhbGciOiJSUzI1NiJ9."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    @Test
    @DisplayName("교환 본문은 번들 ID · grant_type · code · client_secret 이고 redirect_uri 는 없다")
    void exchange_form_carries_bundle_id_without_redirect_uri() {
        AppleTokenClient tokenClient = feign(AppleTokenClient.class, stubUrl("/auth/token"));

        // 디코더가 null 을 주므로 교환은 TOKEN_INVALID 로 끝난다. 이 시험이 보는 것은 나간 본문뿐이다.
        assertThatThrownBy(() -> exchanger(tokenClient, null).exchange("auth-code", SUB))
                .isInstanceOf(OAuthException.class);

        assertThat(capturedQuery.get()).isNull();
        assertThat(capturedBody.get())
                .contains("client_id=" + BUNDLE_ID)
                .contains("grant_type=authorization_code")
                .contains("code=auth-code")
                .contains("client_secret=client-secret-jwt")
                .doesNotContain("redirect_uri");
    }

    @Test
    @DisplayName("교환 응답의 refresh_token 을 돌려준다")
    void exchange_returns_refresh_token() {
        AppleTokenClient tokenClient = mock(AppleTokenClient.class);
        AtomicReference<Map<String, Object>> form = new AtomicReference<>();
        given(tokenClient.getToken(anyMap())).willAnswer(invocation -> {
            form.set(invocation.getArgument(0));
            return new AppleTokenResponse("at", "Bearer", idToken(SUB), 3600L, "r.refresh-token");
        });

        assertThat(exchanger(tokenClient, null).exchange("auth-code", SUB)).isEqualTo("r.refresh-token");
        assertThat(form.get()).containsEntry("client_id", BUNDLE_ID).doesNotContainKey("redirect_uri");
    }

    @Test
    @DisplayName("교환 응답의 sub 가 앱이 보낸 토큰의 sub 와 다르면 거절한다")
    void sub_mismatch_is_rejected() {
        AppleTokenClient tokenClient = mock(AppleTokenClient.class);
        given(tokenClient.getToken(anyMap()))
                .willReturn(new AppleTokenResponse("at", "Bearer", idToken("someone-else"), 3600L, "r.refresh"));

        assertThatThrownBy(() -> exchanger(tokenClient, null).exchange("auth-code", SUB))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_TOKEN_INVALID");
    }

    // 철회에 쓸 값이 없으면 탈퇴 때 Apple 쪽 연결을 끊을 수 없다. 가입을 받지 않는다.
    @Test
    @DisplayName("refresh_token 이 없는 응답은 거절한다")
    void missing_refresh_token_is_rejected() {
        AppleTokenClient tokenClient = mock(AppleTokenClient.class);
        given(tokenClient.getToken(anyMap()))
                .willReturn(new AppleTokenResponse("at", "Bearer", idToken(SUB), 3600L, null));

        assertThatThrownBy(() -> exchanger(tokenClient, null).exchange("auth-code", SUB))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_TOKEN_INVALID");
    }

    // invalid_grant(쓴 code · 5분 지난 code) 는 사용자가 다시 하면 되는 실패다.
    @Test
    @DisplayName("Apple 400 은 OAUTH_TOKEN_INVALID 다")
    void apple_400_is_token_invalid() {
        status.set(400);
        AppleTokenClient tokenClient = feign(AppleTokenClient.class, stubUrl("/auth/token"));

        assertThatThrownBy(() -> exchanger(tokenClient, null).exchange("used-code", SUB))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("Apple 5xx 는 OAUTH_PROVIDER_UNAVAILABLE 이다")
    void apple_5xx_is_unavailable() {
        status.set(503);
        AppleTokenClient tokenClient = feign(AppleTokenClient.class, stubUrl("/auth/token"));

        assertThatThrownBy(() -> exchanger(tokenClient, null).exchange("auth-code", SUB))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("연결이 안 되면 OAUTH_PROVIDER_UNAVAILABLE 이다")
    void connection_failure_is_unavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AppleTokenClient tokenClient = feign(AppleTokenClient.class, "http://127.0.0.1:" + closedPort + "/auth/token");

        assertThatThrownBy(() -> exchanger(tokenClient, null).exchange("auth-code", SUB))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("철회 본문은 token · token_type_hint=refresh_token · 번들 ID · client_secret 이다(200 빈 본문)")
    void revoke_form_body() {
        AppleRevokeClient revokeClient = feign(AppleRevokeClient.class, stubUrl("/auth/revoke"));

        exchanger(null, revokeClient).revoke("r.refresh-token", BUNDLE_ID);

        assertThat(capturedQuery.get()).isNull();
        assertThat(capturedBody.get())
                .contains("token=r.refresh-token")
                .contains("token_type_hint=refresh_token")
                .contains("client_id=" + BUNDLE_ID)
                .contains("client_secret=client-secret-jwt");
    }

    // 탈퇴는 이미 커밋됐다. 철회 실패가 예외로 올라가면 되돌릴 것도 없이 로그만 어지럽힌다.
    @Test
    @DisplayName("철회가 실패해도 던지지 않는다")
    void revoke_failure_is_swallowed() {
        status.set(400);
        AppleRevokeClient revokeClient = feign(AppleRevokeClient.class, stubUrl("/auth/revoke"));

        assertThatCode(() -> exchanger(null, revokeClient).revoke("r.refresh-token", BUNDLE_ID))
                .doesNotThrowAnyException();
    }

    // 웹 로그인으로 받은 토큰을 번들 ID 로 철회하면 Apple 이 invalid_client 로 거절한다.
    @Test
    @DisplayName("웹 토큰의 철회는 Services ID 와 그 secret 으로 보낸다")
    void revoke_uses_the_issuing_client_id() {
        AppleRevokeClient revokeClient = feign(AppleRevokeClient.class, stubUrl("/auth/revoke"));

        exchanger(null, revokeClient).revoke("r.web-refresh", WEB_ID);

        assertThat(capturedBody.get())
                .contains("client_id=" + WEB_ID)
                .contains("client_secret=web-client-secret-jwt")
                .doesNotContain("client_id=" + BUNDLE_ID);
    }

    @Test
    @DisplayName("웹 교환 본문은 Services ID · redirect_uri · 그 secret 이다")
    void web_exchange_form_carries_services_id_and_redirect_uri() {
        AppleTokenClient tokenClient = feign(AppleTokenClient.class, stubUrl("/auth/token"));

        // 디코더가 null 을 주므로 교환은 TOKEN_INVALID 로 끝난다. 이 시험이 보는 것은 나간 본문뿐이다.
        assertThatThrownBy(() -> exchanger(tokenClient, null).exchangeWeb("web-code"))
                .isInstanceOf(OAuthException.class);

        assertThat(capturedBody.get())
                .contains("client_id=" + WEB_ID)
                .contains("client_secret=web-client-secret-jwt")
                .contains("grant_type=authorization_code")
                .contains("code=web-code")
                .contains("redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("웹 교환은 응답을 그대로 돌려주고, refresh_token 이 없으면 거절한다")
    void web_exchange_returns_tokens() {
        AppleTokenClient tokenClient = mock(AppleTokenClient.class);
        given(tokenClient.getToken(anyMap()))
                .willReturn(new AppleTokenResponse("at", "Bearer", idToken(SUB), 3600L, "r.web-refresh"))
                .willReturn(new AppleTokenResponse("at", "Bearer", idToken(SUB), 3600L, null));
        AppleTokenExchangerImpl exchanger = exchanger(tokenClient, null);

        assertThat(exchanger.exchangeWeb("web-code").refreshToken()).isEqualTo("r.web-refresh");
        assertThatThrownBy(() -> exchanger.exchangeWeb("web-code"))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("웹 교환도 Apple 5xx 는 OAUTH_PROVIDER_UNAVAILABLE 이다")
    void web_exchange_5xx_is_unavailable() {
        status.set(503);
        AppleTokenClient tokenClient = feign(AppleTokenClient.class, stubUrl("/auth/token"));

        assertThatThrownBy(() -> exchanger(tokenClient, null).exchangeWeb("web-code"))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_PROVIDER_UNAVAILABLE");
    }

    // form 본문은 SpringFormEncoder 가 처리하므로 메시지 컨버터가 필요 없다.
    private static final class NoConverters implements ObjectProvider<FeignHttpMessageConverters> {
        @Override
        public FeignHttpMessageConverters getObject(Object... args) throws BeansException {
            return null;
        }

        @Override
        public FeignHttpMessageConverters getIfAvailable() throws BeansException {
            return null;
        }

        @Override
        public FeignHttpMessageConverters getIfUnique() throws BeansException {
            return null;
        }

        @Override
        public FeignHttpMessageConverters getObject() throws BeansException {
            return null;
        }
    }
}
