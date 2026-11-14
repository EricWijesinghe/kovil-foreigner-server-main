package com.kovil.domain.auth.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class PhoneVerificationException extends CustomException {
    public PhoneVerificationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public PhoneVerificationException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
