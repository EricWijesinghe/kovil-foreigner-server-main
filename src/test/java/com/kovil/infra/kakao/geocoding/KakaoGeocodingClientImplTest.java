package com.kovil.infra.kakao.geocoding;

import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.kakao.geocoding.dto.response.KakaoAddressResponse;
import com.kovil.infra.geocoding.exception.GeocodingErrorCode;
import com.kovil.infra.geocoding.exception.GeocodingException;
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
import static org.mockito.BDDMockito.given;

// 카카오 응답 → 포트 값 변환 격자. 도로명 결손(지번 전용 주소)이 판별 축이다.
@ExtendWith(MockitoExtension.class)
class KakaoGeocodingClientImplTest {

    @Mock KakaoGeocodingFeignClient feignClient;

    private KakaoGeocodingClientImpl client() {
        return new KakaoGeocodingClientImpl(feignClient);
    }

    @Test
    @DisplayName("도로명·우편번호·시도·시군구가 포트 형상으로 옮겨진다")
    void maps_full_document() {
        given(feignClient.searchAddress("빛가람로 685", 1, 10)).willReturn(new KakaoAddressResponse(
                List.of(new KakaoAddressResponse.Document(
                        "전남 나주시 빛가람동 235-1", "126.7891", "35.0210",
                        new KakaoAddressResponse.RoadAddress("전남 나주시 빛가람로 685", "58217", "전남", "나주시"),
                        new KakaoAddressResponse.Address("전남 나주시 빛가람동 235-1", "전남", "나주시", "빛가람동", "4617010300"))),
                new KakaoAddressResponse.Meta(1L, 1L, true)));

        GeocodedPage page = client().search("빛가람로 685", 1, 10);

        var address = page.addresses().get(0);
        assertThat(address.roadAddress()).isEqualTo("전남 나주시 빛가람로 685");
        assertThat(address.jibunAddress()).isEqualTo("전남 나주시 빛가람동 235-1");
        assertThat(address.postalCode()).isEqualTo("58217");
        assertThat(address.sido()).isEqualTo("전남");
        assertThat(address.sigugun()).isEqualTo("나주시");
        assertThat(address.englishAddress()).isNull();
        // 법정동 코드 · 동 이름은 지번(address) 쪽에만 있다.
        assertThat(address.legalDongCode()).isEqualTo("4617010300");
        assertThat(address.dongName()).isEqualTo("빛가람동");
    }

    // 산·임야 등 도로명이 아예 없는 지번 주소가 실존한다 — 빈 행 대신 매칭 표기를 싣는다.
    @Test
    @DisplayName("도로명 결손 주소는 검색 매칭 표기로 채워지고 우편번호는 null이다")
    void jibun_only_document() {
        given(feignClient.searchAddress("산 12", 1, 10)).willReturn(new KakaoAddressResponse(
                List.of(new KakaoAddressResponse.Document(
                        "전남 나주시 금천면 산 12", "126.7", "35.0", null,
                        new KakaoAddressResponse.Address("전남 나주시 금천면 산 12", "전남", "나주시", "금천면", ""))),
                new KakaoAddressResponse.Meta(1L, 1L, true)));

        var address = client().search("산 12", 1, 10).addresses().get(0);

        assertThat(address.roadAddress()).isEqualTo("전남 나주시 금천면 산 12");
        assertThat(address.postalCode()).isNull();
        assertThat(address.sigugun()).isEqualTo("나주시");
        assertThat(address.dongName()).isEqualTo("금천면");
        assertThat(address.legalDongCode()).as("카카오의 빈 문자열은 null").isNull();
    }

    @Test
    @DisplayName("size는 카카오 상한 30으로 눌린다 — 넘기면 벤더가 400을 준다")
    void size_is_clamped() {
        given(feignClient.searchAddress("서울", 1, 30)).willReturn(new KakaoAddressResponse(
                List.of(), new KakaoAddressResponse.Meta(0L, 0L, true)));

        assertThat(client().search("서울", 1, 100).addresses()).isEmpty();
    }

    // 응답이 없으면(연결 거절 · 시한) 오류 디코더를 거치지 않는다 — 그대로 두면 처리기 없이 500 이었다.
    @Test
    @DisplayName("응답 없는 실패(연결 · 시한)는 GEOCODING_PROVIDER_ERROR 다")
    void no_response_is_provider_error() {
        given(feignClient.searchAddress("빛가람로 685", 1, 10)).willThrow(Mockito.mock(feign.RetryableException.class));

        Assertions.assertThatThrownBy(() -> client().search("빛가람로 685", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("본문을 못 읽은 실패도 GEOCODING_PROVIDER_ERROR 다")
    void unreadable_body_is_provider_error() {
        given(feignClient.searchAddress("빛가람로 685", 1, 10)).willThrow(Mockito.mock(DecodeException.class));

        Assertions.assertThatThrownBy(() -> client().search("빛가람로 685", 1, 10))
                .isInstanceOf(GeocodingException.class)
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }

    // 디코더가 가른 코드(키 오류 · 쿼터)는 FeignException 이 아니라 위 잡기에 걸리지 않고 그대로 나간다.
    @Test
    @DisplayName("디코더가 가른 코드(UNAUTHORIZED 등)는 그대로 나간다")
    void decoder_codes_pass_through() {
        given(feignClient.searchAddress("빛가람로 685", 1, 10)).willThrow(new GeocodingException(
                GeocodingErrorCode.GEOCODING_UNAUTHORIZED));

        Assertions.assertThatThrownBy(() -> client().search("빛가람로 685", 1, 10))
                .extracting(e -> ((GeocodingException) e).getErrorCode())
                .isEqualTo(GeocodingErrorCode.GEOCODING_UNAUTHORIZED);
    }
}
