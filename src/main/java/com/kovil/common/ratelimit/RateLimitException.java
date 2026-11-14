package com.kovil.common.ratelimit;

import com.kovil.common.exception.CommonErrorCode;
import com.kovil.common.exception.CustomException;
import lombok.Getter;

@Getter
public class RateLimitException extends CustomException {

    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterSeconds) {
        super(CommonErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
