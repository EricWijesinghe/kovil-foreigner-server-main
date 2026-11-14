package com.kovil.infra.jwt.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class JwtException extends CustomException {

    public JwtException(ErrorCode errorCode) {
        super(errorCode);
    }

    public JwtException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
