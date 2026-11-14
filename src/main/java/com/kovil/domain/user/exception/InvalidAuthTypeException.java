package com.kovil.domain.user.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class InvalidAuthTypeException extends CustomException {
    public InvalidAuthTypeException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InvalidAuthTypeException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
