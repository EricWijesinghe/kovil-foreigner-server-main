package com.kovil.infra.naver.ocr.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClovaOcrResponse(
        List<Image> images
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(String inferResult, String message, List<Field> fields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String inferText, Double inferConfidence, boolean lineBreak) {
    }
}
