package com.kovil.domain.device.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class DeviceException extends CustomException {
    public DeviceException(ErrorCode errorCode) {
        super(errorCode);
    }

    public DeviceException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
