package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

// 검증은 가입 폼(BrokerageOfficeForm.tel)과 같다 — 같은 칸을 두 길로 받으면서 규칙이 갈리면 안 된다.
@Schema(description = "중개사무소 대표번호만 바꾼다. 심사 없이 즉시 바뀐다.")
public record BrokerageOfficeTelRequest(
        @Schema(description = "대표번호(공시 연락처)", example = "02-000-0000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 30)
        String tel
) {
}
