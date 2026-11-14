package com.kovil.common.security.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SecurityErrorCode implements ErrorCode {

    UNAUTHORIZED_ACCESS(
            "UNAUTHORIZED_ACCESS",
            "인증이 필요합니다.",
            HttpStatus.UNAUTHORIZED
    ),
    FORBIDDEN_ACCESS(
            "FORBIDDEN_ACCESS",
            "접근 권한이 없습니다.",
            HttpStatus.FORBIDDEN
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
