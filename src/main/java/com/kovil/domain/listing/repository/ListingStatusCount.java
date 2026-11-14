package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.enums.ListingStatus;

public record ListingStatusCount(ListingStatus status, long count) {}