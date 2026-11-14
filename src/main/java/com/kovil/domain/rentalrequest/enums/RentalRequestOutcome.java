package com.kovil.domain.rentalrequest.enums;

// 맡은 사무소가 요청을 종료할 때 한 번 고르는 결과. 성사율(사업 지표)이 남는 유일한 자리다.
// ★ 유학생 응답에는 싣지 않는다 — 유학생 화면은 "종료"만 그린다.
// ★ 이름이 곧 저장값이다(rental_requests.outcome ENUM).
public enum RentalRequestOutcome {

    // 방을 구했다
    FOUND,

    // 진행하지 않음
    NOT_PROCEEDED
}
