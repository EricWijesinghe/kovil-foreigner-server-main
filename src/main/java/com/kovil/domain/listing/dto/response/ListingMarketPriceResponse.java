package com.kovil.domain.listing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(name = "ListingMarketPriceResponse", description = "시세 — 광고 중 매물의 평균 호가(실거래가가 아니다)")
public record ListingMarketPriceResponse(
        @Schema(description = "평균을 내는 최소 표본 수. 이보다 적으면 그 구의 두 평균이 null")
        int minSampleSize,
        @Schema(description = "지도 영역 안 개수(inViewCount) 내림차순 — 요약 칩은 첫 행을 쓴다")
        List<Region> regions
) {

    @Schema(name = "ListingMarketPriceRegion", description = "구 하나. 숫자는 그 구 전체 공개 매물 기준")
    public record Region(
            @Schema(description = "시군구 법정동코드. 지역 행을 못 찾으면 null") String regionCode,
            @Schema(description = "요청 언어(한국어면 한국어, 아니면 영어)") String regionName,
            @Schema(description = "그 구 공개 매물 흐린 좌표 평균 — 말풍선 자리") BigDecimal lat,
            BigDecimal lng,
            @Schema(description = "지금 지도 영역 안의 개수") long inViewCount,
            @Schema(description = "그 구 전체 표본 수") long sampleSize,
            @Schema(description = "보증금 평균(원, 반올림). 표본 부족이면 null") Long avgDeposit,
            @Schema(description = "월세 평균(원, 반올림). 전세이거나 표본 부족이면 null") Long avgMonthlyRent
    ) {}
}