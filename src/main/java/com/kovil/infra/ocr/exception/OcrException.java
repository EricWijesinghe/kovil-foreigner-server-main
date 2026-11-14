package com.kovil.infra.ocr.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class OcrException extends CustomException {

    public OcrException(ErrorCode errorCode) {
        super(errorCode);
    }
}
