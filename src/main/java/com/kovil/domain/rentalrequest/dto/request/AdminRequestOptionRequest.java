package com.kovil.domain.rentalrequest.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import com.kovil.common.validator.annotation.RangeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        요청 옵션 저장. `parentId`가 없으면 **질문(그룹)**, 있으면 그 질문의 **선택지(항목)**다.
        질문의 `selectionType`은 SINGLE | MULTI. 코드·부모는 만들 때만 정한다.

        **★ 아무것도 잠그지 않는다**(V15, PO 확정). 답변이 달린 질문의 구조를 바꿔도 지난
        요청은 스냅샷(V13)이 지킨다. 대가는 하나 — 진행 중 요청의 주인이 수정할 때 바뀐
        질문을 현재 규칙으로 다시 답한다(앱이 현재 질문지로 다시 그린다).

        **이름·설명·아이콘·순서·활성은 답변이 있어도 바꿀 수 있다** — 답변이 제출 당시 문구를
        스냅샷으로 들고 있어 과거 화면이 흔들리지 않는다.
        """)
public record AdminRequestOptionRequest(
        Long parentId,
        @Schema(description = """
                **비워서 보내면 서버가 짓는다**(질문 `Q_001` · 선택지 `<부모코드>_001`).
                관리자에게 식별자를 지어내라고 요구하지 않기 위해서다 — 코드로 분기하는 곳은 없고,
                유일하고 읽히기만 하면 된다. 만든 뒤에는 바꿀 수 없다.

                값을 보내면 그대로 쓴다 — 개발자가 특정 행을 코드로 참조해야 할 때의 문이다.
                이미 있는 코드면 `MASTER_DUPLICATE`(409). **최대 36자** — 컬럼은 40자지만
                선택지 코드가 `<부모코드>_NNN`으로 4자를 더 쓰므로, 여기서 안 막으면
                부모는 저장되고 선택지 추가가 500으로 죽는다.
                """)
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 36)
        String code,
        @Schema(description = """
                질문의 **입력 유형** — SINGLE | MULTI | TEXT | NUMBER | DATE | ADDRESS.

                SINGLE·MULTI 만 선택지(자식 항목)를 갖는다. 나머지는 선택지 없이 값을 직접 받고,
                ADDRESS 는 지역 마스터(시도→시군구)를 그대로 쓴다.
                """) String selectionType,
        String iconKey,
        Integer sortOrder,
        Boolean active,

        @Schema(description = "그룹만: 답을 반드시 받는 질문인가. 켜도 **과거 요청에 소급하지 않는다**")
        Boolean required,

        @Schema(description = """
                그룹만: 이 **항목 id**가 선택됐을 때만 이 질문을 보인다. 비우면 항상 노출.
                깊이 1 고정 — 조건부 질문에 속한 항목을 다시 가리키면 `OPTION_CONDITION_INVALID`(400).
                연쇄 의존은 관리자가 추적할 수 없고, 앱이 어느 답을 지울지 판단할 수 없다.
                """)
        Long showWhenOptionId,

        @Schema(description = "항목만: NONE | TEXT | NUMBER | DATE — 고르면 열리는 입력칸의 종류")
        String extraInputType,

        @Schema(description = "항목만: TEXT 일 때 글자 수 상한(비우면 255). **255가 물리 한계**(컬럼 폭) — 넘겨 설정하면 코드 검증은 통과하는데 저장이 500으로 죽는다")
        @RangeWithErrorCode(
                value = "OPTION_TEXT_LIMIT_INVALID", min = 1, max = 255)
        Integer extraMaxLength,

        @Schema(description = "항목만: 그 항목을 골랐으면 추가 입력도 반드시 채워야 하는가")
        Boolean extraRequired,

        @Schema(description = "질문만(TEXT): 글자 수 상한. 비우면 255. **255가 물리 한계**(컬럼 폭)")
        @RangeWithErrorCode(
                value = "OPTION_TEXT_LIMIT_INVALID", min = 1, max = 255)
        Integer inputMaxLength,
        @Schema(description = "질문만(NUMBER): 하한. 비우면 0")
        Long inputMinNumber,
        @Schema(description = "질문만(NUMBER): 상한. 비우면 제한 없음")
        Long inputMaxNumber,
        @Schema(description = "질문만(ADDRESS): 여러 지역을 고를 수 있는가")
        Boolean inputMultiple,
        @Schema(description = "질문만: 입력칸 뒤에 붙는 표시 단위(원 · 년 · 회). 검증에는 쓰지 않는다",
                example = "회")
        String inputUnit,
        @Schema(description = "질문만(TEXT): 여러 줄로 받는가")
        Boolean inputMultiline,
        @Schema(description = "질문만(DATE): 오늘 이전 날짜를 허용하는가. 비우면 허용")
        Boolean inputAllowPast,

        List<TranslationEntry> translations
) {
    // springdoc은 **중첩 record의 홑이름**으로 스키마를 만든다 — 같은 이름이 여러 도메인에 있으면
    // 하나로 붕괴해 생성 계약이 거짓을 말한다(실제로 세 화면에서 필드가 어긋났다).
    // 자바 타입 이름은 그대로 두고 스키마 이름만 도메인별로 못 박는다.
    @Schema(name = "RequestOptionTranslationEntry")
    public record TranslationEntry(
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String languageCode,
            @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String name,
            @Schema(description = "질문 밑에 붙는 보조 설명. 비우면 표시하지 않는다")
            String description
    ) {
    }
}
