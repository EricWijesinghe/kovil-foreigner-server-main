package com.kovil.infra.llm.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LlmErrorCode implements ErrorCode {

    LLM_PROVIDER_ERROR("Could not analyze the document. Enter the details manually.",
            HttpStatus.BAD_GATEWAY),
    LLM_RESPONSE_UNPARSABLE("Could not read the analysis result. Enter the details manually.",
            HttpStatus.BAD_GATEWAY),
    // 크리덴셜 문제와 쿼터 소진을 갈라 둔다 — 대응이 정반대다.
    LLM_UNAUTHORIZED("Document analysis is not available right now.", HttpStatus.BAD_GATEWAY),
    LLM_RATE_LIMITED("Too many analyses right now. Try again later or enter the details manually.",
            HttpStatus.BAD_GATEWAY);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
