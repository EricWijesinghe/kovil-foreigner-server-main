package com.kovil.domain.partner.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class PartnerException extends CustomException {

    public PartnerException(ErrorCode errorCode) {
        super(errorCode);
    }
}
