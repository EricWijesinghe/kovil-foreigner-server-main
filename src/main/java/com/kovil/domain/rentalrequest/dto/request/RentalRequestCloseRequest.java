package com.kovil.domain.rentalrequest.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        맡은 사무소의 종료. **되돌릴 수 없다.** 결과를 한 번 고른다.

        유학생 화면에는 결과 없이 "종료"만 보인다 — 결과는 성사율을 남기는 내부 기록이다.
        """)
public record RentalRequestCloseRequest(

        @Schema(description = "FOUND(방을 구함) · NOT_PROCEEDED(진행 안 함)", example = "FOUND",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("RENTAL_REQUEST_OUTCOME_REQUIRED")
        RentalRequestOutcome outcome
) {
}
