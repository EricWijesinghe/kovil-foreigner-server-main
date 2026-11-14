package com.kovil.domain.rentalrequest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        임대 요청 선택 항목 2단 트리. **깊이는 2로 고정**이다.

        **항목 추가는 서버 데이터만 바꾸면 된다** — 앱 배포 없이 늘어난다. 그래서 FE는
        코드를 하드코딩하지 말고 받은 트리를 그대로 그린다. `iconKey`도 마찬가지다.
        """)
public record RequestOptionGroupResponse(

        @Schema(description = "그룹 code", example = "ROOM_TYPE")
        String code,

        @Schema(description = "표시명. 번역이 없으면 code가 그대로 온다(빈 이름은 내보내지 않는다).",
                example = "방 구조")
        String name,

        @Schema(description = """
                **SINGLE = 라디오, MULTI = 체크박스.** 서버가 같은 규칙으로 검증하므로
                화면 위젯을 이 값으로 고르면 어긋나지 않는다.
                """, example = "SINGLE")
        String selectionType,

        @Schema(description = "FE 아이콘 키", example = "home")
        String iconKey,

        @Schema(description = "질문 밑에 붙는 보조 설명. 없으면 null — 표시하지 않는다.",
                example = "해당 없다면 다음 질문으로 넘어가세요")
        String description,

        @Schema(description = """
                **답을 반드시 받아야 하는 질문인가.** true면 답이 없을 때 제출이
                `OPTION_ANSWER_REQUIRED`(400)로 거절된다 — 화면이 먼저 막을 것.
                """, example = "true")
        Boolean required,

        @Schema(description = """
                **조건부 노출** — 이 항목 code가 선택됐을 때만 이 질문을 보인다. null이면 항상 노출.

                ★ 조건이 깨지면 서버가 그 질문의 답을 **조용히 버린다**. 화면도 같이 숨겨야
                사용자가 "답했는데 사라졌다"고 느끼지 않는다. 깊이는 1로 고정이라
                조건부 질문이 다시 조건이 되는 일은 없다.
                """, example = "ONE_ROOM")
        String showWhenOptionCode,

        @Schema(description = "입력칸 뒤에 붙는 표시 단위(원 · 년 · 회). 없으면 null", example = "회")
        String inputUnit,

        @Schema(description = "TEXT 질문을 여러 줄로 받는가", example = "false")
        Boolean inputMultiline,
        @Schema(description = "DATE: 오늘 이전 날짜를 고를 수 있는가 — 화면이 달력 하한을 정하는 축")
        Boolean inputAllowPast,

        @Schema(description = "TEXT 글자 수 상한 / NUMBER 하한·상한 / ADDRESS 다중 허용")
        Integer inputMaxLength,
        Long inputMinNumber,
        Long inputMaxNumber,
        Boolean inputMultiple,

        @Schema(description = "하위 항목. 비활성 항목은 빠진다.")
        List<Item> items
) {

    @Schema(description = "선택 항목 한 개")
    public record Item(

            @Schema(description = "항목 code. 요청 등록 시 이 값을 보낸다.", example = "ONE_ROOM")
            String code,

            @Schema(description = "표시명", example = "원룸")
            String name,

            @Schema(description = "FE 아이콘 키", example = "square")
            String iconKey,

            @Schema(description = """
                    **고르면 열리는 입력칸의 종류** — NONE | TEXT | NUMBER | DATE.
                    "기타 → 직접 입력"이 이 축이다. 값은 `optionAnswers`로 함께 보낸다.
                    앱이 모르는 종류는 입력칸 없이 선택만 되는 것으로 취급할 것.
                    """, example = "TEXT")
            String extraInputType,

            @Schema(description = "TEXT일 때 글자 수 상한. 없으면 255", example = "255")
            Integer extraMaxLength,

            @Schema(description = "이 항목을 골랐으면 추가 입력도 반드시 채워야 하는가", example = "true")
            Boolean extraRequired
    ) {
    }
}
