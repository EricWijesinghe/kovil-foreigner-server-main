package com.kovil.domain.listing.enums;

// 「중개대상물의 표시·광고 명시사항 세부기준」이 요구하는 관리비 부과 방식.
// 다방 번들에서 확인한 값 그대로다(실측 2026-10-05).
public enum MaintenanceChargeType {

    FIXED_FEE,      // 정액관리비
    ETC_CHARGE,     // 기타부과
    UNABLE_CHECK;   // 확인불가 — 금액 자리에 숫자 대신 '확인불가'가 찍힌다

    public boolean hasTotalCost() {
        return this != UNABLE_CHECK;
    }
}
