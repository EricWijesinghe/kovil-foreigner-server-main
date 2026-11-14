package com.kovil.infra.openai.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 키 · 주소는 계약서 파싱과 같은 openai.* 를 쓴다(OPENAI_API_KEY 하나). 번역 몫은 모델 · 생각 정도 · 출력 상한뿐이다.
@ConfigurationProperties(prefix = "translation.openai")
public record OpenAiTranslationProperties(String model, String reasoningEffort, Integer maxCompletionTokens) {

    public OpenAiTranslationProperties {
        model = (model == null || model.isBlank()) ? "gpt-6-luna" : model;
        // 비우면 싣지 않는다 — 모델 기본값(gpt-6-luna 는 medium)으로 생각 토큰이 붙어 출력 요금이 커진다
        reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
        maxCompletionTokens = (maxCompletionTokens == null || maxCompletionTokens <= 0) ? 4000 : maxCompletionTokens;
    }
}
