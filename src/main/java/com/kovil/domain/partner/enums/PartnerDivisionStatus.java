package com.kovil.domain.partner.enums;

/*
 * 부문의 심사 상태. **승인 · 정지의 축은 여기 하나다** — 중개사무소에는 상태 칼럼이 없다.
 * 상태가 두 곳에 있으면 "부문은 승인, 사무소는 대기" 같은 어긋난 조합이 생긴다.
 *
 * 전이:
 *   PENDING(제출 전 · 심사 대기) ─승인→ APPROVED ─정지→ SUSPENDED ─복구→ APPROVED
 *          └─반려→ REJECTED ─고쳐 다시 냄→ PENDING
 *   PENDING ─회원 탈퇴→ REJECTED(심사자 없음)
 *
 * ★ APPROVED 는 PENDING 으로 돌아가지 않는다(PO 확정 2026-10-07). 승인 뒤 사무소 정보를 바꾸면 부문은 그대로 두고
 *   변경 신청(OfficeChangeRequestStatus)으로 받는다 — 심사 동안 매물 공개 · 기능이 멈추지 않게.
 */
public enum PartnerDivisionStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED
}
