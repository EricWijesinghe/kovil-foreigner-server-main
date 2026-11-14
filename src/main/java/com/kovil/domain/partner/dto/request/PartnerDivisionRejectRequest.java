package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

// 반려는 사유와 함께 관리자가 본 판(version)을 싣는다 — 정지 사유(PartnerDivisionReasonRequest)와 모양이 갈린다.
@Schema(description = "반려. 사유와 관리자가 화면에서 본 판(`version`)을 싣는다.")
public record PartnerDivisionRejectRequest(
        @Schema(description = "반려 사유. **신청자에게 그대로 보여 준다** — 내부 은어를 쓰지 않는다.",
                example = "등록증의 대표자 성명과 입력한 이름이 다릅니다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 255)
        String reason,

        @Schema(description = """
                심사 상세 · 대기열에서 받은 `version` 그대로. 그 뒤에 신청자가 다시 냈거나 다른 관리자가 처리했으면
                `PARTNER_DIVISION_CHANGED`(409).
                """, example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        Long version
) {
}
