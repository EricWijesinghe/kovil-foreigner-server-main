package com.kovil.infra.openai.llm.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
        String model,
        List<Message> messages,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        @JsonProperty("response_format") ResponseFormat responseFormat
) {

    public record Message(String role, String content) {
    }

    public record ResponseFormat(String type) {

        // JSON 객체를 강제한다 — 프롬프트로만 부탁하면 설명 문장이 섞여 온다.
        public static ResponseFormat jsonObject() {
            return new ResponseFormat("json_object");
        }
    }
}
