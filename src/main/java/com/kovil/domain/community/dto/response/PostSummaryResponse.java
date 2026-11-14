package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// INV-10 — 작성자 계정 정보(uuid·닉네임·프로필·국적)는 어떤 필드로도 싣지 않는다.
@Schema(description = "글 목록 카드. 작성자 정보는 익명 정책으로 제공되지 않는다.")
public record PostSummaryResponse(
        String postUuid,
        String categoryCode,
        String title,
        @Schema(description = "본문 앞 120자(줄바꿈은 공백으로)") String bodyPreview,
        @Schema(description = "sort_order 0 이미지. 없으면 null") String thumbnailUrl,
        long viewCount,
        int likeCount,
        int commentCount,
        @Schema(description = "작성 시점 언어 스냅샷") String languageCode,

        @Schema(description = """
                작성자 표시 번호(100~999) — 화면엔 "익명 {n}"으로 그린다.
                글 uuid에서 파생한 값이라 **글이 다르면 같은 사람이어도 번호가 다르다**(계정 추적 불가).
                같은 글에서는 목록·상세가 항상 같은 번호다.
                """, example = "125")
        int anonymousTag,
        @Schema(description = "내가 쓴 글인가") boolean isMine,
        @Schema(description = "내가 좋아요를 눌렀는가") boolean likedByMe,
        LocalDateTime createdAt
) {
}
