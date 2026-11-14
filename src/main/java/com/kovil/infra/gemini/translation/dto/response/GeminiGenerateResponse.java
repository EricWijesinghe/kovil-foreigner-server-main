package com.kovil.infra.gemini.translation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateResponse(List<Candidate> candidates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(Content content, String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {}

    /*
     * 첫 후보의 본문. 응답 모양이 조금만 달라도 NPE 가 나므로 단계마다 막는다 —
     * 번역 실패는 원문 폴백으로 흡수되지만 **NPE 는 500 이 되어 매물 조회를 통째로 막는다.**
     */
    public String firstText() {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Content content = candidates.get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return null;
        }
        return content.parts().get(0).text();
    }
}
