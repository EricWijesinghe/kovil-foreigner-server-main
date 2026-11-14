package com.kovil.domain.auth.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class SignInException extends CustomException {
    public SignInException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SignInException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
