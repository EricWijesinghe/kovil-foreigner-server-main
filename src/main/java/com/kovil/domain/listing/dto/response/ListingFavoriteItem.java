package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "ListingFavoriteItem", description = "내 찜 목록 한 줄")
public record ListingFavoriteItem(
        @Schema(description = "찜 해제(DELETE /api/v1/listings/{listingUuid}/favorite)에 사용 — 내려간 매물도 지울 수 있음")
        String listingUuid,
        LocalDateTime favoritedAt,
        @Schema(description = "지금 공개 매물이면 true. 내려간 매물(거래 완료 · 만료 · 내림 · 정지)은 false — 이유는 가르지 않음")
        boolean available,
        @Schema(description = "available=true 일 때만. 공개 목록 카드와 같은 모양이고 favorited 는 늘 true")
        ListingSummaryResponse listing,
        @Schema(description = "available=false 일 때만. 흐린 카드에 필요한 최소 칸")
        Brief brief
) {

    @Schema(name = "ListingFavoriteBrief", description = "내려간 매물의 흐린 카드")
    public record Brief(
            String listingNo,
            String thumbnailUrl,
            RoomType roomType,
            boolean division,
            boolean duplex,
            SellingType sellingType,
            Long deposit,
            Long monthlyRent
    ) {

        static Brief of(Listing l, String thumbnailUrl) {
            return new Brief(l.getListingNo(), thumbnailUrl, l.getRoomType(),
                    l.isDivision(), l.isDuplex(),
                    l.getSellingType(), l.getDeposit(), l.getMonthlyRent());
        }
    }

    public static ListingFavoriteItem available(Listing l, LocalDateTime favoritedAt,
                                                ListingSummaryResponse card) {
        return new ListingFavoriteItem(l.getUuid(), favoritedAt, true, card, null);
    }

    public static ListingFavoriteItem gone(Listing l, LocalDateTime favoritedAt, String thumbnailUrl) {
        return new ListingFavoriteItem(l.getUuid(), favoritedAt, false, null, Brief.of(l, thumbnailUrl));
    }
}