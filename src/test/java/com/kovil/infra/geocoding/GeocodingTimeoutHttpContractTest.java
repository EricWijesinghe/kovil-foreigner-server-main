package com.kovil.infra.geocoding;

import com.kovil.infra.geocoding.exception.GeocodingErrorCode;
import com.kovil.infra.geocoding.exception.GeocodingException;
import com.kovil.infra.kakao.geocoding.KakaoGeocodingClientImpl;
import com.kovil.infra.kakao.geocoding.KakaoGeocodingFeignClient;
import com.kovil.infra.naver.geocoding.NaverGeocodingClientImpl;
import com.kovil.infra.naver.geocoding.NaverGeocodingFeignClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.cloud.openfeign.FeignClientProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 주소 검색 Feign 시한 — 진짜 Feign 으로 본다. 시한은 application.yaml 의 클라이언트 이름 키에 걸려 있어
 * 이름이 어긋나면 기본(연결 10초 · 읽기 60초)으로 돌아가는데, 목 Feign 테스트는 그걸 못 본다.
 * 읽기 시한은 GEOCODING_READ_TIMEOUT_MS 로 300ms 까지 줄여 빨리 끝낸다 — 그 자리표시자가 실제로 읽히는지도 함께 본다.
 * 카카오는 2초 뒤에 답하는 스텁(읽기 시한), 네이버는 닫힌 포트(연결 거절)다.
 */
@SpringBootTest
class GeocodingTimeoutHttpContractTest {

    private static final String BODY = "{\"documents\":[],\"meta\":{\"total_count\":0,\"pageable_count\":0,\"is_end\":true}}";
    private static final HttpServer SLOW = startSlowStub();
    private static final int CLOSED_PORT = closedPort();

    @Autowired KakaoGeocodingFeignClient kakaoFeignClient;
    @Autowired FeignClientProperties feignClientProperties;
    @Autowired NaverGeocodingFeignClient naverFeignClient;

    @DynamicPropertySource
    static void vendors(DynamicPropertyRegistry registry) {
        registry.add("kakao.geocoding.base-url", () -> "http://127.0.0.1:" + SLOW.getAddress().getPort());
        registry.add("kakao.geocoding.rest-api-key", () -> "geo-key");
        registry.add("naver.geocoding.base-url", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("naver.geocoding.client-id", () -> "id");
        registry.add("naver.geocoding.client-secret", () -> "secret");
        registry.add("GEOCODING_READ_TIMEOUT_MS", () -> "300");
    }

    @AfterAll
    static void stop() {
        SLOW.stop(0);
    }

    @Test
    @DisplayName("카카오가 읽기 시한 안에 답하지 않으면 곧바로 GEOCODING_PROVIDER_ERROR — 60초를 붙잡지 않는다")
    void kakao_read_timeout_is_provider_error() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> new KakaoGeocodingClientImpl(kakaoFeignClient).search("테스트로 1", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_500);
    }

    @Test
    @DisplayName("네이버에 연결이 안 되면 GEOCODING_PROVIDER_ERROR — 500 이 아니다")
    void naver_connection_refused_is_provider_error() {
        assertThatThrownBy(() -> new NaverGeocodingClientImpl(naverFeignClient).search("테스트로 1", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }

    // 키 이름이 @FeignClient(name) 과 어긋나면 그 클라이언트는 기본(연결 10초 · 읽기 60초)으로 돌아간다 — 두 벤더 · 두 시한 모두 본다.
    @Test
    @DisplayName("두 벤더 모두 클라이언트 이름 키로 시한이 걸린다 — 연결 3초(기본값) · 읽기는 환경변수 값")
    void both_vendors_bind_their_timeouts() {
        for (String client : List.of("kakaoGeocodingClient", "naverGeocodingClient")) {
            var config = feignClientProperties.getConfig().get(client);
            assertThat(config).as(client).isNotNull();
            assertThat(config.getConnectTimeout()).as(client).isEqualTo(3_000);
            assertThat(config.getReadTimeout()).as(client).isEqualTo(300);
        }
    }

    private static HttpServer startSlowStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(2_000);
                    byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // 클라이언트가 시한으로 끊고 나간 뒤라 쓰기가 실패한다 — 정상.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 빈 포트를 하나 잡았다 바로 닫는다 — 그 포트로의 연결은 거절된다.
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
