package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

// INV-10 — 작성자 계정 정보는 어떤 필드로도 싣지 않는다. 허용 필드는 anonymousNo·isMine뿐.
@Schema(description = """
        글 상세. **조회수는 즉시 반영되지 않는다** — Redis 버퍼를 배치가 최대 1분 지연으로 가산한다.
        """)
public record PostDetailResponse(
        String postUuid,
        String categoryCode,
        String title,
        String body,
        List<String> imageUrls,
        List<String> tags,
        long viewCount,
        int likeCount,
        int commentCount,
        String languageCode,
        @Schema(description = "글쓴이 별칭 번호 — 항상 0") short anonymousNo,
        @Schema(description = "작성자 표시 번호(100~999) — 목록과 같은 값. \"익명 {n}\"으로 그린다.",
                example = "125")
        int anonymousTag,
        boolean isMine,
        @Schema(description = "내가 좋아요를 눌렀는가") boolean likedByMe,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
