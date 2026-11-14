package com.kovil.domain.auth.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class OAuthException extends CustomException {
    public OAuthException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OAuthException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
