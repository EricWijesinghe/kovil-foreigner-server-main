package com.kovil.domain.file.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FileErrorCode implements ErrorCode {

    FILE_PURPOSE_NULL("FILE_PURPOSE_NULL", "파일 용도는 필수입니다.", HttpStatus.BAD_REQUEST),
    INVALID_FILE_PURPOSE("INVALID_FILE_PURPOSE", "올바르지 않은 파일 용도입니다.", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_CONTENT_TYPE(
            "UNSUPPORTED_CONTENT_TYPE", "지원하지 않는 파일 형식입니다.", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE("FILE_TOO_LARGE", "파일 용량이 너무 큽니다.", HttpStatus.BAD_REQUEST),
    INVALID_TEMP_KEY("INVALID_TEMP_KEY", "올바르지 않은 업로드 키입니다.", HttpStatus.BAD_REQUEST),
    UPLOADED_FILE_NOT_FOUND(
            "UPLOADED_FILE_NOT_FOUND", "업로드된 파일을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    FILE_NOT_FOUND("FILE_NOT_FOUND", "파일을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
