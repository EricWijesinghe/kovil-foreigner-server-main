package com.kovil.infra.kakao.geocoding.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// GET /v2/local/search/address.json 응답. x=경도, y=위도 — 문자열로 온다(그대로 나른다).
public record KakaoAddressResponse(List<Document> documents, Meta meta) {

    public record Document(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("x") String longitude,
            @JsonProperty("y") String latitude,
            @JsonProperty("road_address") RoadAddress roadAddress,
            @JsonProperty("address") Address address
    ) {
    }

    public record RoadAddress(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("zone_no") String zoneNo,
            @JsonProperty("region_1depth_name") String region1,
            @JsonProperty("region_2depth_name") String region2
    ) {
    }

    public record Address(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("region_1depth_name") String region1,
            @JsonProperty("region_2depth_name") String region2,
            @JsonProperty("region_3depth_name") String region3,
            // 법정 코드. 도로명(road_address)에는 없다.
            @JsonProperty("b_code") String legalDongCode
    ) {
    }

    public record Meta(
            // total_count가 진짜 전체지만 카카오는 45건 너머를 안 준다 —
            // 페이지 UI가 못 가는 페이지를 약속하지 않도록 pageable_count를 쓴다.
            @JsonProperty("total_count") Long totalCount,
            @JsonProperty("pageable_count") Long pageableCount,
            @JsonProperty("is_end") Boolean isEnd
    ) {
    }
}
