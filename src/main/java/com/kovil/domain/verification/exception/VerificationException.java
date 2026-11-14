package com.kovil.domain.verification.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class VerificationException extends CustomException {
    public VerificationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public VerificationException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
