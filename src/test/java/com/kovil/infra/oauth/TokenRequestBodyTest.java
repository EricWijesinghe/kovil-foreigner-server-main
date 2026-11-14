package com.kovil.infra.oauth;

import com.kovil.infra.oauth.google.GoogleTokenClient;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 교환 요청이 **본문으로** 나가는지 고정한다.
 *
 * 실사고(2026-08-22): @RequestParam 으로 선언된 탓에 Feign 이 값을 쿼리스트링에 붙이고 본문 없이
 * POST 했다. 구글은 Content-Length 를 요구하므로 411 Length Required 로 거절했고, 동의를 마친
 * 사용자가 마지막 단계에서 로그인에 실패했다. 선례인 카카오가 쿼리스트링을 받아준 탓에
 * 같은 모양이 그대로 복사돼 있었다 — 프로바이더마다 다르다는 것을 여기서 못박는다.
 */
class TokenRequestBodyTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            try (InputStream in = exchange.getRequestBody()) {
                capturedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(204, -1);   // 이 테스트가 보는 것은 요청뿐이다
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** form 본문은 SpringFormEncoder 가 처리하므로 메시지 컨버터가 필요 없다. */
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

    @Test
    @DisplayName("구글 토큰 교환은 쿼리스트링이 아니라 form 본문으로 나간다")
    void googleTokenRequestCarriesFormBody() {
        GoogleTokenClient client = Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new SpringEncoder(new NoConverters()))
                .decoder((response, type) -> null)
                .target(GoogleTokenClient.class,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/token");

        client.getToken(Map.of(
                "grant_type", "authorization_code",
                "client_id", "cid",
                "client_secret", "secret",
                "redirect_uri", "https://wjfapi.example.test/oauth/google/callback",
                "code", "auth-code"));

        // 본문이 비면 Content-Length 가 0/부재라 구글이 411 로 끊는다 — 이 단언이 그 사고를 막는다.
        assertThat(capturedBody.get()).isNotBlank();
        assertThat(capturedBody.get())
                .contains("grant_type=authorization_code")
                .contains("code=auth-code");
        // client_secret 이 URL 에 실리면 예외 메시지·액세스 로그로 새어나간다.
        assertThat(capturedQuery.get()).isNull();
        assertThat(capturedBody.get()).contains("client_secret=secret");
    }
}
