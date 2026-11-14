package com.kovil.common.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String getCode();

    // 번역 실패 시의 폴백. 사용자에게 보이는 최종 문구가 아니다 —
    // 해석은 사용자 언어를 아는 계층이 getCode()를 키로 수행한다.
    String getDefaultMessage();

    HttpStatus getHttpStatus();
}
