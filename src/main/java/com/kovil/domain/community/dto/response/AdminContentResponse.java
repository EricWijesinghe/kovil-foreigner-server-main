package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = """
        신고 대상의 **원문**(ADMIN 전용).

        ★ **블라인드·삭제된 대상도 그대로 내려온다** — 유저 조회는 이것들을 감추지만,
        내린 판단을 되돌리려면 관리자는 원문을 다시 볼 수 있어야 한다.

        **작성자 식별자는 없다**(INV-10). 전면 익명은 관리자 화면에서도 유지된다 —
        같은 사람의 다른 글은 `/admin/community/posts/{postUuid}/sibling-posts`로 본다.
        """)
public record AdminContentResponse(

        @Schema(description = "ACTIVE · HIDDEN(블라인드)", example = "HIDDEN") String status,

        @Schema(description = "삭제 시각. 작성자가 지운 글이면 값이 있다.") LocalDateTime deletedAt,

        @Schema(description = "글 원문. 대상이 댓글이면 **댓글이 달린 글**이 여기 온다.")
        PostDetailResponse post,

        @Schema(description = "댓글 원문. 대상이 글이면 null.") CommentResponse comment
) {
}
