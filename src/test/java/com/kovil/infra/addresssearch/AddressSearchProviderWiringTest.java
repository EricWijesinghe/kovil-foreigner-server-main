package com.kovil.infra.addresssearch;

import com.kovil.infra.geocoding.GeocodingClient;
import com.kovil.infra.juso.addresssearch.JusoAddressSearchClient;
import com.kovil.infra.juso.addresssearch.JusoFeignClient;
import com.kovil.infra.juso.addresssearch.JusoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/*
 * ★ 기본값은 지금처럼 좌표 벤더로 검색이다 — 이 판을 배포만 해서는 검색이 바뀌지 않아야 한다.
 *   juso 로 바꾸면 후보에 좌표가 비어 오고, 좌표 없는 후보를 못 고르는 옛 파트너스 앱이 매물 주소를 못 넣는다.
 */
class AddressSearchProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(GeocodingClient.class, () -> mock(GeocodingClient.class))
            .withBean(JusoFeignClient.class, () -> mock(JusoFeignClient.class))
            .withBean(JusoProperties.class, () -> new JusoProperties("https://juso.test", "k"))
            .withUserConfiguration(GeocodingAddressSearchClient.class, JusoAddressSearchClient.class);

    @Test
    @DisplayName("설정이 없으면 좌표 벤더로 검색한다(지금과 같다)")
    void default_is_geocoding() {
        runner.run(context -> assertThat(context.getBean(AddressSearchClient.class))
                .isInstanceOf(GeocodingAddressSearchClient.class));
    }

    @Test
    @DisplayName("address-search.provider=juso 면 행정안전부 하나만 뜬다")
    void juso_when_configured() {
        runner.withPropertyValues("address-search.provider=juso")
                .run(context -> assertThat(context.getBean(AddressSearchClient.class))
                        .isInstanceOf(JusoAddressSearchClient.class));
    }

    @Test
    @DisplayName("juso 인데 승인키가 비면 컨텍스트가 뜨지 않는다(배포 헬스체크 실패 → 이전 이미지)")
    void juso_without_key_fails_startup() {
        new ApplicationContextRunner()
                .withBean(GeocodingClient.class, () -> mock(GeocodingClient.class))
                .withBean(JusoFeignClient.class, () -> mock(JusoFeignClient.class))
                .withBean(JusoProperties.class, () -> new JusoProperties("https://juso.test", ""))
                .withUserConfiguration(GeocodingAddressSearchClient.class, JusoAddressSearchClient.class)
                .withPropertyValues("address-search.provider=juso")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("address-search.provider=geocoding 도 좌표 벤더")
    void explicit_geocoding() {
        runner.withPropertyValues("address-search.provider=geocoding")
                .run(context -> assertThat(context.getBean(AddressSearchClient.class))
                        .isInstanceOf(GeocodingAddressSearchClient.class));
    }
}
