package com.kovil.domain.user.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class InvalidUserStatusException extends CustomException {
    public InvalidUserStatusException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InvalidUserStatusException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
