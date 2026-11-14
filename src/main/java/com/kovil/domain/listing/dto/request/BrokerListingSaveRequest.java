package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;


@Schema(description = "매물 등록·수정")
public record BrokerListingSaveRequest(

        @NotNull ListingCategory category,
        @NotNull RoomType roomType,
        @Schema(description = "분리형 — room_type 과 조립해 '원룸(분리형)' 이 된다")
        Boolean division,
        @Schema(description = "복층")
        Boolean duplex,

        @NotBlank @Size(max = 100) String title,
        @Size(max = 2000)
        @Schema(description = "중개사가 쓰는 한국어 본문. 바뀌면 번역 캐시가 무효화된다")
        String description,

        @NotNull SellingType sellingType,
        @NotNull @PositiveOrZero @Schema(description = "월세면 보증금, 전세면 전세금. 원 단위") Long deposit,
        @PositiveOrZero @Schema(description = "전세는 0 또는 생략") Long monthlyRent,

        @NotNull @Positive BigDecimal exclusiveArea,
        @Positive BigDecimal supplyArea,
        @Positive BigDecimal contractArea,

        @Schema(description = "GROUND · SEMI_BASEMENT · ROOFTOP. 반지하·옥탑은 층수로 표현할 수 없다")
        FloorType floorType,
        @Schema(description = "정확한 층. floorBand 와 **둘 중 하나만** 채운다")
        Integer floor,
        @Schema(description = "층 노출을 꺼릴 때. 값이 있으면 응답에서 floor 를 빼고 이것만 보낸다")
        FloorBand floorBand,
        Integer buildingFloor,
        Integer basementFloor,

        Direction direction,
        DirectionBase directionBase,
        HeatingType heating,
        EntranceType entranceType,

        Boolean canParking,
        @PositiveOrZero Integer parkingNum,
        Boolean hasElevator,
        @PositiveOrZero Integer householdNum,
        LocalDate buildingApprovalDate,

        Boolean moveInImmediate,
        LocalDate moveInDate,

        @Schema(description = "전입신고 가능 여부. **ASK 로는 게시할 수 없다** — 유학생에게는 생존 조건이다")
        AvailabilityAnswer residentRegistration,
        AvailabilityAnswer depositInsurance,
        Boolean shortLease,

        @NotNull BigDecimal lat,
        @NotNull BigDecimal lng,

        @Size(max = 10)
        @Schema(description = """
                시군구 법정동코드(`GET /api/v1/regions` 의 code). **서버가 주소(`addressJibun` · `addressRoad` · `dongName`)로
                시군구를 정할 수 있으면 이 값은 보지 않는다** — 그때는 닫힌 시군구여도 그 지역으로 저장되고 게시만 막힌다.
                주소로 못 가를 때만 이 값을 쓴다(열린 지역만, 아니면 `LISTING_REGION_INVALID`). 초안은 비워도 되지만 게시하려면 지역이 있어야 한다
                """, example = "11620")
        String regionCode,
        @Size(max = 50) String dongName,
        @NotBlank @Size(max = 255) String addressJibun,
        @Size(max = 255) String addressRoad,
        @Schema(description = "false 면 동까지만 공개한다")
        Boolean showDetailAddress,

        @Schema(description = "비우면 사무소 공시 번호로 떨어진다")
        @Size(max = 30) String contactSmsNumber,
        @Size(max = 30) String contactCallNumber,

        @Valid MaintenancePart maintenance,

        @Schema(description = "옵션 코드. request_options 와 어휘를 맞춘다(AIR_CONDITIONER 등)")
        @Size(max = 60) List<@Size(max = 40) String> optionCodes,
        @Schema(description = "안전시설 코드. 옵션과 **코드 공간이 다르다**")
        @Size(max = 30) List<@Size(max = 40) String> safetyCodes,
        @Schema(description = "확정된 파일 uuid. 순서가 곧 노출 순서다. 최대 10장")
        @Size(max = 10) List<@Size(max = 36) String> imageFileUuids,

        @Schema(description = """
                편집 GET 이 준 `version`. 수정(PUT)에서 지금 매물의 값과 다르면 **409 `CONFLICT`** 이고 아무것도 저장하지 않는다.
                비우면 검사하지 않는다(옛 앱). 등록(POST)에서는 무시한다.
                """, example = "3")
        Long version
) {

    public boolean divisionOrFalse() {
        return Boolean.TRUE.equals(division);
    }

    public boolean duplexOrFalse() {
        return Boolean.TRUE.equals(duplex);
    }

    public boolean canParkingOrFalse() {
        return Boolean.TRUE.equals(canParking);
    }

    public boolean moveInImmediateOrFalse() {
        return Boolean.TRUE.equals(moveInImmediate);
    }

    public boolean shortLeaseOrFalse() {
        return Boolean.TRUE.equals(shortLease);
    }

    public boolean showDetailAddressOrFalse() {
        return Boolean.TRUE.equals(showDetailAddress);
    }

    @Schema(name = "BrokerListingMaintenanceInput")
    public record MaintenancePart(
            @NotNull MaintenanceChargeType chargeType,
            @Schema(description = "정액관리비일 때만. **금액에서 파생하지 말 것** — 중개사가 고르는 값이다")
            FixedDetailType fixedDetailType,
            @PositiveOrZero @Schema(description = "확인불가면 비운다") Long totalCost,
            @Schema(description = "기타부과 E01~E06·E98·E99 / 확인불가 U01~U04·U99")
            @Size(max = 4) String reasonCode,
            @Size(max = 255) String reasonEtcDesc,
            MaintenanceStandardType standardType,
            @Size(max = 255) String standardEtcDesc,
            @Schema(description = "정액 + 세부내역 있음일 때만") @Valid List<ItemPart> items,
            @Schema(description = "세부내역을 못 적을 때 '무엇이 포함되는지'") List<MaintenanceItemCode> includes
    ) {
    }

    @Schema(name = "BrokerListingMaintenanceItemInput")
    public record ItemPart(
            @NotNull MaintenanceItemCode itemCode,
            @Schema(description = "true 면 금액 대신 '실비'로 표시된다 — 합계 옆 '+ α' 의 정체다")
            Boolean actualCost,
            @PositiveOrZero Long cost,
            @Size(max = 255) String etcDesc
    ) {

        public boolean actualCostOrFalse() {
            return Boolean.TRUE.equals(actualCost);
        }
    }
}
