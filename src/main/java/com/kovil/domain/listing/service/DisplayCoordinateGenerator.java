package com.kovil.domain.listing.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;


@Component
public class DisplayCoordinateGenerator {

    private static final double METERS_PER_DEGREE = 111_320d;
    private static final double OFFSET_METERS = 10d;
    private static final int SCALE = 7;

    private final SecureRandom random = new SecureRandom();

    public BigDecimal[] obfuscate(BigDecimal lat, BigDecimal lng) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = OFFSET_METERS * (0.5 + random.nextDouble() * 0.5);

        double latDelta = (distance * Math.sin(angle)) / METERS_PER_DEGREE;
        double lngDelta = (distance * Math.cos(angle))
                / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat.doubleValue())));

        return new BigDecimal[]{
                lat.add(BigDecimal.valueOf(latDelta)).setScale(SCALE, RoundingMode.HALF_UP),
                lng.add(BigDecimal.valueOf(lngDelta)).setScale(SCALE, RoundingMode.HALF_UP)
        };
    }
}
