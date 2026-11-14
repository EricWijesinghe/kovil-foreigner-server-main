package com.kovil.infra.geocoding;

// 벤더 중립 포트. 구현체는 infra/{provider}/geocoding에 두고 geocoding.provider로 교체한다(fake | naver | kakao).
public interface GeocodingClient {

    /** page는 1부터 — 벤더 규약을 그대로 따른다. 0기반과의 변환은 부르는 쪽(AddressSearchService)이 한다. */
    GeocodedPage search(String query, int page, int count);
}
