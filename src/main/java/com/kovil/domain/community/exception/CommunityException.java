package com.kovil.domain.community.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class CommunityException extends CustomException {

    public CommunityException(ErrorCode errorCode) {
        super(errorCode);
    }
}
