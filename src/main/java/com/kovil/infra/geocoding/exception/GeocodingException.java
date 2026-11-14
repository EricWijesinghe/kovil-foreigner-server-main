package com.kovil.infra.geocoding.exception;

import com.kovil.common.exception.CustomException;

public class GeocodingException extends CustomException {

    public GeocodingException(GeocodingErrorCode errorCode) {
        super(errorCode);
    }
}
