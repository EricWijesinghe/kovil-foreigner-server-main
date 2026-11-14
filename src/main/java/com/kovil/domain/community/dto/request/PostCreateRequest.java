package com.kovil.domain.community.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        글 작성. 카테고리는 `code`로 지목한다(카테고리 목록 API의 `code`).

        언어는 보내지 않는다 — **작성 시점의 내 언어 설정이 스냅샷**된다(FR-707).
        태그는 글당 10개·각 20자, 이미지는 5장까지. `imageUrls[0]`이 목록 썸네일이다.
        """)
public record PostCreateRequest(

        @Schema(description = "카테고리 코드", example = "HOUSING", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("CATEGORY_NOT_FOUND")
        String categoryCode,

        @Schema(description = "제목 (150자)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("POST_TITLE_REQUIRED")
        @SizeWithErrorCode(value = "POST_TITLE_REQUIRED", min = 1, max = 150)
        String title,

        @Schema(description = "본문 (5000자, 줄바꿈 보존)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("POST_BODY_REQUIRED")
        @SizeWithErrorCode(value = "POST_BODY_REQUIRED", min = 1, max = 5000)
        String body,

        @Schema(description = "태그 (10개, 각 20자, # 제외)")
        List<String> tags,

        @Schema(description = "이미지 URL (5장). 파일 API에 COMMUNITY_IMAGE 용도로 올린 공개 URL")
        List<String> imageUrls
) {
}
