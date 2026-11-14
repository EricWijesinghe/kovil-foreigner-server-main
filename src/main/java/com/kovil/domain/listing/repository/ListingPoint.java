package com.kovil.domain.listing.repository;

import java.math.BigDecimal;

public record ListingPoint(Long id, BigDecimal lat, BigDecimal lng) {
}
