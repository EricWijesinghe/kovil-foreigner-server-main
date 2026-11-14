package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.enums.ListingBlockAction;
import com.kovil.domain.listing.enums.ListingReportReason;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.SellingType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "AdminListingDetailResponse", description = "관리자 매물 상세 — 차단 판단 재료. 신고자는 싣지 않는다.")
public record AdminListingDetailResponse(
        String listingUuid,
        String listingNo,
        ListingStatus status,
        String title,
        @Schema(description = "상세설명 한국어 원문") String description,
        SellingType sellingType,
        Long deposit,
        Long monthlyRent,
        @Schema(description = "도로명 주소. 없으면 null") String addressRoad,
        String addressJibun,
        @Schema(description = "사진 URL, 대표 사진이 첫 번째") List<String> imageUrls,
        @Schema(description = "처음 게시한 시각. 게시된 적 없으면 null") LocalDateTime postedAt,
        LocalDateTime expiresAt,
        Office office,
        @Schema(description = "이 매물의 전체 신고 수") long reportCount,
        @Schema(description = "그중 대기 중인 수") long pendingReportCount,
        @Schema(description = "최신 100건까지, 최신부터") List<Report> reports,
        @Schema(description = "차단 · 해제 이력, 최신부터") List<BlockLog> blockHistory
) {

    @Schema(name = "AdminListingOffice", description = "매물을 올린 사무소")
    public record Office(
            String officeUuid,
            String name,
            String regNo,
            String tel,
            @Schema(description = "지금 광고를 낼 수 있는 사무소인가(대표자 부문 승인 · 계정 정상). "
                    + "false 면 차단과 상관없이 공개 목록에서 빠져 있다")
            boolean advertisable
    ) {}

    @Schema(name = "AdminListingDetailReport", description = "이 매물의 신고 한 건")
    public record Report(
            String reportUuid,
            ListingReportReason reasonCode,
            String detail,
            ListingReportStatus status,
            LocalDateTime createdAt,
            String handledByName,
            LocalDateTime handledAt
    ) {}

    @Schema(name = "AdminListingBlockLog", description = "차단 · 해제 한 건")
    public record BlockLog(
            ListingBlockAction action,
            @Schema(description = "차단 사유. 해제면 null") String reason,
            @Schema(description = "처리한 관리자 이름. 이름 없음이면 null") String adminName,
            LocalDateTime createdAt
    ) {}
}