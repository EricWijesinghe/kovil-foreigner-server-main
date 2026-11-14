package com.kovil.infra.addresssearch;

import com.kovil.infra.geocoding.GeocodedPage;

// 주소 검색(후보 목록) 포트. 좌표 변환(GeocodingClient)과 갈라 둔다 — 검색은 무료인 행정안전부, 좌표는 고를 때 한 번 네이버(PO 11-02).
// 구현은 address-search.provider 로 고른다(geocoding | juso). page 는 1부터 — GeocodingClient 와 같다.
public interface AddressSearchClient {

    GeocodedPage search(String query, int page, int count);
}
