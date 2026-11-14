package com.kovil.domain.rentalrequest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = """
        요청 옵션 (관리자 뷰 — 트리는 parentId로 조립, 비활성 포함).

        `hasAnswers`는 **정보 값**이다(V15부터 아무 조작도 잠그지 않는다) — "진행 중 요청이
        이 질문에 답해 둔 상태"를 관리자에게 알려주는 축. 구조를 바꾸거나 지워도 되지만,
        그 유저가 요청을 수정할 때 다시 답하게 될 수 있음을 화면이 귀띔하는 데 쓴다.
        이름·설명·아이콘·순서·활성은 `hasAnswers`와 무관하게 언제든 바꿀 수 있다.
        """)
public record AdminRequestOptionResponse(
        Long id, Long parentId, String code, String selectionType, String iconKey,
        Integer sortOrder, Boolean active,

        @Schema(description = "질문만: 반드시 답을 받는가") Boolean required,
        @Schema(description = "질문만: 이 항목 id가 선택됐을 때만 노출") Long showWhenOptionId,
        @Schema(description = "항목만: NONE | TEXT | NUMBER | DATE") String extraInputType,
        @Schema(description = "항목만: TEXT 글자 수 상한") Integer extraMaxLength,
        @Schema(description = "항목만: 추가 입력이 필수인가") Boolean extraRequired,

        @Schema(description = "질문만(TEXT): 글자 수 상한") Integer inputMaxLength,
        @Schema(description = "질문만(NUMBER): 하한") Long inputMinNumber,
        @Schema(description = "질문만(NUMBER): 상한") Long inputMaxNumber,
        @Schema(description = "질문만(ADDRESS): 다중 선택 허용") Boolean inputMultiple,
        @Schema(description = "질문만: 표시 단위") String inputUnit,
        @Schema(description = "질문만(TEXT): 여러 줄 입력") Boolean inputMultiline,
        @Schema(description = "질문만(DATE): 오늘 이전 날짜 허용 여부") Boolean inputAllowPast,

        @Schema(description = "진행 중 요청이 이 질문에 답해 둔 상태인가 — 정보 값(V15, 잠그지 않는다)",
                example = "true")
        boolean hasAnswers,

        Map<String, String> names,
        @Schema(description = "언어별 보조 설명") Map<String, String> descriptions
) {
}
