package com.kovil.infra.juso.addresssearch;

import com.kovil.infra.addresssearch.exception.AddressSearchErrorCode;
import com.kovil.infra.addresssearch.exception.AddressSearchException;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.juso.addresssearch.dto.JusoSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/*
 * 행정안전부는 오류도 HTTP 200 에 errorCode 로 준다. 갈래는 셋 — "0" 이면 후보, 검색어 탓 오류면 빈 목록,
 * 그 밖(키 만료 · 미승인 · 장애 · 빈 본문 · 연결 실패)은 503 ADDRESS_SEARCH_UNAVAILABLE.
 */
class JusoAddressSearchClientTest {

    private JusoFeignClient feignClient;
    private JusoAddressSearchClient client;

    @BeforeEach
    void setUp() {
        feignClient = mock(JusoFeignClient.class);
        client = new JusoAddressSearchClient(feignClient, new JusoProperties("https://juso.test", "test-key"));
    }

    private static JusoSearchResponse ok(String total, List<JusoSearchResponse.Juso> juso) {
        return new JusoSearchResponse(new JusoSearchResponse.Results(
                new JusoSearchResponse.Common(total, "0", "정상"), juso));
    }

    private static JusoSearchResponse error(String code) {
        return new JusoSearchResponse(new JusoSearchResponse.Results(
                new JusoSearchResponse.Common("0", code, "오류"), null));
    }

    private static JusoSearchResponse.Juso jeongja() {
        return new JusoSearchResponse.Juso("경기도 성남시 분당구 불정로 6 (정자동)", "경기도 성남시 분당구 정자동 178-1",
                "6 Buljeong-ro, Bundang-gu, Seongnam-si, Gyeonggi-do", "13561", "4113510300",
                "경기도", "성남시 분당구", "정자동");
    }

    @Test
    @DisplayName("정상 — 후보를 옮겨 담고 좌표는 비운다 · 법정동 코드와 읍면동 이름을 싣는다")
    void maps_candidates_without_coordinates() {
        given(feignClient.search(any())).willReturn(ok("31", List.of(jeongja())));

        GeocodedPage page = client.search("불정로 6", 2, 10);

        assertThat(page.totalCount()).isEqualTo(31);
        assertThat(page.page()).isEqualTo(2);
        GeocodedAddress address = page.addresses().get(0);
        assertThat(address.roadAddress()).isEqualTo("경기도 성남시 분당구 불정로 6 (정자동)");
        assertThat(address.jibunAddress()).isEqualTo("경기도 성남시 분당구 정자동 178-1");
        assertThat(address.englishAddress()).startsWith("6 Buljeong-ro");
        assertThat(address.postalCode()).isEqualTo("13561");
        assertThat(address.sido()).isEqualTo("경기도");
        assertThat(address.sigugun()).isEqualTo("성남시 분당구");
        assertThat(address.longitude()).isNull();
        assertThat(address.latitude()).isNull();
        assertThat(address.legalDongCode()).isEqualTo("4113510300");
        assertThat(address.dongName()).isEqualTo("정자동");
    }

    // 키는 폼 본문으로만 — GET 쿼리에 실으면 Feign 예외 메시지(URL)에 키가 남는다. 페이지는 벤더 1기반 그대로.
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("요청 — 승인키 · 페이지 · 개수 · 거른 검색어 · json 을 폼에 싣는다")
    void sends_form() {
        given(feignClient.search(any())).willReturn(ok("0", List.of()));
        ArgumentCaptor<Map<String, ?>> captor = ArgumentCaptor.forClass(Map.class);

        client.search("불정로'; 6", 3, 20);

        verify(feignClient).search(captor.capture());
        Map<String, Object> form = (Map<String, Object>) captor.getValue();
        assertThat(form).containsEntry("confmKey", "test-key")
                .containsEntry("currentPage", 3)
                .containsEntry("countPerPage", 20)
                .containsEntry("keyword", "불정로 6")
                .containsEntry("resultType", "json");
    }

    @Test
    @DisplayName("걸러서 보낼 게 없으면(한 글자 · SQL 낱말) 벤더를 부르지 않고 빈 목록")
    void unusable_keyword_skips_vendor() {
        assertThat(client.search("가", 1, 10).addresses()).isEmpty();
        assertThat(client.search("select", 1, 10).addresses()).isEmpty();
        verify(feignClient, never()).search(any());
    }

    @Test
    @DisplayName("검색어 탓 오류 코드는 빈 목록이다(503 아님)")
    void input_errors_are_empty() {
        for (String code : List.of("E0005", "E0006", "E0008", "E0009", "E0010", "E0011", "E0012", "E0013", "E0015")) {
            given(feignClient.search(any())).willReturn(error(code));
            GeocodedPage page = client.search("관악로", 1, 10);
            assertThat(page.addresses()).as(code).isEmpty();
            assertThat(page.totalCount()).as(code).isZero();
        }
    }

    @Test
    @DisplayName("키 만료 · 미승인 · 알 수 없는 코드 · 빈 본문은 503")
    void vendor_errors_are_503() {
        for (JusoSearchResponse response : List.of(error("E0014"), error("E0001"), error("-999"),
                new JusoSearchResponse(null), new JusoSearchResponse(new JusoSearchResponse.Results(null, null)))) {
            given(feignClient.search(any())).willReturn(response);
            assertThatThrownBy(() -> client.search("관악로", 1, 10))
                    .isInstanceOf(AddressSearchException.class)
                    .extracting(e -> ((AddressSearchException) e).getErrorCode())
                    .isEqualTo(AddressSearchErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
        }
        given(feignClient.search(any())).willReturn(null);
        assertThatThrownBy(() -> client.search("관악로", 1, 10)).isInstanceOf(AddressSearchException.class);
    }

    @Test
    @DisplayName("연결 실패 · 시한 등 예외는 503")
    void transport_failure_is_503() {
        willThrow(new IllegalStateException("connect timed out")).given(feignClient).search(any());

        assertThatThrownBy(() -> client.search("관악로", 1, 10))
                .isInstanceOf(AddressSearchException.class)
                .extracting(e -> ((AddressSearchException) e).getErrorCode())
                .isEqualTo(AddressSearchErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
    }

    @Test
    @DisplayName("빈 칸은 null · 법정동 코드가 10자리 숫자가 아니면 null · 총 개수를 못 읽으면 받은 개수")
    void blanks_and_bad_values() {
        JusoSearchResponse.Juso sparse = new JusoSearchResponse.Juso("  ", "전라남도 나주시 빛가람동 235-1", "",
                null, "46170A", "전라남도", "나주시", " ");
        given(feignClient.search(any())).willReturn(ok("많음", List.of(sparse)));

        GeocodedPage page = client.search("빛가람동", 1, 10);

        GeocodedAddress address = page.addresses().get(0);
        assertThat(address.roadAddress()).isNull();
        assertThat(address.englishAddress()).isNull();
        assertThat(address.postalCode()).isNull();
        assertThat(address.legalDongCode()).isNull();
        assertThat(address.dongName()).isNull();
        assertThat(page.totalCount()).isEqualTo(1);
    }

    // 키 없이 켜지면 검색이 전부 조용히 503 — 부팅에서 멈춰 배포 헬스체크가 실패하게 한다
    @Test
    @DisplayName("승인키가 비면 시작할 때 멈춘다 · 있으면 통과")
    void requires_key_at_startup() {
        for (String blank : new String[]{null, "", "  "}) {
            JusoAddressSearchClient keyless = new JusoAddressSearchClient(feignClient, new JusoProperties("https://juso.test", blank));
            assertThatThrownBy(keyless::requireKey).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JUSO_SEARCH_CONFM_KEY");
        }
        client.requireKey();
    }

    @Test
    @DisplayName("후보 목록이 null 이어도 빈 목록이다")
    void null_list_is_empty() {
        given(feignClient.search(any())).willReturn(ok("0", null));

        assertThat(client.search("관악로", 1, 10).addresses()).isEmpty();
    }
}
