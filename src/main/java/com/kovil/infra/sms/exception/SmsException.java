package com.kovil.infra.sms.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class SmsException extends CustomException {
    public SmsException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SmsException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
