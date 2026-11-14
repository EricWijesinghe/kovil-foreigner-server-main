package com.kovil.infra.oauth;

import com.kovil.common.exception.CommonErrorCode;
import com.kovil.domain.auth.exception.OAuthException;

import java.util.Locale;

// 되돌림 페이지가 code · state 를 넘길 곳. 주소는 요청에서 받지 않고 이 이름만 받는다.
// 실제 주소는 서버 설정(앱 스킴 · 웹 주소)에서 고르므로 남의 사이트로 돌려보낼 수 없다.
public enum OAuthReturnTarget {
    APP,
    WEB;

    // 비우면 APP 이다. 이 칸이 생기기 전의 앱이 그대로 동작해야 한다.
    public static OAuthReturnTarget from(String value) {
        if (value == null || value.isBlank()) {
            return APP;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OAuthException(CommonErrorCode.INVALID_REQUEST);
        }
    }
}
