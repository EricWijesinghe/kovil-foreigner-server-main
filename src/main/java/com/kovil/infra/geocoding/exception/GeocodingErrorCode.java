package com.kovil.infra.geocoding.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GeocodingErrorCode implements ErrorCode {

    // 401·403을 일반 오류와 뭉치면 "크리덴셜이 틀렸다"를 "검색이 안 된다"로 오진하게 된다.
    GEOCODING_UNAUTHORIZED("Address search is not available right now.", HttpStatus.BAD_GATEWAY),
    // 429는 무료 한도 소진 또는 Application의 API 미선택 — 운영자가 콘솔을 봐야 하는 신호다.
    GEOCODING_QUOTA_EXCEEDED("Address search is temporarily unavailable.", HttpStatus.BAD_GATEWAY),
    GEOCODING_PROVIDER_ERROR("Address search failed. Try again.", HttpStatus.BAD_GATEWAY);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
