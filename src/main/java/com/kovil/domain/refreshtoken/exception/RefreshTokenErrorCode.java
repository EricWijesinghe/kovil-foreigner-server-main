package com.kovil.domain.refreshtoken.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefreshTokenErrorCode implements ErrorCode {

    // 없음·폐기됨·만료를 하나로 답한다. 구분하면 "그 토큰이 존재하긴 했는가"가 새어 나간다.
    REFRESH_TOKEN_INVALID(
            "REFRESH_TOKEN_INVALID",
            "다시 로그인해 주세요.",
            HttpStatus.UNAUTHORIZED
    ),
    REFRESH_TOKEN_BLANK(
            "REFRESH_TOKEN_BLANK",
            "리프레시 토큰은 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    REFRESH_TOKEN_FORBIDDEN(
            "REFRESH_TOKEN_FORBIDDEN",
            "해당 리프레시 토큰에 대한 권한이 없습니다.",
            HttpStatus.FORBIDDEN
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
