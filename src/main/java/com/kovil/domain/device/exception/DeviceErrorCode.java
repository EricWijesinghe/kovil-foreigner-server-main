package com.kovil.domain.device.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DeviceErrorCode implements ErrorCode {

    DEVICE_TOKEN_BLANK(
            "DEVICE_TOKEN_BLANK",
            "디바이스 토큰은 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    INVALID_DEVICE_PLATFORM(
            "INVALID_DEVICE_PLATFORM",
            "올바르지 않은 디바이스 플랫폼입니다.",
            HttpStatus.BAD_REQUEST
    ),
    DEVICE_PLATFORM_NULL(
            "DEVICE_PLATFORM_NULL",
            "디바이스 플랫폼은 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    DEVICE_TOKEN_NOT_FOUND(
            "DEVICE_TOKEN_NOT_FOUND",
            "등록된 디바이스를 찾을 수 없습니다.",
            HttpStatus.NOT_FOUND
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
