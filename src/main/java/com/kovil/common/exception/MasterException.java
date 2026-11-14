package com.kovil.common.exception;

public class MasterException extends CustomException {

    public MasterException(ErrorCode errorCode) {
        super(errorCode);
    }
}
