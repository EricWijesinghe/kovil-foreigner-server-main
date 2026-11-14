package com.kovil.infra.naver.geocoding.dto.response;

import java.util.List;

// GET /map-geocode/v2/geocode 응답. x=경도, y=위도 — 벤더가 문자열로 준다(변환하지 않고 그대로 나른다).
public record NaverGeocodeResponse(
        String status,
        Meta meta,
        List<Address> addresses,
        String errorMessage
) {

    public record Meta(Long totalCount, Integer page, Integer count) {
    }

    public record Address(
            String roadAddress,
            String jibunAddress,
            String englishAddress,
            String x,
            String y,
            List<AddressElement> addressElements
    ) {
    }

    public record AddressElement(List<String> types, String longName, String shortName, String code) {
    }
}
