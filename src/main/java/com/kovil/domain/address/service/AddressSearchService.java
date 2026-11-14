package com.kovil.domain.address.service;

import com.kovil.domain.address.dto.response.AddressSearchResponse;
import com.kovil.domain.region.service.AddressRegionResolver;
import com.kovil.infra.addresssearch.AddressSearchClient;
import com.kovil.infra.geocoding.GeocodedPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressSearchService {

    private final AddressSearchClient addressSearchClient;
    private final AddressRegionResolver regionResolver;

    static final int MAX_QUERY_LENGTH = 80;
    // 쪽 번호 상한 — 사람이 끝까지 넘겨도 닿지 않는 값. 넘는 쪽은 벤더를 부르지 않는다(page + 1 넘침 · 벤더 오류 경보 방지).
    static final int MAX_PAGE = 1000;

    // 검색어가 비면 벤더를 부르지 않음(호출당 과금). 페이지는 우리 0기반 ↔ 벤더 1기반.
    public AddressSearchResponse search(String query, int zeroBasedPage, int size) {
        String trimmed = clip(query == null ? "" : query.strip());
        if (trimmed.isEmpty() || zeroBasedPage >= MAX_PAGE) {
            return new AddressSearchResponse(0, zeroBasedPage, List.of());
        }
        GeocodedPage page = addressSearchClient.search(trimmed, zeroBasedPage + 1, size);
        if (page.addresses().isEmpty()) {
            return AddressSearchResponse.of(page, zeroBasedPage, null);
        }
        // 벤더 호출이 먼저, 지역은 그 뒤에 읽음 — 이 경로는 OSIV 아래라 먼저 읽으면 벤더를 기다리는 동안 DB 커넥션을 쥠.
        return AddressSearchResponse.of(page, zeroBasedPage, regionResolver.snapshot());
    }

    // 비로그인 공개 경로라 아주 긴 검색어를 벤더에 그대로 넘기지 않음. 주소는 이 길이를 넘지 않음.
    private static String clip(String query) {
        if (query.codePointCount(0, query.length()) <= MAX_QUERY_LENGTH) {
            return query;
        }
        return query.substring(0, query.offsetByCodePoints(0, MAX_QUERY_LENGTH)).strip();
    }
}
