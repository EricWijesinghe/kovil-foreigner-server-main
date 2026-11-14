package com.kovil.domain.auth.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class SignUpException extends CustomException {
    public SignUpException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SignUpException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
