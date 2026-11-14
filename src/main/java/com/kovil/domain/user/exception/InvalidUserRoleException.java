package com.kovil.domain.user.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class InvalidUserRoleException extends CustomException {

    public InvalidUserRoleException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InvalidUserRoleException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
