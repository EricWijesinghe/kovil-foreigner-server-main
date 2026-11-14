package com.kovil.domain.notification.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class NotificationException extends CustomException {

    public NotificationException(ErrorCode errorCode) {
        super(errorCode);
    }
}
