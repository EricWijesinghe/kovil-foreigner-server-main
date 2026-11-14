package com.kovil.infra.kakao.geocoding;

import com.kovil.infra.geocoding.exception.GeocodingException;
import feign.Response;
import feign.codec.ErrorDecoder;

import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_PROVIDER_ERROR;
import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_QUOTA_EXCEEDED;
import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_UNAUTHORIZED;

// 네이버 디코더와 같은 3분류 — 벤더가 달라도 화면이 보는 실패 축은 하나여야 한다.
public class KakaoGeocodingErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return switch (response.status()) {
            case 401, 403 -> new GeocodingException(GEOCODING_UNAUTHORIZED);
            case 429 -> new GeocodingException(GEOCODING_QUOTA_EXCEEDED);
            default -> new GeocodingException(GEOCODING_PROVIDER_ERROR);
        };
    }
}
