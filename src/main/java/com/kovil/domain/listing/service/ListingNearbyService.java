package com.kovil.domain.listing.service;

import tools.jackson.core.JacksonException;
import com.kovil.domain.listing.dto.response.ListingNearbyResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.NearbyCategory;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.infra.nearby.NearbyPlaceClient;
import com.kovil.infra.nearby.NearbySearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingNearbyService {

    private static final List<ListingStatus> VISIBLE = List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);
    private static final String KEY_PREFIX = "listing:nearby:";

    private final ListingRepository listingRepository;
    private final NearbyPlaceClient nearbyPlaceClient;
    private final ListingNearbyProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public ListingNearbyResponse nearby(String listingUuid) {
        Listing listing = listingRepository.findPublicByUuid(listingUuid, VISIBLE)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));

        String key = KEY_PREFIX + listing.getId() + ":" + listing.getDisplayLat().toPlainString()
                + ":" + listing.getDisplayLng().toPlainString();

        ListingNearbyResponse cached = readCache(key);

        if (cached != null) {
            return cached;
        }

        ListingNearbyResponse fresh = search(listing);

        if (fresh.status() == ListingNearbyResponse.Status.OK) {
            writeCache(key, fresh);
        }

        return fresh;
    }

    private ListingNearbyResponse search(Listing listing) {
        List<ListingNearbyResponse.Category> categories = new ArrayList<>();
        try {
            for (NearbyCategory category : NearbyCategory.values()) {
                NearbySearchResult result = nearbyPlaceClient.search(category.name(),
                        listing.getDisplayLat(), listing.getDisplayLng(),
                        properties.radius(), properties.nearestSize());

                categories.add(new ListingNearbyResponse.Category(category, result.totalCount(), result.places().stream()
                        .map(p -> new ListingNearbyResponse.Place(p.name(), p.lat(), p.lng())).toList()));
            }
        } catch (RuntimeException e) {
            log.warn("[nearby] 주변시설 조회 실패 — UNAVAILABLE 로 응답한다. listingId={} cause={}",
                    listing.getId(), e.toString());
            return ListingNearbyResponse.unavailable(properties.radius());
        }

        return new ListingNearbyResponse(ListingNearbyResponse.Status.OK, properties.radius(), categories);
    }

    private ListingNearbyResponse readCache(String key) {
        if (!cacheEnabled()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : jsonMapper.readValue(json, ListingNearbyResponse.class);
        } catch (DataAccessException | JacksonException e) {
            log.warn("[nearby] 캐시 읽기 실패 — 직접 조회한다. cause={}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, ListingNearbyResponse response) {
        if (!cacheEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(response), properties.cacheTtl());
        } catch (DataAccessException | JacksonException e) {
            log.warn("[nearby] 캐시 저장 실패 — 다음 요청이 다시 조회한다. cause={}", e.getMessage());
        }
    }

    private boolean cacheEnabled() {
        return properties.cacheTtl() != null
                && !properties.cacheTtl().isZero() && !properties.cacheTtl().isNegative();
    }
}