package com.kovil.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_REQUEST(
            "INVALID_REQUEST",
            "올바르지 않은 요청입니다.",
            HttpStatus.BAD_REQUEST
    ),
    NOT_FOUND(
            "NOT_FOUND",
            "요청하신 정보를 찾을 수 없습니다.",
            HttpStatus.NOT_FOUND
    ),
    CONFLICT(
            "CONFLICT",
            "다른 요청과 충돌했습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.CONFLICT
    ),
    INTERNAL_SERVER_ERROR(
            "INTERNAL_SERVER_ERROR",
            "내부 서버 오류가 발생했습니다.",
            HttpStatus.INTERNAL_SERVER_ERROR
    ),
    // FE 반응 축이 "토스트 + 잠시 후 재시도" 하나이므로 코드도 하나다.
    // 문구는 의도적으로 무정보 — 로그인 차단에도 쓰이므로 계정 존재 여부를 누설하면 안 된다.
    RATE_LIMITED(
            "RATE_LIMITED",
            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.TOO_MANY_REQUESTS
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
