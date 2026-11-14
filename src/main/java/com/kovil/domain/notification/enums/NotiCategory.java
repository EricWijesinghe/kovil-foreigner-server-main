package com.kovil.domain.notification.enums;

// FR-608 — **CONTRACT는 토글 대상이 아니다.** 월세·계약·인증은 서비스 핵심이라
// 꺼 두면 "몰라서 못 냈다"가 성립해 버린다. 설정 행 자체를 만들지 않는다.
public enum NotiCategory {

    CONTRACT,
    COMMUNITY,
    MARKETING;

    public boolean isToggleable() {
        return this != CONTRACT;
    }
}
