package com.kovil.infra.naver.geocoding;

import com.kovil.infra.geocoding.exception.GeocodingException;
import feign.Response;
import feign.codec.ErrorDecoder;

import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_PROVIDER_ERROR;
import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_QUOTA_EXCEEDED;
import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_UNAUTHORIZED;

// 벤더 상태코드를 우리 코드로 옮긴다. 401·403을 일반 오류와 뭉치면
// "크리덴셜이 틀렸다"를 "검색이 안 된다"로 오진하게 된다.
// 429는 API Gateway 계층 — 무료 한도 소진이거나 Application에서 Geocoding을 선택 안 한 것.
public class NaverGeocodingErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return switch (response.status()) {
            case 401, 403 -> new GeocodingException(GEOCODING_UNAUTHORIZED);
            case 429 -> new GeocodingException(GEOCODING_QUOTA_EXCEEDED);
            default -> new GeocodingException(GEOCODING_PROVIDER_ERROR);
        };
    }
}
