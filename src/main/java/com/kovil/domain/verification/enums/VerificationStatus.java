package com.kovil.domain.verification.enums;

// 전이는 PENDING_REVIEW → APPROVED | REJECTED 단방향이다(FR-206).
// 되돌리는 전이가 없으므로 반려 후 재제출은 수정이 아니라 새 행이다(FR-207).
//
// REVOKED는 유일한 예외다: APPROVED → REVOKED. 승인된 인증의 여권 성명이 그대로 계약
// 명의가 되므로, 오승인을 되돌릴 수단이 없으면 잘못된 이름의 계약이 나간다.
// PENDING_REVIEW로 되돌리지 않는 이유 — 그러면 심사 대기열에 "이미 심사한 건"이 다시 섞이고,
// 무효화라는 사실이 상태에서 사라진다.
public enum VerificationStatus {
    PENDING_REVIEW, APPROVED, REJECTED, REVOKED
}
