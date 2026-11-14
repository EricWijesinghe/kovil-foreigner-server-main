package com.kovil.domain.listing.enums;

// 지도 탭. **필터 축이 탭마다 다르다** — 원·투룸만 분리형·복층을 묻고, 아파트는 층·엘리베이터를
// 묻지 않는다(단지 단위라 의미가 없다). 화면이 탭으로 갈리는 이유가 그것이다.
public enum ListingCategory {
    ONE_TWO_ROOM,
    HOUSE_VILLA,
    OFFICETEL,
    APT
}
