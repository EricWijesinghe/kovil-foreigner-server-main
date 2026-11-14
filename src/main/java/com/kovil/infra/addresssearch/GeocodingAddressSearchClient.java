package com.kovil.infra.addresssearch;

import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.geocoding.GeocodingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 기본값 — 지금까지처럼 좌표 벤더(geocoding.provider)로 검색한다. 후보에 좌표가 함께 온다.
// 기본값으로 두는 이유: 이 판을 배포만 해서는 아무것도 바뀌지 않게. 좌표 없는 후보를 다루는 파트너스 앱이 깔린 뒤 juso 로 바꾼다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "address-search.provider", havingValue = "geocoding", matchIfMissing = true)
public class GeocodingAddressSearchClient implements AddressSearchClient {

    private final GeocodingClient geocodingClient;

    @Override
    public GeocodedPage search(String query, int page, int count) {
        return geocodingClient.search(query, page, count);
    }
}
