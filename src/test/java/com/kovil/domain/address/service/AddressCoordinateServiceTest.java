package com.kovil.domain.address.service;

import com.kovil.domain.address.dto.response.AddressCoordinateResponse;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.geocoding.GeocodingClient;
import com.kovil.infra.geocoding.exception.GeocodingErrorCode;
import com.kovil.infra.geocoding.exception.GeocodingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 고른 후보 하나의 좌표 — 도로명 → 지번 순으로 한 건씩, 못 찾으면 null(오류 아님), 벤더 오류는 그대로(502).
@ExtendWith(MockitoExtension.class)
class AddressCoordinateServiceTest {

    @Mock GeocodingClient geocodingClient;

    private AddressCoordinateService service() {
        return new AddressCoordinateService(geocodingClient);
    }

    private static GeocodedPage hit(String longitude, String latitude) {
        return new GeocodedPage(1, 1, List.of(new GeocodedAddress("도로명", "지번", null, null, null, null,
                longitude, latitude, null, null)));
    }

    private static GeocodedPage miss() {
        return new GeocodedPage(0, 1, List.of());
    }

    @Test
    @DisplayName("도로명으로 찾으면 그 좌표 — 지번은 묻지 않는다(호출 한 번)")
    void road_hit() {
        given(geocodingClient.search("경기도 성남시 분당구 불정로 6 (정자동)", 1, 1)).willReturn(hit(" 127.1054328", "37.3595963 "));

        AddressCoordinateResponse response = service().locate("경기도 성남시 분당구 불정로 6 (정자동)", "경기도 성남시 분당구 정자동 178-1");

        assertThat(response.longitude()).isEqualTo("127.1054328");
        assertThat(response.latitude()).isEqualTo("37.3595963");
        verify(geocodingClient, never()).search("경기도 성남시 분당구 정자동 178-1", 1, 1);
    }

    @Test
    @DisplayName("도로명으로 못 찾으면 지번으로 한 번 더")
    void falls_back_to_jibun() {
        given(geocodingClient.search("도로명 주소", 1, 1)).willReturn(miss());
        given(geocodingClient.search("지번 주소 1", 1, 1)).willReturn(hit("126.9", "37.5"));

        assertThat(service().locate("도로명 주소", "지번 주소 1").longitude()).isEqualTo("126.9");
    }

    // 좌표가 비거나 빈 문자열인 후보는 좌표가 아니다 — 다음 후보 · 다음 주소로 넘어간다.
    @Test
    @DisplayName("둘 다 못 찾거나 좌표가 빈 결과면 null — 오류가 아니다")
    void not_found_is_null() {
        given(geocodingClient.search("도로명 주소", 1, 1)).willReturn(hit("", "37.5"));
        given(geocodingClient.search("지번 주소 1", 1, 1)).willReturn(miss());

        AddressCoordinateResponse response = service().locate("도로명 주소", "지번 주소 1");

        assertThat(response.longitude()).isNull();
        assertThat(response.latitude()).isNull();
    }

    @Test
    @DisplayName("둘 다 비면 벤더를 부르지 않는다 · 같은 문자열이면 한 번만 묻는다")
    void blank_and_duplicate_inputs() {
        assertThat(service().locate("  ", null).longitude()).isNull();
        verifyNoInteractions(geocodingClient);

        given(geocodingClient.search("같은 주소", 1, 1)).willReturn(miss());
        service().locate("같은 주소", " 같은 주소 ");
        verify(geocodingClient).search("같은 주소", 1, 1);
    }

    @Test
    @DisplayName("200자를 넘는 주소는 200자까지만 넘긴다")
    void long_input_is_clipped() {
        String head = "가".repeat(199) + " ";
        given(geocodingClient.search("가".repeat(199), 1, 1)).willReturn(miss());

        service().locate(head + "나".repeat(500), null);

        verify(geocodingClient).search("가".repeat(199), 1, 1);
    }

    @Test
    @DisplayName("벤더 오류는 삼키지 않는다 — 화면이 '못 찾음'과 '지금 안 됨'을 가른다")
    void vendor_error_propagates() {
        given(geocodingClient.search(anyString(), anyInt(), anyInt()))
                .willThrow(new GeocodingException(GeocodingErrorCode.GEOCODING_QUOTA_EXCEEDED));

        assertThatThrownBy(() -> service().locate("도로명 주소", null)).isInstanceOf(GeocodingException.class);
    }
}
