package com.kovil.infra.gemini.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "translation.gemini")
public record GeminiProperties(String apiKey, String model, int timeoutSeconds) {

    public GeminiProperties {
        model = (model == null || model.isBlank()) ? "gemini-3.1-flash-lite" : model;
        timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
    }
}
