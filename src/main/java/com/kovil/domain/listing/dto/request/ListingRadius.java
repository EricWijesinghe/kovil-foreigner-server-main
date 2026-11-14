package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.exception.ListingException;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_RADIUS_INVALID;

public record ListingRadius(
        BigDecimal lat,
        BigDecimal lng,
        int meters
) {

    public static final int MIN_METERS = 100;
    public static final int MAX_METERS = 3_000;

    private static final double METERS_PER_DEGREE = 111_195.08;
    private static final int MAX_SCALE = 15;
    private static final BigDecimal LAT_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LNG_LIMIT = BigDecimal.valueOf(180);

    public static ListingRadius ofNullable(BigDecimal lat, BigDecimal lng, Integer meters) {
        if (lat == null && lng == null && meters == null) {
            return null;
        }

        if (lat == null || lng == null || meters == null ||
                meters < MIN_METERS || meters > MAX_METERS
                || !sane(lat, LAT_LIMIT) || !sane(lng, LNG_LIMIT)
        ) {
            throw new ListingException(LISTING_RADIUS_INVALID);
        }

        return new ListingRadius(
                lat.setScale(7, RoundingMode.HALF_UP),
                lng.setScale(7, RoundingMode.HALF_UP),
                meters
        );
    }

    public BigDecimal minLat() {
        return lat.subtract(latDelta());
    }

    public BigDecimal maxLat() {
        return lat.add(latDelta());
    }

    public BigDecimal minLng() {
        return lng.subtract(lngDelta());
    }

    public BigDecimal maxLng() {
        return lng.add(lngDelta());
    }

    public BigDecimal lngScaleSq() {
        double cos = cosLat();
        return BigDecimal.valueOf(cos * cos).setScale(12, RoundingMode.HALF_UP);
    }

    public BigDecimal radiusDegSq() {
        double degrees = meters / METERS_PER_DEGREE;
        return BigDecimal.valueOf(degrees * degrees).setScale(18, RoundingMode.HALF_UP);
    }

    private BigDecimal latDelta() {
        return BigDecimal.valueOf(meters / METERS_PER_DEGREE).setScale(7, RoundingMode.UP);
    }

    private BigDecimal lngDelta() {
        double cos = cosLat();
        double degrees = cos < 1e-6 ? 180 : Math.min(180, meters / (METERS_PER_DEGREE * cos));
        return BigDecimal.valueOf(degrees).setScale(7, RoundingMode.UP);
    }

    private double cosLat() {
        return Math.cos(Math.toRadians(lat.doubleValue()));
    }

    private static boolean sane(BigDecimal value, BigDecimal limit) {
        return value.scale() <= MAX_SCALE && value.abs().compareTo(limit) <= 0;
    }

}
