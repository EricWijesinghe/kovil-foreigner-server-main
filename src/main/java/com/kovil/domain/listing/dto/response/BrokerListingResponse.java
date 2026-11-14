package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingMaintenance;
import com.kovil.domain.listing.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "중개사 매물 상세(편집용)")
public record BrokerListingResponse(

        String listingUuid,
        String listingNo,
        ListingStatus status,

        ListingCategory category,
        RoomType roomType,
        boolean division,
        boolean duplex,

        String title,
        String description,
        @Schema(description = "번역 캐시 무효화 축. 설명이 바뀔 때만 오른다") int descriptionVersion,

        SellingType sellingType,
        Long deposit,
        Long monthlyRent,

        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        BigDecimal contractArea,

        FloorType floorType,
        @Schema(description = "중개사 화면이라 숨긴 층도 그대로 준다") Integer floor,
        FloorBand floorBand,
        Integer buildingFloor,
        Integer basementFloor,

        Direction direction,
        DirectionBase directionBase,
        HeatingType heating,
        EntranceType entranceType,

        boolean canParking,
        Integer parkingNum,
        Boolean hasElevator,
        Integer householdNum,
        LocalDate buildingApprovalDate,

        boolean moveInImmediate,
        LocalDate moveInDate,

        AvailabilityAnswer residentRegistration,
        AvailabilityAnswer depositInsurance,
        boolean shortLease,

        @Schema(description = "실좌표. **중개사 자신의 매물에만** 나간다") BigDecimal lat,
        BigDecimal lng,
        @Schema(description = "시군구 법정동코드 — 수정 요청에 그대로 되보낸다(그 지역이 닫혔어도 그대로 둔다). "
                + "**지역 없이 저장한 초안이면 null** — 문자열 \"null\" 로 되보내지 말 것", nullable = true)
        String regionCode,
        @Schema(description = """
                지역이 주소로 정해지는가 — true 면 저장할 때 서버가 주소(`addressJibun` · `addressRoad` · `dongName`)로 시군구를 다시 정하고
                보낸 `regionCode` 는 보지 않는다(앱은 지역 칸을 바꿀 수 없게 보여 준다). false 면 주소로 못 가른 매물이라 보낸 `regionCode` 를 쓴다
                """)
        boolean regionFromAddress,
        String dongName,
        String addressJibun,
        String addressRoad,
        boolean showDetailAddress,

        String contactSmsNumber,
        String contactCallNumber,

        LocalDateTime postedAt,
        @Schema(description = "만료 시각. 지나면 EXPIRED 가 되고 7일 안에 연장해야 한다") LocalDateTime expiresAt,
        Long viewCount,
        Long inquiryCount,

        MaintenancePart maintenance,
        List<String> optionCodes,
        List<String> safetyCodes,
        List<ImagePart> images,

        @Schema(description = """
                낡은 폼 감지 — 편집 GET 의 값을 수정(PUT) 본문 `version` 에 그대로 싣는다.
                저장 · 상태 전이 · 만료 배치 · 지역 닫힘 · 차단마다 바뀐다. 값의 크기는 계약이 아니다 — 같은지만 본다.
                """, example = "3")
        Long version
) {

    @Schema(name = "BrokerListingMaintenanceView")
    public record MaintenancePart(
            MaintenanceChargeType chargeType,
            FixedDetailType fixedDetailType,
            Long totalCost,
            String reasonCode,
            String reasonEtcDesc,
            MaintenanceStandardType standardType,
            String standardEtcDesc,
            List<ItemPart> items,
            List<MaintenanceItemCode> includes
    ) {

        public static MaintenancePart of(ListingMaintenance m, List<ItemPart> items,
                                         List<MaintenanceItemCode> includes) {
            if (m == null) {
                return null;
            }
            return new MaintenancePart(m.getChargeType(), m.getFixedDetailType(), m.getTotalCost(),
                    m.getReasonCode(), m.getReasonEtcDesc(), m.getStandardType(), m.getStandardEtcDesc(),
                    items, includes);
        }
    }

    @Schema(name = "BrokerListingMaintenanceItemView")
    public record ItemPart(MaintenanceItemCode itemCode, boolean actualCost, Long cost, String etcDesc) {}

    public record ImagePart(String fileUuid, String imageUrl, int sortOrder) {}

    public static BrokerListingResponse of(Listing listing, String regionCode, boolean regionFromAddress,
                                           MaintenancePart maintenance,
                                           List<String> optionCodes, List<String> safetyCodes,
                                           List<ImagePart> images) {
        return new BrokerListingResponse(
                listing.getUuid(), listing.getListingNo(), listing.getStatus(),
                listing.getCategory(), listing.getRoomType(), listing.isDivision(), listing.isDuplex(),
                listing.getTitle(), listing.getDescription(), listing.getDescriptionVersion(),
                listing.getSellingType(), listing.getDeposit(), listing.getMonthlyRent(),
                listing.getExclusiveArea(), listing.getSupplyArea(), listing.getContractArea(),
                listing.getFloorType(), listing.getFloor(), listing.getFloorBand(), listing.getBuildingFloor(), listing.getBasementFloor(),
                listing.getDirection(), listing.getDirectionBase(), listing.getHeating(), listing.getEntranceType(),
                listing.isCanParking(), listing.getParkingNum(), listing.getHasElevator(), listing.getHouseholdNum(),
                listing.getBuildingApprovalDate(),
                listing.isMoveInImmediate(), listing.getMoveInDate(),
                listing.getResidentRegistration(), listing.getDepositInsurance(), listing.isShortLease(),
                listing.getRealLat(), listing.getRealLng(), regionCode, regionFromAddress, listing.getDongName(),
                listing.getAddressJibun(), listing.getAddressRoad(), listing.isShowDetailAddress(),
                listing.getContactSmsNumber(), listing.getContactCallNumber(),
                listing.getPostedAt(), listing.getExpiresAt(), listing.getViewCount(), listing.getInquiryCount(),
                maintenance, optionCodes, safetyCodes, images, listing.getVersion());
    }
}
