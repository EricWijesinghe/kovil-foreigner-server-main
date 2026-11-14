package com.kovil.domain.rentalrequest.enums;

// 잡기 이력의 사건 — 사무소가 요청을 쥐고 놓는 순간들. 관리자가 "이 요청을 잡았다 놓은 사무소"를 본다.
// ★ 유학생의 수정 · 취소 · 탈퇴는 여기 남기지 않는다 — 사무소가 한 일이 아니고, 요청 행의 상태 · 종료 시각이 이미 그것을 말한다.
// ★ 이름이 곧 저장값이다(rental_request_claim_events.event_type ENUM).
public enum RentalRequestClaimEventType {

    // 사무소가 잡았다(대기 → 진행 중). 같은 사무소가 두 번 누른 멱등 잡기는 남기지 않는다.
    CLAIMED,

    // 맡은 사무소가 스스로 놓았다(진행 중 → 대기).
    RELEASED,

    // 맡을 사람이 사라져 대기로 돌아갔다 — 원인은 RentalRequestReturnCause. 사람이 한 일이 아니라 행위자가 없다.
    RETURNED,

    // 맡은 사무소가 결과를 골라 종료했다.
    CLOSED
}
