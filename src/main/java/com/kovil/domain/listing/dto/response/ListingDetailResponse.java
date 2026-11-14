package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


@Schema(description = "매물 상세(공개)")
public record ListingDetailResponse(

        String listingUuid,
        String listingNo,
        ListingStatus status,
        String title,
        @Schema(description = "중개사가 쓴 **한국어** 원문. 번역은 별도 엔드포인트가 준다")
        String description,
        @Schema(description = "번역 캐시 키의 일부. 클라이언트가 번역 요청에 쓸 필요는 없다")
        int descriptionVersion,

        ListingCategory category,
        RoomType roomType,
        boolean division,
        boolean duplex,

        SellingType sellingType,
        Long deposit,
        Long monthlyRent,
        MaintenanceView maintenance,

        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        BigDecimal contractArea,

        FloorType floorType,
        @Schema(description = "숨긴 매물이면 null") Integer floor,
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

        @Schema(description = "**흐린 좌표**") BigDecimal lat,
        BigDecimal lng,
        String dongName,
        @Schema(description = "매물이 상세 주소 공개를 켠 경우에만 채워진다. 아니면 동까지만")
        String address,

        List<String> optionCodes,
        List<String> safetyCodes,
        List<String> imageUrls,

        OfficeView office,

        LocalDateTime postedAt,
        Long viewCount,
        Long favoriteCount,
        Long inquiryCount,
        boolean favorited
) {

    public record MaintenanceView(
            MaintenanceChargeType chargeType,
            FixedDetailType fixedDetailType,
            @Schema(description = "확인불가면 null — 화면은 '확인불가'를 찍는다") Long totalCost,
            @Schema(description = "문구는 앱이 각 언어로 가진다")
            String reasonCode,
            String reasonEtcDesc,
            MaintenanceStandardType standardType,
            String standardEtcDesc,
            List<ItemView> items,
            List<MaintenanceItemCode> includes
    ) {}

    public record ItemView(
            MaintenanceItemCode itemCode,
            @Schema(description = "true 면 금액 대신 '실비'. 합계 옆 '+ α' 의 근거다")
            boolean actualCost,
            Long cost,
            String etcDesc
    ) {}

    @Schema(name = "ListingOfficeView", description = "공개 상세의 중개사무소 — 대표번호 · 사업자번호 · 등록증은 싣지 않음")
    public record OfficeView(
            @Schema(description = "\"이 사무소의 다른 방\" — 목록 `GET /api/v1/listings?officeUuid=` 에 그대로 넘긴다")
            String officeUuid,
            String name, String ceoName, String regNo, String address) {

        public static OfficeView of(BrokerageOffice o) {
            return new OfficeView(o.getUuid(), o.getName(), o.getCeoName(), o.getRegNo(), o.getAddress());
        }
    }
    
    public static ListingDetailResponse of(Listing listing, BrokerageOffice office,
                                           MaintenanceView maintenance,
                                           List<String> optionCodes, List<String> safetyCodes,
                                           List<String> imageUrls, boolean favorited) {
        return new ListingDetailResponse(
                listing.getUuid(), listing.getListingNo(), listing.getStatus(), listing.getTitle(),
                listing.getDescription(), listing.getDescriptionVersion(),
                listing.getCategory(), listing.getRoomType(), listing.isDivision(), listing.isDuplex(),
                listing.getSellingType(), listing.getDeposit(), listing.getMonthlyRent(), maintenance,
                listing.getExclusiveArea(), listing.getSupplyArea(), listing.getContractArea(),
                listing.getFloorType(), listing.exposedFloor(), listing.getFloorBand(),
                listing.getBuildingFloor(), listing.getBasementFloor(),
                listing.getDirection(), listing.getDirectionBase(), listing.getHeating(), listing.getEntranceType(),
                listing.isCanParking(), listing.getParkingNum(), listing.getHasElevator(), listing.getHouseholdNum(),
                listing.getBuildingApprovalDate(),
                listing.isMoveInImmediate(), listing.getMoveInDate(),
                listing.getResidentRegistration(), listing.getDepositInsurance(), listing.isShortLease(),
                listing.getDisplayLat(), listing.getDisplayLng(), listing.getDongName(),
                listing.isShowDetailAddress() ? listing.getAddressJibun() : null,
                optionCodes, safetyCodes, imageUrls,
                OfficeView.of(office),
                listing.getPostedAt(), listing.getViewCount(), listing.getFavoriteCount(), listing.getInquiryCount(),
                favorited);
    }
}
