package com.kovil.domain.community.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "댓글 수정 — 본인만.")
public record CommentUpdateRequest(

        @Schema(description = "본문 (1000자)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("COMMENT_BODY_REQUIRED")
        @SizeWithErrorCode(value = "COMMENT_BODY_REQUIRED", min = 1, max = 1000)
        String body
) {
}
