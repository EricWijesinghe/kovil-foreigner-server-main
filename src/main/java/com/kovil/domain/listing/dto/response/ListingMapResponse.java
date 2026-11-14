package com.kovil.domain.listing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;


@Schema(description = "지도 표시 데이터")
public record ListingMapResponse(
        @Schema(description = "PIN 이면 개별 매물, CLUSTER 면 시군구 집계")
        Mode mode,
        long total,
        List<Pin> pins,
        List<Cluster> clusters
) {

    public enum Mode { PIN, CLUSTER }

    @Schema(description = "개별 매물 핀. 좌표는 **흐린 값**이다")
    public record Pin(String listingUuid, BigDecimal lat, BigDecimal lng,
                      Long deposit, Long monthlyRent, String sellingType,
                      @Schema(description = "시군구 법정동코드 — 이름은 앱이 GET /api/v1/regions 로 붙인다. 지역 없는 옛 매물이면 null")
                      String regionCode) {}

    @Schema(description = "시군구 묶음. regionCode 는 목록 필터(`regionCode`)에 그대로 넣는다. "
            + "regionName 은 요청 언어(한국어면 한국어, 아니면 영어). 지역 행을 찾지 못한 묶음은 둘 다 null 이고 개수만 온다")
    public record Cluster(String regionCode, String regionName, long count,
                          BigDecimal lat, BigDecimal lng) {}

    public static ListingMapResponse pins(List<Pin> pins) {
        return new ListingMapResponse(Mode.PIN, pins.size(), pins, List.of());
    }

    public static ListingMapResponse clusters(long total, List<Cluster> clusters) {
        return new ListingMapResponse(Mode.CLUSTER, total, List.of(), clusters);
    }
}
