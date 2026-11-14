package com.kovil.infra.crypto.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class CryptoException extends CustomException {
    public CryptoException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CryptoException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
