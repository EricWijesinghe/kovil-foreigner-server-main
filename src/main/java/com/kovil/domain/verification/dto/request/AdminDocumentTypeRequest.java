package com.kovil.domain.verification.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.RangeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "문서 유형 저장. `isSensitive`가 켜지면 공개 URL이 절대 만들어지지 않는다(INV-08).")
public record AdminDocumentTypeRequest(
        @Schema(example = "PASSPORT")
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND")
        String code,
        Boolean isSensitive,
        // 컬럼이 TINYINT(최대 127)다 — 무검증 Integer는 저장 시 500으로 죽는다(AUDIT). 상한은
        // 실무 여유를 두고 100으로 자른다(문서 슬롯이 세 자리가 되는 순간 화면 설계가 먼저 죽는다).
        @RangeWithErrorCode(
                value = "INVALID_REQUEST", min = 1, max = 100)
        Integer maxFiles,
        Integer sortOrder,
        Boolean active,
        List<TranslationEntry> translations
) {
    // springdoc은 **중첩 record의 홑이름**으로 스키마를 만든다 — 같은 이름이 여러 도메인에 있으면
    // 하나로 붕괴해 생성 계약이 거짓을 말한다(실제로 세 화면에서 필드가 어긋났다).
    // 자바 타입 이름은 그대로 두고 스키마 이름만 도메인별로 못 박는다.
    @Schema(name = "DocumentTypeTranslationEntry")
    public record TranslationEntry(
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String languageCode,
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String name,
            @Schema(description = "업로드 화면의 안내 문구") String guideNote
    ) {
    }
}
