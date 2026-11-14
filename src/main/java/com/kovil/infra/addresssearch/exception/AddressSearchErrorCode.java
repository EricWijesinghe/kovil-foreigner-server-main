package com.kovil.infra.addresssearch.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AddressSearchErrorCode implements ErrorCode {

    // 행정안전부 점검 · 장애 · 키 만료 · 연결 실패. 사용자가 할 일은 "잠시 뒤 다시"라 503(SMS 실패와 같은 축).
    // 검색어가 잘못된 것(한 글자 · 특수문자만 등)은 이 코드가 아니라 빈 목록이다.
    ADDRESS_SEARCH_UNAVAILABLE("Address search is not available right now. Try again later.",
            HttpStatus.SERVICE_UNAVAILABLE);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
