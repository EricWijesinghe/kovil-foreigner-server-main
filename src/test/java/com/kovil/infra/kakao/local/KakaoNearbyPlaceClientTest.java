package com.kovil.infra.kakao.local;

import com.kovil.infra.kakao.geocoding.KakaoGeocodingFeignConfig;
import com.kovil.infra.kakao.local.dto.response.KakaoCategoryResponse;
import com.kovil.infra.nearby.NearbySearchResult;
import com.kovil.infra.nearby.NearbyVendorException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/*
 * 카카오 카테고리 검색 → 포트 값 변환 격자.
 * ★ 카카오는 x = 경도, y = 위도다. 뒤집으면 서울 매물의 주변시설을 바다 한가운데서 찾는다 — 그래도 200 이 온다.
 * ★ "가까운 순 3곳"은 이 어댑터가 size 를 그대로 넘기는 데 기댄다(서비스는 받은 만큼 싣는다).
 */
@ExtendWith(MockitoExtension.class)
class KakaoNearbyPlaceClientTest {

    private static final BigDecimal LAT = new BigDecimal("37.5552000");
    private static final BigDecimal LNG = new BigDecimal("126.9368000");

    @Mock KakaoLocalFeignClient feignClient;

    private KakaoNearbyPlaceClient client() {
        return new KakaoNearbyPlaceClient(feignClient);
    }

    @Test
    @DisplayName("x 에 경도 · y 에 위도 · 반경 · size 그대로 · 가까운 순(distance)으로 묻고, 이름 · 좌표 · 전체 수를 옮긴다")
    void maps_request_and_response() {
        given(feignClient.searchCategory("CS2", "126.9368000", "37.5552000", 500, 3, "distance"))
                .willReturn(new KakaoCategoryResponse(
                        List.of(new KakaoCategoryResponse.Document("테스트편의점", "126.9371", "37.5556")),
                        new KakaoCategoryResponse.Meta(7L)));

        NearbySearchResult result = client().search("CS2", LAT, LNG, 500, 3);

        assertThat(result.totalCount()).isEqualTo(7L);
        assertThat(result.places()).hasSize(1);
        NearbySearchResult.Place place = result.places().get(0);
        assertThat(place.name()).isEqualTo("테스트편의점");
        assertThat(place.lat()).isEqualByComparingTo("37.5556");
        assertThat(place.lng()).isEqualByComparingTo("126.9371");
    }

    @Test
    @DisplayName("size 는 카카오 상한 15 로 눌린다 — 넘기면 벤더가 400 을 준다")
    void size_is_clamped() {
        given(feignClient.searchCategory("CE7", "126.9368000", "37.5552000", 500, 15, "distance"))
                .willReturn(new KakaoCategoryResponse(List.of(), new KakaoCategoryResponse.Meta(0L)));

        assertThat(client().search("CE7", LAT, LNG, 500, 40).places()).isEmpty();
    }

    @Test
    @DisplayName("전체 수(meta)가 없으면 받은 장소 수로 친다")
    void missing_meta_counts_documents() {
        given(feignClient.searchCategory("BK9", "126.9368000", "37.5552000", 500, 3, "distance"))
                .willReturn(new KakaoCategoryResponse(List.of(
                        new KakaoCategoryResponse.Document("은행 A", "126.937", "37.556"),
                        new KakaoCategoryResponse.Document("은행 B", "126.938", "37.557")), null));

        assertThat(client().search("BK9", LAT, LNG, 500, 3).totalCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("본문이 비면 벤더 실패다 — 0곳으로 꾸미지 않는다")
    void empty_body_is_vendor_failure() {
        given(feignClient.searchCategory("PO3", "126.9368000", "37.5552000", 500, 3, "distance")).willReturn(null);

        assertThatThrownBy(() -> client().search("PO3", LAT, LNG, 500, 3))
                .isInstanceOf(NearbyVendorException.class);
    }

    @Test
    @DisplayName("documents 가 없어도 벤더 실패다")
    void missing_documents_is_vendor_failure() {
        given(feignClient.searchCategory("MT1", "126.9368000", "37.5552000", 500, 3, "distance"))
                .willReturn(new KakaoCategoryResponse(null, new KakaoCategoryResponse.Meta(3L)));

        assertThatThrownBy(() -> client().search("MT1", LAT, LNG, 500, 3))
                .isInstanceOf(NearbyVendorException.class);
    }

    @ParameterizedTest(name = "{0} → 벤더 실패")
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    @DisplayName("카카오 오류 응답은 어떤 상태든 벤더 실패 하나로 바뀐다 — 상태는 메시지(로그)에만 남는다")
    void error_statuses_become_vendor_failure(int status) {
        Response response = Response.builder()
                .status(status)
                .reason("x")
                .headers(Map.of())
                .request(Request.create(Request.HttpMethod.GET, "https://dapi.kakao.com/v2/local/search/category.json",
                        Map.of(), null, StandardCharsets.UTF_8, null))
                .build();

        Exception decoded = new KakaoLocalErrorDecoder().decode("searchCategory", response);

        assertThat(decoded).isInstanceOf(NearbyVendorException.class).hasMessageContaining(String.valueOf(status));
    }

    /*
     * ★ 타임아웃은 yaml 의 spring.cloud.openfeign.client.config.<클라이언트 이름> 에 건다.
     *   @FeignClient 의 name 을 바꾸고 yaml 을 그대로 두면 **타임아웃이 조용히 기본값(연결 10초 · 읽기 60초)으로 돌아간다** —
     *   컴파일도 되고 다른 테스트도 다 통과한다(10-12 적용 때 실제로 어긋났다).
     */
    @Test
    @DisplayName("Feign 이름과 yaml 타임아웃 키가 짝이다 — 연결 1초 · 읽기 2초")
    void timeout_config_matches_feign_name() {
        String name = KakaoLocalFeignClient.class.getAnnotation(FeignClient.class).name();
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        String prefix = "spring.cloud.openfeign.client.config." + name + ".";
        assertThat(properties.getProperty(prefix + "connect-timeout")).isEqualTo("1000");
        assertThat(properties.getProperty(prefix + "read-timeout")).isEqualTo("2000");
    }

    /*
     * ★ 두 설정은 @FeignClient(configuration = …) 로만 걸려야 한다. 컴포넌트 스캔에 잡히면 부모 컨텍스트의 빈이 되어
     *   **모든 Feign 클라이언트**(구글 · 애플 · 네이버 · OpenAI …)의 요청에 'KakaoAK 키' 헤더가 실린다.
     *   @Configuration 도 @Component 라서 합친 판정(AnnotatedElementUtils)으로 함께 막는다.
     */
    @Test
    @DisplayName("카카오 Feign 설정 클래스에는 @Configuration · @Component 가 없다 — 붙으면 카카오 키가 다른 벤더 요청으로 샌다")
    void kakao_feign_configs_are_not_scanned() {
        for (Class<?> config : List.of(KakaoLocalFeignConfig.class, KakaoGeocodingFeignConfig.class)) {
            assertThat(config.isAnnotationPresent(Configuration.class)).as(config.getSimpleName()).isFalse();
            assertThat(AnnotatedElementUtils.hasAnnotation(config, Component.class)).as(config.getSimpleName()).isFalse();
        }
    }
}
