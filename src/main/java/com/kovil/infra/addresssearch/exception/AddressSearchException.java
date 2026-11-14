package com.kovil.infra.addresssearch.exception;

import com.kovil.common.exception.CustomException;

public class AddressSearchException extends CustomException {

    public AddressSearchException(AddressSearchErrorCode errorCode) {
        super(errorCode);
    }
}
