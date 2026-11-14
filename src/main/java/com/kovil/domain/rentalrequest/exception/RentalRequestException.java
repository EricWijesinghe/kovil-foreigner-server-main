package com.kovil.domain.rentalrequest.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class RentalRequestException extends CustomException {

    public RentalRequestException(ErrorCode errorCode) {
        super(errorCode);
    }
}
