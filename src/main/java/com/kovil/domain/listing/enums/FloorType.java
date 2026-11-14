package com.kovil.domain.listing.enums;

// ★ 반지하·옥탑은 **층수로 표현할 수 없다.** 다방 필터의 실제 축이 이것이다
//   (roomFloorList: GROUND_FIRST | GROUND_SECOND_OVER | SEMI_BASEMENT | ROOFTOP).
//   지상 1층 / 2층 이상은 floor 값으로 계산되므로 여기 두지 않는다.
public enum FloorType {
    GROUND,
    SEMI_BASEMENT,
    ROOFTOP
}
