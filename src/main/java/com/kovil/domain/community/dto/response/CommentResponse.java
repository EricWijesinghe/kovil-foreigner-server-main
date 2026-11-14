package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

// INV-10 — 익명 응답에 담기는 작성자 축은 anonymousNo · isAuthor · isMine 셋뿐이다(FR-703).
@Schema(description = """
        댓글. 최상위 작성순, 그 아래 대댓글 작성순.

        **삭제됐지만 대댓글이 있는 댓글은 자리(placeholder)로 남는다** —
        `deleted=true`, `body`·`anonymousNo`는 null. 대댓글 없는 삭제는 목록에서 빠진다.
        """)
public record CommentResponse(
        String commentUuid,
        @Schema(description = "별칭 번호. 0 = 글쓴이. 삭제 자리면 null") Short anonymousNo,
        @Schema(description = "글쓴이의 댓글인가") boolean isAuthor,
        @Schema(description = "내 댓글인가") boolean isMine,
        @Schema(description = "삭제 자리면 null") String body,
        int likeCount,
        @Schema(description = "내가 좋아요를 눌렀는가") boolean likedByMe,
        boolean deleted,
        LocalDateTime createdAt,
        List<CommentResponse> children
) {
}
