package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingMaintenance;
import com.kovil.domain.listing.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Schema(description = "매물 목록 항목(공개)")
public record ListingSummaryResponse(

        String listingUuid,
        String listingNo,
        String title,
        @Schema(description = "공개 상태 — ACTIVE(게시 중) · RESERVED(예약 중). 카드의 '예약 중' 배지")
        ListingStatus status,
        @Schema(description = "최초 게시 시각. 수정 · 내렸다 다시 올리기 · 연장으로 바뀌지 않는다")
        LocalDateTime postedAt,

        ListingCategory category,
        RoomType roomType,
        @Schema(description = "분리형·복층. 화면이 roomType 과 조립해 '원룸(분리형)' 을 만든다")
        boolean division,
        boolean duplex,

        SellingType sellingType,
        Long deposit,
        Long monthlyRent,
        @Schema(description = "관리비 합계. 확인불가면 null") Long maintenanceCost,

        @Schema(description = "관리비 분기. 관리비 정보가 없으면 null — maintenanceCost 가 null 일 때 "
                + "확인불가(UNABLE_CHECK)인지 금액 없는 기타부과(ETC_CHARGE)인지 이것으로 가른다")
        MaintenanceChargeType maintenanceChargeType,
        @Schema(description = "세부 비목 중 실비가 하나라도 있으면 true — 합계 옆 '+ α'")
        boolean maintenanceActualCost,

        BigDecimal exclusiveArea,
        FloorType floorType,
        @Schema(description = "숨긴 매물이면 null — 그때는 floorBand 가 대신 온다") Integer floor,
        FloorBand floorBand,
        Integer buildingFloor,

        @Schema(description = "전입신고 가능 여부. 유학생에게는 체류 행정이 걸린 조건이다")
        AvailabilityAnswer residentRegistration,

        @Schema(description = "보증보험 가입 가능 여부")
        AvailabilityAnswer depositInsurance,

        boolean shortLease,

        @Schema(description = "**흐린 좌표**다. 실좌표는 어떤 공개 응답에도 나가지 않는다")
        BigDecimal lat,
        BigDecimal lng,

        @Schema(description = "시군구 법정동코드 — GET /api/v1/regions 의 code. 지역이 없는 옛 매물이면 null")
        String regionCode,
        @Schema(description = "시군구 이름. 한국어 요청이면 한국어, 아니면 영어(지역 API 와 같은 규칙)")
        String regionName,

        @Schema(description = "동까지만. 상세 주소는 매물이 공개를 켠 경우에만 상세에서 준다")
        String dongName,

        String thumbnailUrl,
        Long viewCount,
        Long favoriteCount,
        @Schema(description = "조회 당사자가 찜했는지. 비로그인·미찜이면 false")
        boolean favorited
) {

    public static ListingSummaryResponse of(Listing l, ListingMaintenance maintenance,
                                            boolean maintenanceActualCost,
                                            String regionCode, String regionName,
                                            String thumbnailUrl, boolean favorited) {
        return new ListingSummaryResponse(
                l.getUuid(), l.getListingNo(), l.getTitle(),
                l.getStatus(), l.getPostedAt(),
                l.getCategory(), l.getRoomType(), l.isDivision(), l.isDuplex(),
                l.getSellingType(), l.getDeposit(), l.getMonthlyRent(),
                maintenance == null ? null : maintenance.getTotalCost(),
                maintenance == null ? null : maintenance.getChargeType(),
                maintenanceActualCost,
                l.getExclusiveArea(), l.getFloorType(),
                l.exposedFloor(), l.getFloorBand(), l.getBuildingFloor(),
                l.getResidentRegistration(), l.getDepositInsurance(), l.isShortLease(),
                l.getDisplayLat(), l.getDisplayLng(),
                regionCode, regionName, l.getDongName(),
                thumbnailUrl, l.getViewCount(), l.getFavoriteCount(), favorited);
    }
}
