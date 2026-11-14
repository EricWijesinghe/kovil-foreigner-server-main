package com.kovil.infra.kakao.local;

import com.kovil.infra.kakao.local.dto.response.KakaoCategoryResponse;
import com.kovil.infra.nearby.NearbyPlaceClient;
import com.kovil.infra.nearby.NearbySearchResult;
import com.kovil.infra.nearby.NearbyVendorException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nearby.provider", havingValue = "kakao")
public class KakaoNearbyPlaceClient implements NearbyPlaceClient {

    private static final int MAX_SIZE = 15;

    private final KakaoLocalFeignClient feignClient;

    @Override
    public NearbySearchResult search(String categoryCode, BigDecimal lat, BigDecimal lng, int radius, int size) {
        KakaoCategoryResponse response = feignClient.searchCategory(
                categoryCode, lng.toPlainString(), lat.toPlainString(), radius, Math.min(size, MAX_SIZE), "distance");

        if (response == null || response.documents() == null) {
            throw new NearbyVendorException("kakao local empty body");
        }

        List<NearbySearchResult.Place> places = response.documents().stream()
                .map(d -> new NearbySearchResult.Place(
                        d.placeName(), new BigDecimal(d.latitude()), new BigDecimal(d.longitude())))
                .toList();

        long total = response.meta() == null || response.meta().totalCount() == null ?
                response.documents().size() :
                response.meta().totalCount();

        return new NearbySearchResult(total, places);
    }
}
