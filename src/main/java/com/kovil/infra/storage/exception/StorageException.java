package com.kovil.infra.storage.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class StorageException extends CustomException {
    public StorageException(ErrorCode errorCode) {
        super(errorCode);
    }

    public StorageException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
