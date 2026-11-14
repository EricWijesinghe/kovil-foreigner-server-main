package com.kovil.domain.listing.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nearby")
public record ListingNearbyProperties(int radius, int nearestSize, Duration cacheTtl) {
}