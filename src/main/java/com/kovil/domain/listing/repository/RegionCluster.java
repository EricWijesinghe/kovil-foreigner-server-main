package com.kovil.domain.listing.repository;

import java.math.BigDecimal;

public record RegionCluster(Long regionId, long count, Double avgLat, Double avgLng) {

    public BigDecimal lat() {
        return avgLat == null ? null : BigDecimal.valueOf(avgLat);
    }

    public BigDecimal lng() {
        return avgLng == null ? null : BigDecimal.valueOf(avgLng);
    }
}
