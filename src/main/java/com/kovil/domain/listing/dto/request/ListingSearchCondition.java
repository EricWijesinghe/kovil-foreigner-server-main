package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.exception.ListingException;

import java.math.BigDecimal;
import java.util.List;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;


public record ListingSearchCondition(
        ListingCategory category,
        List<SellingType> sellingTypes,
        List<RoomType> roomTypes,
        List<FloorType> floorTypes,
        Long depositMin, Long depositMax,
        Long rentMin, Long rentMax,
        BigDecimal areaMin, BigDecimal areaMax,
        boolean canParking,
        boolean shortLease,
        AvailabilityAnswer residentRegistration,
        Long regionId,
        String officeUuid,
        BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng
) {

    private static final int MAX_SCALE = 15;
    private static final BigDecimal LAT_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LNG_LIMIT = BigDecimal.valueOf(180);
    private static final BigDecimal AREA_LIMIT = new BigDecimal("100000000");

    public ListingSearchCondition {
        sellingTypes = orAll(sellingTypes, SellingType.values());
        roomTypes = orAll(roomTypes, RoomType.values());
        floorTypes = orAll(floorTypes, FloorType.values());
        requireSane(swLat, LAT_LIMIT);
        requireSane(neLat, LAT_LIMIT);
        requireSane(swLng, LNG_LIMIT);
        requireSane(neLng, LNG_LIMIT);
        requireSane(areaMin, AREA_LIMIT);
        requireSane(areaMax, AREA_LIMIT);
        officeUuid = officeUuid == null || officeUuid.isBlank() ? null : officeUuid.strip();
    }

    private static <T> List<T> orAll(List<T> given, T[] all) {
        return given == null || given.isEmpty() ? List.of(all) : List.copyOf(given);
    }

    private static void requireSane(BigDecimal value, BigDecimal limit) {
        if (value != null &&
                (value.scale() > MAX_SCALE || value.abs().compareTo(limit) > 0)) {
            throw new ListingException(INVALID_REQUEST);
        }
    }

    public boolean hasBoundingBox() {
        return swLat != null && swLng != null && neLat != null && neLng != null;
    }

    public BigDecimal effectiveSwLat() {
        return hasBoundingBox() ? swLat : null;
    }

    public BigDecimal effectiveSwLng() {
        return hasBoundingBox() ? swLng : null;
    }

    public BigDecimal effectiveNeLat() {
        return hasBoundingBox() ? neLat : null;
    }

    public BigDecimal effectiveNeLng() {
        return hasBoundingBox() ? neLng : null;
    }
}
