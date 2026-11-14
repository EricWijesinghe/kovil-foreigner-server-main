package com.kovil.domain.community.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        글 수정 — 본인만. 태그·이미지는 **보낸 목록으로 통째로 교체**된다.
        카테고리는 옮길 수 없다.
        """)
public record PostUpdateRequest(

        @Schema(description = "제목 (150자)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("POST_TITLE_REQUIRED")
        @SizeWithErrorCode(value = "POST_TITLE_REQUIRED", min = 1, max = 150)
        String title,

        @Schema(description = "본문 (5000자)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("POST_BODY_REQUIRED")
        @SizeWithErrorCode(value = "POST_BODY_REQUIRED", min = 1, max = 5000)
        String body,

        @Schema(description = "태그 — 전체 교체")
        List<String> tags,

        @Schema(description = "이미지 URL — 전체 교체")
        List<String> imageUrls
) {
}
