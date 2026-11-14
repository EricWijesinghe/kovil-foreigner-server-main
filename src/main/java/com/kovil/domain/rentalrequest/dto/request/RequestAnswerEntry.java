package com.kovil.domain.rentalrequest.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = """
        질문 하나에 대한 **값 답변**.

        두 자리에서 쓰인다:
        1. **선택형 질문의 항목이 추가 입력칸을 여는 경우** — `optionCode`에 그 **항목** code를 넣는다
           ("기타 → 직접 입력"). 선택 자체는 `optionCodes`가 소유하고 여기는 값만 얹는다.
        2. **비선택형 질문**(TEXT · NUMBER · DATE · ADDRESS) — `optionCode`에 그 **질문** code를 넣는다.
           선택지가 없으므로 이 배열이 답의 전부다. 이때도 `optionCodes`에 같은 code를 함께 보낸다
           (답을 했다는 사실은 `optionCodes`가, 값은 여기가 말한다).

        질문·항목의 유형에 맞는 칸 하나만 채운다. 맞지 않는 값은 무시되고,
        필수인데 비어 있으면 `OPTION_EXTRA_REQUIRED`(400)다.
        """)
public record RequestAnswerEntry(
        String optionCode,
        @Schema(description = "TEXT") String textValue,
        @Schema(description = "NUMBER") Long numberValue,
        @Schema(description = """
                DATE (yyyy-MM-dd). 질문이 과거 불가(`inputAllowPast=false`)면
                오늘 이전 날짜는 `OPTION_DATE_IN_PAST`(400) — 오늘은 허용된다.
                """) LocalDate dateValue,
        @Schema(description = """
                ADDRESS — **시군구 code**(5자리) 목록. 시도 code를 보내면 `REGION_INVALID_LEVEL`.
                질문이 `inputMultiple`이 아니면 하나만 보낸다(둘 이상이면 `OPTION_EXTRA_INVALID`).
                """, example = "[\"11215\"]")
        List<String> regionCodes
) {
}
