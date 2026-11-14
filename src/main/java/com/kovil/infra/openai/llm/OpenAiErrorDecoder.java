package com.kovil.infra.openai.llm;

import com.kovil.infra.llm.exception.LlmException;
import feign.Response;
import feign.codec.ErrorDecoder;

import static com.kovil.infra.llm.exception.LlmErrorCode.LLM_PROVIDER_ERROR;
import static com.kovil.infra.llm.exception.LlmErrorCode.LLM_RATE_LIMITED;
import static com.kovil.infra.llm.exception.LlmErrorCode.LLM_UNAUTHORIZED;

// 429를 일반 오류와 뭉치면 "쿼터 소진"을 "벤더 장애"로 오진한다 — 대응이 정반대다.
public class OpenAiErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return switch (response.status()) {
            case 401, 403 -> new LlmException(LLM_UNAUTHORIZED);
            case 429 -> new LlmException(LLM_RATE_LIMITED);
            default -> new LlmException(LLM_PROVIDER_ERROR);
        };
    }
}
