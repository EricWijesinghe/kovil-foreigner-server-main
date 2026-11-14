package com.kovil.domain.refreshtoken.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class RefreshTokenException extends CustomException {
    public RefreshTokenException(ErrorCode errorCode) {
        super(errorCode);
    }

    public RefreshTokenException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
