package com.kovil.infra.jwt.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum JwtErrorCode implements ErrorCode {

    INVALID_TOKEN(
            "INVALID_TOKEN",
            "올바르지 않은 토큰입니다.",
            HttpStatus.UNAUTHORIZED
    ),
    EXPIRED_TOKEN(
            "EXPIRED_TOKEN",
            "만료된 토큰입니다.",
            HttpStatus.UNAUTHORIZED
    ),
    UNSUPPORTED_TOKEN(
            "UNSUPPORTED_TOKEN",
            "지원하지 않는 토큰 형식입니다.",
            HttpStatus.UNAUTHORIZED
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
