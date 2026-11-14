package com.kovil.domain.partner.enums;

/*
 * 심사 화면 "같은 등록번호의 다른 신청" 한 줄의 종류.
 *   · DIVISION_APPLICATION — 그 번호로 낸 부문 신청(첫 심사 대기 · 반려), 또는 그 번호를 잡고 있는 승인 · 정지된 사무소.
 *   · OFFICE_CHANGE_REQUEST — 승인된 다른 사무소가 그 번호로 옮기려고 낸 대기 중 변경 신청.
 */
public enum SameRegNoApplicationKind {
    DIVISION_APPLICATION,
    OFFICE_CHANGE_REQUEST
}
