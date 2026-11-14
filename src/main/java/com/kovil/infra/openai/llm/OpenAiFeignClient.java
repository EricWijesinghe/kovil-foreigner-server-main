package com.kovil.infra.openai.llm;

import com.kovil.infra.openai.llm.dto.request.OpenAiChatRequest;
import com.kovil.infra.openai.llm.dto.response.OpenAiChatResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "openAiClient",
        url = "${openai.api-url:https://api.openai.com}",
        configuration = OpenAiFeignConfig.class
)
public interface OpenAiFeignClient {

    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    OpenAiChatResponse chat(@RequestBody OpenAiChatRequest request);
}
