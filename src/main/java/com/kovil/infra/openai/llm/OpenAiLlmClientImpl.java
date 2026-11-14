package com.kovil.infra.openai.llm;

import com.kovil.infra.llm.LlmClient;
import com.kovil.infra.llm.exception.LlmException;
import com.kovil.infra.openai.llm.dto.request.OpenAiChatRequest;
import com.kovil.infra.openai.llm.dto.response.OpenAiChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.kovil.infra.llm.exception.LlmErrorCode.LLM_PROVIDER_ERROR;

// ⚠ 한 번도 실제로 호출된 적이 없다 — API 키가 없어 llm.provider=fake가 기본이다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiLlmClientImpl implements LlmClient {

    private static final String ROLE_USER = "user";

    private final OpenAiFeignClient client;
    private final OpenAiProperties properties;

    @Override
    public String complete(String prompt) {
        OpenAiChatRequest request = new OpenAiChatRequest(
                properties.model(),
                List.of(new OpenAiChatRequest.Message(ROLE_USER, prompt)),
                properties.maxCompletionTokens(),
                properties.reasoningEffort(),
                OpenAiChatRequest.ResponseFormat.jsonObject());

        return extractContent(client.chat(request));
    }

    private String extractContent(OpenAiChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new LlmException(LLM_PROVIDER_ERROR);
        }
        OpenAiChatResponse.Message message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new LlmException(LLM_PROVIDER_ERROR);
        }
        return message.content();
    }
}
