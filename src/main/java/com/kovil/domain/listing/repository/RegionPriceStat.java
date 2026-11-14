package com.kovil.domain.listing.repository;

public record RegionPriceStat(
        Long regionId,
        long sampleSize,
        Double avgDeposit, Double avgMonthlyRent,
        Double avgLat, Double avgLng
) {
}
