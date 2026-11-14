package com.kovil.infra.openai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiUrl,
        String apiKey,
        String model,
        Integer maxCompletionTokens,
        String reasoningEffort
) {
}
