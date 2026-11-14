package com.kovil.domain.notification.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND("Notification not found.", HttpStatus.NOT_FOUND),
    NOTIFICATION_NOT_RETRYABLE("This notification is not retryable.", HttpStatus.CONFLICT),
    // FR-608 — 월세·계약·인증은 서비스 핵심이라 끌 수 없다.
    NOTIFICATION_CATEGORY_NOT_TOGGLEABLE("This notification category cannot be turned off.",
            HttpStatus.BAD_REQUEST);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
