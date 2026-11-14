package com.kovil.domain.file.enums;

import java.util.Map;

// content-type → 확장자. 확장자를 클라이언트 파일명에서 뽑지 않는 이유:
// 파일명은 위조할 수 있고, 확정 키의 확장자가 곧 스토리지가 내려줄 타입의 힌트가 된다.
final class FileContentTypes {

    static final Map<String, String> IMAGE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    static final Map<String, String> DOCUMENT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "application/pdf", "pdf");

    static final Map<String, String> ICON = Map.of(
            "image/png", "png",
            "image/webp", "webp");

    private FileContentTypes() {
    }
}
