package com.kovil.domain.verification.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "체류 신분 저장. 코드는 만들 때만 정한다. 번역은 보낸 목록으로 통째로 교체된다.")
public record AdminResidentStatusRequest(
        @Schema(example = "STUDENT")
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND")
        String code,
        String iconKey,
        Integer sortOrder,
        Boolean active,
        @Schema(description = "언어별 이름·설명") List<TranslationEntry> translations
) {
    // springdoc은 **중첩 record의 홑이름**으로 스키마를 만든다 — 같은 이름이 여러 도메인에 있으면
    // 하나로 붕괴해 생성 계약이 거짓을 말한다(실제로 세 화면에서 필드가 어긋났다).
    // 자바 타입 이름은 그대로 두고 스키마 이름만 도메인별로 못 박는다.
    @Schema(name = "ResidentStatusTranslationEntry")
    public record TranslationEntry(
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String languageCode,
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String name,
            String description
    ) {
    }
}
