package com.kovil.domain.rentalrequest.enums;

// 진행 중 요청이 저절로 대기로 돌아간 원인 — 맡을 사람이 사라졌다. 잡기 이력의 대기 복귀(RETURNED)에만 붙는다.
// ★ 이름이 곧 저장값이다(rental_request_claim_events.return_cause ENUM).
public enum RentalRequestReturnCause {

    // 사무소 대표자가 탈퇴했다
    REPRESENTATIVE_WITHDRAWN,

    // 사무소의 공인중개사 부문이 정지됐다
    DIVISION_SUSPENDED,

    // 사무소 대표자의 계정이 정지됐다
    ACCOUNT_SUSPENDED
}
