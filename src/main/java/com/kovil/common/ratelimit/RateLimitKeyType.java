package com.kovil.common.ratelimit;

public enum RateLimitKeyType {
    IP,
    USER,
    USER_OR_IP,
    GLOBAL,
    // 로그인하지 않은 호출 전부를 한 키로 셈. 로그인한 호출은 세지 않음 — 비로그인 폭주가 로그인 사용자까지 막지 않게
    GUEST_GLOBAL,
    MANUAL
}
