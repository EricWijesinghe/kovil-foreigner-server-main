package com.kovil.infra.geocoding;

import java.util.List;

public record GeocodedPage(long totalCount, int page, List<GeocodedAddress> addresses) {
}
