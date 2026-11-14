package com.kovil.domain.community.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        댓글 작성. `parentCommentUuid`를 채우면 대댓글이다 — **깊이는 2까지**,
        대댓글에 다시 달면 `COMMENT_DEPTH_EXCEEDED`(400).
        """)
public record CommentCreateRequest(

        @Schema(description = "본문 (1000자)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("COMMENT_BODY_REQUIRED")
        @SizeWithErrorCode(value = "COMMENT_BODY_REQUIRED", min = 1, max = 1000)
        String body,

        @Schema(description = "부모 댓글 uuid — 비우면 최상위 댓글")
        String parentCommentUuid
) {
}
