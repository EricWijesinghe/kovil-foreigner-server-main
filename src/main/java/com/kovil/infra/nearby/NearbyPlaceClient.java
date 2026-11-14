package com.kovil.infra.nearby;

import java.math.BigDecimal;

public interface NearbyPlaceClient {
    NearbySearchResult search(
            String categoryCode,
            BigDecimal lat,
            BigDecimal lng,
            int radius,
            int size
    );
}
