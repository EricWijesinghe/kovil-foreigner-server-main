package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "게시판 카테고리. `name`은 요청 언어로 번역된다(요청 언어 → en → code 폴백).")
public record CommunityCategoryResponse(
        String uuid,
        @Schema(description = "기계 축 — 글 작성·목록 필터에 이 값을 쓴다") String code,
        String name,
        @Schema(description = "true면 익명 게시판 — 작성자 정보가 응답에 실리지 않는다") boolean isAnonymous
) {
}
