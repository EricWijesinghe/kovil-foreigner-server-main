package com.kovil.infra.llm.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class LlmException extends CustomException {

    public LlmException(ErrorCode errorCode) {
        super(errorCode);
    }
}
