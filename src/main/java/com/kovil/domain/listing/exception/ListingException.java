package com.kovil.domain.listing.exception;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;

public class ListingException extends CustomException {

    public ListingException(ErrorCode errorCode) {
        super(errorCode);
    }
}
