package com.kovil.domain.appversion.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class AppVersionException extends CustomException {
    public AppVersionException(ErrorCode errorCode) {
        super(errorCode);
    }
}
