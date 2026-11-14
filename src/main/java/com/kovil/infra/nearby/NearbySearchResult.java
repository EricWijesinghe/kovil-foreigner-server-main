package com.kovil.infra.nearby;

import java.math.BigDecimal;
import java.util.List;

public record NearbySearchResult(
        long totalCount,
        List<Place> places
) {
    public record Place(
            String name,
            BigDecimal lat,
            BigDecimal lng) {
    }
}
