package com.kovil.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 지도 영역 가운데와 가까운 순 정렬용 경도 가중치(cos² 위도). 거리는 평면 근사라 순서 판정에만 씀.
public record MapCenter(BigDecimal lat, BigDecimal lng, BigDecimal lngWeight) {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    public static MapCenter of(BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng) {
        BigDecimal lat = swLat.add(neLat).divide(TWO);
        BigDecimal lng = swLng.add(neLng).divide(TWO);
        double cos = Math.cos(Math.toRadians(lat.doubleValue()));
        return new MapCenter(lat, lng, BigDecimal.valueOf(cos * cos).setScale(10, RoundingMode.HALF_UP));
    }
}
