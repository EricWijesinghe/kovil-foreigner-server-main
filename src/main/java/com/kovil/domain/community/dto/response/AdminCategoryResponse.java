package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = """
        게시판 (관리자 뷰 — 비활성 포함).

        `hasPosts`가 true면 **익명 설정을 바꿀 수 없다**(INV-12) — 토글을 미리 잠그고
        이유를 표시할 것. 누르고 나서 `CATEGORY_HAS_POSTS` 409를 받는 흐름을 만들지 않기 위해 있는 값이다.
        """)
public record AdminCategoryResponse(
        Long id, String uuid, String code, Boolean isAnonymous,
        Integer sortOrder, Boolean active,
        @Schema(description = "글이 하나라도 있는가(삭제된 글 포함). true면 익명 설정 불변",
                example = "true") boolean hasPosts,
        Map<String, String> names
) {
}
