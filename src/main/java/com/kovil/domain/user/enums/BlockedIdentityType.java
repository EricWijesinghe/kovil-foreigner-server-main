package com.kovil.domain.user.enums;

/**
 * 재가입 차단의 축. CI 를 쓰지 않는 우리는 신원 축이 **둘**이다 —
 * 번호(PHONE)와 로그인 수단(LOCAL·소셜). 둘 중 하나만 막으면 다른 쪽으로 들어온다.
 */
public enum BlockedIdentityType {
    PHONE, LOCAL, APPLE, GOOGLE, FACEBOOK;

    /** 로그인 수단 축의 차단 타입. AuthType 과 1:1 이지만 의미가 달라 enum 을 나눠 둔다. */
    public static BlockedIdentityType of(AuthType authType) {
        return valueOf(authType.name());
    }
}
