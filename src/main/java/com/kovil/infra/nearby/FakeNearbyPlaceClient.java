package com.kovil.infra.nearby;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "nearby.provider", havingValue = "fake", matchIfMissing = true)
public class FakeNearbyPlaceClient implements NearbyPlaceClient {

    private static final BigDecimal NEAR = new BigDecimal("0.0010000");
    private static final BigDecimal FAR = new BigDecimal("0.0020000");

    @Override
    public NearbySearchResult search(String categoryCode, BigDecimal lat, BigDecimal lng, int radius, int size) {
        log.info("[FakeNearby] category={} — 고정 두 곳으로 응답한다", categoryCode);
        List<NearbySearchResult.Place> places = List.of(
                new NearbySearchResult.Place(categoryCode + " 가까운 곳", lat.add(NEAR), lng),
                new NearbySearchResult.Place(categoryCode + " 먼 곳", lat, lng.add(FAR)));
        return new NearbySearchResult(places.size(), places.subList(0, Math.min(size, places.size())));
    }
}
