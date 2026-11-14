package com.kovil.infra.sms.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SmsErrorCode implements ErrorCode {
    SMS_SEND_FAILED(
            "SMS_SEND_FAILED",
            "인증번호 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.SERVICE_UNAVAILABLE
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
