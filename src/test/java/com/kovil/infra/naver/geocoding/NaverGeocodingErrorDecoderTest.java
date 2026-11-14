package com.kovil.infra.naver.geocoding;

import com.kovil.infra.geocoding.exception.GeocodingErrorCode;
import com.kovil.infra.geocoding.exception.GeocodingException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 크리덴셜(401/403)·한도(429)·그 외를 갈라 놓는다 — 뭉치면 콘솔을 봐야 할 신호가 묻힌다.
class NaverGeocodingErrorDecoderTest {

    private final NaverGeocodingErrorDecoder decoder = new NaverGeocodingErrorDecoder();

    private static Response response(int status) {
        return Response.builder()
                .status(status)
                .request(Request.create(Request.HttpMethod.GET, "/map-geocode/v2/geocode",
                        Map.of(), null, StandardCharsets.UTF_8, null))
                .build();
    }

    private GeocodingErrorCode decode(int status) {
        return (GeocodingErrorCode) ((GeocodingException) decoder.decode("m", response(status))).getErrorCode();
    }

    @Test
    @DisplayName("401·403 → 크리덴셜 오류, 429 → 한도, 그 외 → 벤더 오류")
    void status_mapping() {
        assertThat(decode(401)).isEqualTo(GeocodingErrorCode.GEOCODING_UNAUTHORIZED);
        assertThat(decode(403)).isEqualTo(GeocodingErrorCode.GEOCODING_UNAUTHORIZED);
        assertThat(decode(429)).isEqualTo(GeocodingErrorCode.GEOCODING_QUOTA_EXCEEDED);
        assertThat(decode(500)).isEqualTo(GeocodingErrorCode.GEOCODING_PROVIDER_ERROR);
    }
}
