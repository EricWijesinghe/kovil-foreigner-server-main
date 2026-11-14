package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.enums.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "내 사무소 매물 상태별 개수")
public record BrokerListingStatusCountsResponse(
        @Schema(description = "상태 8개 전부 — 0 이어도 실린다") Map<ListingStatus, Long> byStatus,
        @Schema(description = "EXPIRED 중 만료 시각 + 7일이 아직 지나지 않아 연장할 수 있는 수") long renewable
) {}