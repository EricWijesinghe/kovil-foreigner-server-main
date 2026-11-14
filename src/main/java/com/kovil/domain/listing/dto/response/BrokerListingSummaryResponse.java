package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "중개사 매물 목록 항목")
public record BrokerListingSummaryResponse(
        String listingUuid,
        String listingNo,
        ListingStatus status,
        String title,
        RoomType roomType,
        @Schema(description = "분리형") boolean division,
        @Schema(description = "복층") boolean duplex,
        SellingType sellingType,
        Long deposit,
        Long monthlyRent,
        BigDecimal exclusiveArea,
        String thumbnailUrl,
        @Schema(description = "시군구 법정동코드. 지역 없는 초안이면 null. "
                + "`GET /api/v1/regions` 에 없는 코드면 닫힌 지역이다 — 다시 게시 · 연장할 수 없다")
        String regionCode,
        LocalDateTime postedAt,
        @Schema(description = "EXPIRED 면 이 시각 + 7일까지 연장할 수 있다") LocalDateTime expiresAt,
        Long viewCount,
        Long inquiryCount
) {

    public static BrokerListingSummaryResponse of(
            Listing listing,
            String thumbnailUrl,
            String regionCode
    ) {
        return new BrokerListingSummaryResponse(
                listing.getUuid(), listing.getListingNo(), listing.getStatus(), listing.getTitle(),
                listing.getRoomType(), listing.isDivision(), listing.isDuplex(), listing.getSellingType(), listing.getDeposit(), listing.getMonthlyRent(),
                listing.getExclusiveArea(), thumbnailUrl, regionCode,
                listing.getPostedAt(), listing.getExpiresAt(), listing.getViewCount(), listing.getInquiryCount());
    }
}