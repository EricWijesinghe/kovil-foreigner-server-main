package com.kovil.domain.listing.enums;

// 임대인이 정확한 층 노출을 꺼릴 때 쓰는 **표시용** 값이다.
//
// ★ 값이 있으면 화면에 '중층'으로 찍고 **응답에서 floor 를 뺀다.** 저장은 둘 다 하는데,
//   필터가 정확하려면 서버는 실제 층을 알아야 하기 때문이다. 중개사가 숨기기로 한 것을
//   우리가 흘리면 안 된다 — DB 가 못 막으므로 응답 조립과 테스트로 막는다.
public enum FloorBand {
    LOW,
    MID,
    HIGH
}
