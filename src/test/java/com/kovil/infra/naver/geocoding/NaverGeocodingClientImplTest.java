package com.kovil.infra.naver.geocoding;

import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.geocoding.exception.GeocodingErrorCode;
import com.kovil.infra.geocoding.exception.GeocodingException;
import com.kovil.infra.naver.geocoding.dto.response.NaverGeocodeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import feign.codec.DecodeException;
import org.assertj.core.api.Assertions;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// 벤더 응답 → 포트 값 변환 격자. addressElements에서 캐내는 우편번호·시도·시군구가 판별 축이다.
@ExtendWith(MockitoExtension.class)
class NaverGeocodingClientImplTest {

    @Mock NaverGeocodingFeignClient feignClient;

    private NaverGeocodingClientImpl client() {
        return new NaverGeocodingClientImpl(feignClient);
    }

    private static NaverGeocodeResponse.AddressElement el(String type, String longName) {
        return new NaverGeocodeResponse.AddressElement(List.of(type), longName, longName, "");
    }

    @Test
    @DisplayName("addressElements에서 우편번호·시도·시군구를 캐내 싣는다")
    void maps_elements() {
        given(feignClient.geocode("빛가람로 685", 1, 10)).willReturn(new NaverGeocodeResponse(
                "OK", new NaverGeocodeResponse.Meta(1L, 1, 1),
                List.of(new NaverGeocodeResponse.Address(
                        "전라남도 나주시 빛가람로 685", "전라남도 나주시 빛가람동 235-1",
                        "685, Bitgaram-ro, Naju-si, Jeollanam-do, Republic of Korea",
                        "126.7891", "35.0210",
                        List.of(el("SIDO", "전라남도"), el("SIGUGUN", "나주시"), el("DONGMYUN", "빛가람동"),
                                el("POSTAL_CODE", "58217")))),
                ""));

        GeocodedPage page = client().search("빛가람로 685", 1, 10);

        assertThat(page.totalCount()).isEqualTo(1);
        var address = page.addresses().get(0);
        assertThat(address.roadAddress()).isEqualTo("전라남도 나주시 빛가람로 685");
        assertThat(address.postalCode()).isEqualTo("58217");
        assertThat(address.sido()).isEqualTo("전라남도");
        assertThat(address.sigugun()).isEqualTo("나주시");
        assertThat(address.longitude()).isEqualTo("126.7891");
        assertThat(address.latitude()).isEqualTo("35.0210");
        assertThat(address.dongName()).isEqualTo("빛가람동");
        assertThat(address.legalDongCode()).as("네이버는 법정동 코드를 주지 않는다").isNull();
    }

    @Test
    @DisplayName("요소가 없으면 null — 값을 지어내지 않는다")
    void missing_elements_are_null() {
        given(feignClient.geocode("어딘가", 1, 10)).willReturn(new NaverGeocodeResponse(
                "OK", new NaverGeocodeResponse.Meta(1L, 1, 1),
                List.of(new NaverGeocodeResponse.Address("도로명", "지번", "eng", "127", "37", null)),
                ""));

        var address = client().search("어딘가", 1, 10).addresses().get(0);

        assertThat(address.postalCode()).isNull();
        assertThat(address.sido()).isNull();
        assertThat(address.sigugun()).isNull();
    }

    // 벤더가 200에 status=INVALID_REQUEST를 실어 보내는 변형도 문서에 있다 — 조용히 빈 목록으로 두면
    // "검색 결과 없음"과 "벤더 거절"이 화면에서 구분되지 않는다.
    @Test
    @DisplayName("status가 OK가 아니면 벤더 오류로 승격한다")
    void non_ok_status_is_provider_error() {
        given(feignClient.geocode("q", 1, 10)).willReturn(new NaverGeocodeResponse(
                "INVALID_REQUEST", null, null, "bad"));

        assertThatThrownBy(() -> client().search("q", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting("errorCode")
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("addresses가 null이어도 빈 목록으로 선다")
    void null_addresses_become_empty() {
        given(feignClient.geocode("허허벌판", 1, 10)).willReturn(new NaverGeocodeResponse(
                "OK", new NaverGeocodeResponse.Meta(0L, 1, 0), null, ""));

        assertThat(client().search("허허벌판", 1, 10).addresses()).isEmpty();
    }

    // 응답이 없으면(연결 거절 · 시한) 오류 디코더를 거치지 않는다 — 그대로 두면 처리기 없이 500 이었다.
    @Test
    @DisplayName("응답 없는 실패(연결 · 시한)는 GEOCODING_PROVIDER_ERROR 다")
    void no_response_is_provider_error() {
        given(feignClient.geocode("빛가람로 685", 1, 10)).willThrow(Mockito.mock(feign.RetryableException.class));

        Assertions.assertThatThrownBy(() -> client().search("빛가람로 685", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("본문을 못 읽은 실패도 GEOCODING_PROVIDER_ERROR 다")
    void unreadable_body_is_provider_error() {
        given(feignClient.geocode("빛가람로 685", 1, 10)).willThrow(Mockito.mock(DecodeException.class));

        Assertions.assertThatThrownBy(() -> client().search("빛가람로 685", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }

    // 디코더가 가른 코드(키 오류 · 쿼터)는 FeignException 이 아니라 위 잡기에 걸리지 않고 그대로 나간다.
    @Test
    @DisplayName("디코더가 가른 코드(UNAUTHORIZED 등)는 그대로 나간다")
    void decoder_codes_pass_through() {
        given(feignClient.geocode("빛가람로 685", 1, 10)).willThrow(new GeocodingException(
                GeocodingErrorCode.GEOCODING_UNAUTHORIZED));

        Assertions.assertThatThrownBy(() -> client().search("빛가람로 685", 1, 10))
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_UNAUTHORIZED);
    }
}
