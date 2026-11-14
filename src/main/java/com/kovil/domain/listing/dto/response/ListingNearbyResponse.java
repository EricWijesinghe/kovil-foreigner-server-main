package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.enums.NearbyCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(name = "ListingNearbyResponse", description = "매물 주변시설 — 실패해도 200 이고 status 로 가른다")
public record ListingNearbyResponse(
        @Schema(description = "OK 면 categories 가 8개 차 있다. UNAVAILABLE 이면 비어 있다 — 앱은 주변시설 칸을 숨긴다")
        Status status,
        @Schema(description = "검색 반경(m). 매물의 흐린 좌표가 중심이다")
        int radius,
        List<Category> categories
) {

    @Schema(name = "ListingNearbyStatus")
    public enum Status { OK, UNAVAILABLE }

    @Schema(name = "ListingNearbyCategory", description = "카테고리 하나. 매물마다 같은 순서 · 같은 8개")
    public record Category(
            NearbyCategory code,
            @Schema(description = "반경 안 전체 수") long count,
            @Schema(description = "가까운 순 최대 3곳. 시간 · 거리는 싣지 않는다") List<Place> nearest
    ) {}

    @Schema(name = "ListingNearbyPlace")
    public record Place(String name, BigDecimal lat, BigDecimal lng) {}

    public static ListingNearbyResponse unavailable(int radius) {
        return new ListingNearbyResponse(Status.UNAVAILABLE, radius, List.of());
    }
}