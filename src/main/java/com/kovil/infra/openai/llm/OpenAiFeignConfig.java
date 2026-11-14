package com.kovil.infra.openai.llm;

import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;

// @Configuration을 붙이지 않는다 — 붙이면 전역 Feign 설정이 되어 API 키가 다른 클라이언트에도 실린다.
@RequiredArgsConstructor
public class OpenAiFeignConfig {

    private final OpenAiProperties properties;

    @Bean
    public RequestInterceptor openAiAuthInterceptor() {
        return template -> template.header("Authorization", "Bearer " + properties.apiKey());
    }

    @Bean
    public OpenAiErrorDecoder openAiErrorDecoder() {
        return new OpenAiErrorDecoder();
    }
}
