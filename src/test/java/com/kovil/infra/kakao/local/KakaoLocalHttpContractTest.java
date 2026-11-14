package com.kovil.infra.kakao.local;

import com.kovil.infra.kakao.geocoding.KakaoGeocodingFeignClient;
import com.kovil.infra.nearby.NearbyPlaceClient;
import com.kovil.infra.nearby.NearbySearchResult;
import com.kovil.infra.nearby.NearbyVendorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 카카오 로컬 HTTP 계약 — 목 Feign 으로는 안 보이는 쿼리 이름 · 인증 헤더 · JSON 칸 이름을 진짜 Feign 으로 태운다.
 * ★ 운영 기본은 fake 이고 서비스는 벤더 예외를 전부 200 UNAVAILABLE 로 삼킨다 — 여기가 어긋나도 다른 테스트는 다 초록이다.
 * ★ 카카오는 x = 경도, y = 위도다. 뒤집어 보내도 카카오는 200 을 준다(바다 한가운데라 0곳).
 */
@SpringBootTest
class KakaoLocalHttpContractTest {

    private static final String CATEGORY_PATH = "/v2/local/search/category.json";
    private static final String ADDRESS_PATH = "/v2/local/search/address.json";
    private static final BigDecimal LAT = new BigDecimal("37.5552000");
    private static final BigDecimal LNG = new BigDecimal("126.9368000");

    // 카카오 문서의 응답 모양 그대로 — 우리가 읽지 않는 칸(id · distance · pageable_count …)도 섞어 둔다.
    private static final String CATEGORY_BODY = """
            {"documents":[{"id":"1","place_name":"GS25 신촌점","distance":"120","category_group_code":"CS2","x":"126.9371","y":"37.5556"}],"meta":{"total_count":7,"pageable_count":7,"is_end":true}}""";
    private static final String ADDRESS_BODY = """
            {"documents":[],"meta":{"total_count":0,"pageable_count":0,"is_end":true}}""";

    private static final List<Seen> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile int categoryStatus = 200;
    // 컨텍스트가 뜨기 전에 포트가 있어야 @DynamicPropertySource 가 주소를 넘길 수 있다 — 그래서 클래스 로딩 때 띄운다.
    private static final HttpServer SERVER = startStub();

    @Autowired NearbyPlaceClient nearbyPlaceClient;
    @Autowired KakaoGeocodingFeignClient geocodingFeignClient;

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry registry) {
        String baseUrl = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("nearby.provider", () -> "kakao");
        registry.add("kakao.local.base-url", () -> baseUrl);
        registry.add("kakao.local.rest-api-key", () -> "test-key");
        // 주소 검색은 다른 키로 둔다 — 한쪽 설정의 인터셉터가 다른 쪽 요청에 섞이면 헤더 값으로 드러난다.
        registry.add("kakao.geocoding.base-url", () -> baseUrl);
        registry.add("kakao.geocoding.rest-api-key", () -> "geo-key");
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        categoryStatus = 200;
    }

    @AfterAll
    static void stopStub() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("요청: 카테고리 경로 · 쿼리 이름과 값(x=경도 · y=위도 · 반경 · size · 가까운 순) · 'KakaoAK 키' 헤더 하나")
    void request_matches_kakao_contract() {
        assertThat(nearbyPlaceClient).isInstanceOf(KakaoNearbyPlaceClient.class);

        nearbyPlaceClient.search("CS2", LAT, LNG, 500, 3);

        assertThat(REQUESTS).hasSize(1);
        Seen seen = REQUESTS.get(0);
        assertThat(seen.method()).isEqualTo("GET");
        assertThat(seen.path()).isEqualTo(CATEGORY_PATH);
        assertThat(queryOf(seen.rawQuery())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "category_group_code", "CS2",
                "x", "126.9368000",
                "y", "37.5552000",
                "radius", "500",
                "size", "3",
                "sort", "distance"));
        // 값이 둘이면 다른 설정의 인터셉터가 섞인 것이다.
        assertThat(seen.authorization()).containsExactly("KakaoAK test-key");
    }

    @Test
    @DisplayName("응답: 실제 문서 모양(읽지 않는 칸 포함)에서 이름 · y=위도 · x=경도 · total_count 를 읽는다")
    void response_maps_kakao_fields() {
        NearbySearchResult result = nearbyPlaceClient.search("CS2", LAT, LNG, 500, 3);

        assertThat(result.totalCount()).isEqualTo(7L);
        assertThat(result.places()).hasSize(1);
        NearbySearchResult.Place place = result.places().get(0);
        assertThat(place.name()).isEqualTo("GS25 신촌점");
        assertThat(place.lat()).isEqualByComparingTo("37.5556");
        assertThat(place.lng()).isEqualByComparingTo("126.9371");
    }

    @Test
    @DisplayName("429(쿼터 초과)면 벤더 실패 예외다 — 0곳으로 꾸미지 않는다")
    void too_many_requests_is_vendor_failure() {
        categoryStatus = 429;

        assertThatThrownBy(() -> nearbyPlaceClient.search("CS2", LAT, LNG, 500, 3))
                .isInstanceOf(NearbyVendorException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("주소 검색 요청에는 주소 검색 키 하나만 실린다 — 주변시설 설정이 전역으로 새지 않는다")
    void geocoding_request_carries_only_its_own_key() {
        geocodingFeignClient.searchAddress("테스트로 1", 1, 10);

        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.get(0).path()).isEqualTo(ADDRESS_PATH);
        assertThat(REQUESTS.get(0).authorization()).containsExactly("KakaoAK geo-key");
    }

    // 같은 이름이 두 번 오면 toMap 이 터진다 — 중복 쿼리도 계약 위반으로 잡는다.
    private static Map<String, String> queryOf(String rawQuery) {
        assertThat(rawQuery).isNotNull();
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : ""));
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", KakaoLocalHttpContractTest::answer);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void answer(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            List<String> authorization = exchange.getRequestHeaders().get("Authorization");
            REQUESTS.add(new Seen(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    authorization == null ? List.of() : List.copyOf(authorization)));

            int status;
            byte[] body;
            if (CATEGORY_PATH.equals(path)) {
                status = categoryStatus;
                body = status == 200 ? CATEGORY_BODY.getBytes(StandardCharsets.UTF_8) : new byte[0];
            } else if (ADDRESS_PATH.equals(path)) {
                status = 200;
                body = ADDRESS_BODY.getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = new byte[0];
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private record Seen(String method, String path, String rawQuery, List<String> authorization) {
    }
}
