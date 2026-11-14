package com.kovil.infra.naver.ocr.dto.request;

import java.util.List;

public record ClovaOcrRequest(
        String version,
        String requestId,
        long timestamp,
        String lang,
        List<Image> images
) {

    public record Image(String format, String name, String data) {
    }
}
