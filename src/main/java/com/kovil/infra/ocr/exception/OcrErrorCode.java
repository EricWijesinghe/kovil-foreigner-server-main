package com.kovil.infra.ocr.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OcrErrorCode implements ErrorCode {

    OCR_NO_FILE("Attach at least one page of the contract.", HttpStatus.BAD_REQUEST),
    OCR_TOO_MANY_PAGES("Too many pages.", HttpStatus.BAD_REQUEST),
    OCR_FILE_TOO_LARGE("The file is too large.", HttpStatus.BAD_REQUEST),
    OCR_UNSUPPORTED_FILE_TYPE("That file type is not supported.", HttpStatus.BAD_REQUEST),
    OCR_PROVIDER_ERROR("Could not read the document. Enter the details manually.",
            HttpStatus.BAD_GATEWAY),
    // 크리덴셜 문제를 파일 문제로 오진하지 않기 위해 갈라 둔다.
    OCR_UNAUTHORIZED("Document reading is not available right now.", HttpStatus.BAD_GATEWAY);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
