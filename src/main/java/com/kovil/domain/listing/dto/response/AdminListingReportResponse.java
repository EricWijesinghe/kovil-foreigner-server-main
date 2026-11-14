package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.enums.ListingReportReason;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.enums.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "AdminListingReportResponse",
        description = "매물 신고 대기열 행. `sameListingPendingCount` 가 2 이상이면 여러 사람이 지목한 매물이다.")
public record AdminListingReportResponse(
        String reportUuid,
        String listingUuid,
        String listingNo,
        String listingTitle,
        @Schema(description = "매물의 지금 상태. BLOCKED 면 이미 차단됐다") ListingStatus listingStatus,
        ListingReportReason reasonCode,
        @Schema(description = "신고자가 적은 설명. 없으면 null") String detail,
        @Schema(description = "같은 매물의 대기 중 신고 수") long sameListingPendingCount,
        ListingReportStatus status,
        LocalDateTime createdAt,
        @Schema(description = "처리한 관리자 이름. 미처리 · 이름 없음이면 null") String handledByName,
        @Schema(description = "처리 시각. 미처리면 null") LocalDateTime handledAt
) {
}