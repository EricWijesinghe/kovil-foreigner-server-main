package com.kovil.infra.gemini.translation.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// Gemini generateContent 요청. 번역에 필요한 최소 모양만 담는다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiGenerateRequest(
        List<Content> contents,
        SystemInstruction systemInstruction,
        GenerationConfig generationConfig) {

    public record Content(List<Part> parts) {}

    public record Part(String text) {}

    public record SystemInstruction(List<Part> parts) {}

    /*
     * ★ temperature 를 0 으로 둔다. 번역은 창작이 아니라 **같은 입력에 같은 출력**이어야
     *   캐시가 의미를 갖는다. 값이 흔들리면 같은 매물을 두 사람이 다르게 읽는다.
     */
    public record GenerationConfig(double temperature) {}

    public static GeminiGenerateRequest of(String systemPrompt, String userText) {
        return new GeminiGenerateRequest(
                List.of(new Content(List.of(new Part(userText)))),
                new SystemInstruction(List.of(new Part(systemPrompt))),
                new GenerationConfig(0d));
    }
}
