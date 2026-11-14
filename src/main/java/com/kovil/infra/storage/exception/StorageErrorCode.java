package com.kovil.infra.storage.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StorageErrorCode implements ErrorCode {
    STORAGE_UNAVAILABLE(
            "STORAGE_UNAVAILABLE",
            "파일 저장소가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.SERVICE_UNAVAILABLE
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
