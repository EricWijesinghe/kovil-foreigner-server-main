package com.kovil.domain.file.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class FileException extends CustomException {
    public FileException(ErrorCode errorCode) {
        super(errorCode);
    }

    public FileException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
