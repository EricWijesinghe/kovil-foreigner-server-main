package com.kovil.domain.listing.enums;

// ★ 다섯 개가 전부다. '원룸(분리형)' '오피스텔(분리형,복층)' 의 괄호는 **별도 값이 아니라**
//   Listing 의 isDivision·isDuplex 를 조립해 만든 표시 문자열이다(실측 2026-10-05).
//   30가지 방종류를 만들지 말 것 — 5 × 2 × 2 다.
public enum RoomType {
    ONE_ROOM,
    TWO_ROOM,
    THREE_ROOM,
    OFFICETEL,
    APT
}
