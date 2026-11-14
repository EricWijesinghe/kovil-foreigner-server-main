package com.kovil.domain.community.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        게시판 저장. **글이 하나라도 있으면 `isAnonymous`를 바꿀 수 없다**(`CATEGORY_HAS_POSTS` 409) —
        익명 → 비익명 전환은 과거 익명 글의 작성자를 소급 노출시키는 사고다.
        """)
public record AdminCategoryRequest(
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String code,
        Boolean isAnonymous,
        Integer sortOrder,
        Boolean active,
        List<TranslationEntry> translations
) {
    // springdoc은 **중첩 record의 홑이름**으로 스키마를 만든다 — 같은 이름이 여러 도메인에 있으면
    // 하나로 붕괴해 생성 계약이 거짓을 말한다(실제로 세 화면에서 필드가 어긋났다).
    // 자바 타입 이름은 그대로 두고 스키마 이름만 도메인별로 못 박는다.
    @Schema(name = "CommunityCategoryTranslationEntry")
    public record TranslationEntry(
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String languageCode,
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String name
    ) {
    }
}
