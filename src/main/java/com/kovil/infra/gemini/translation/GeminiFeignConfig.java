package com.kovil.infra.gemini.translation;

import feign.Request;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class GeminiFeignConfig {

    private final GeminiProperties properties;

    @Bean
    public Request.Options geminiRequestOptions() {
        return new Request.Options(
                5, TimeUnit.SECONDS,
                properties.timeoutSeconds(), TimeUnit.SECONDS,
                true);
    }

    @Bean
    public ErrorDecoder geminiErrorDecoder() {
        return new GeminiErrorDecoder();
    }
}
