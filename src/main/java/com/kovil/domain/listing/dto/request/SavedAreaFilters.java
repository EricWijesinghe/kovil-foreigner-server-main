package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;


@Schema(description = "저장할 때의 목록 필터 — 칸 이름이 목록 API 파라미터와 같다. 비운 칸은 그 축 전체")
public record SavedAreaFilters(
        ListingCategory category,
        List<SellingType> sellingTypes,
        List<RoomType> roomTypes,
        List<FloorType> floorTypes,
        Long depositMin, Long depositMax,
        Long rentMin, Long rentMax,
        BigDecimal areaMin, BigDecimal areaMax,
        Boolean canParking,
        Boolean shortLease,
        AvailabilityAnswer residentRegistration,
        @Schema(description = "시군구 법정동코드 — 열린 지역만(닫혔거나 없으면 400 LISTING_REGION_INVALID)")
        @Size(max = 10) String regionCode
) {

    public static final SavedAreaFilters NONE = new SavedAreaFilters(null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    public SavedAreaFilters {
        sellingTypes = distinct(sellingTypes);
        roomTypes = distinct(roomTypes);
        floorTypes = distinct(floorTypes);
        regionCode = regionCode == null || regionCode.isBlank() ? null : regionCode.trim();
    }

    private static <T> List<T> distinct(List<T> given) {
        return given == null ? null : List.copyOf(new LinkedHashSet<>(given));
    }
}