package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사무소 정보 변경 승인. 관리자가 화면에서 본 판(`version`)을 싣는다.")
public record OfficeChangeApproveRequest(
        @Schema(description = """
                변경 신청 상세 · 대기열에서 받은 `version` 그대로. 그 뒤에 신청자가 다시 냈거나 다른 관리자가 처리했으면
                `OFFICE_CHANGE_REQUEST_CHANGED`(409) — 본 적 없는 값을 승인하지 않게 다시 열어 보게 한다.
                """, example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        Long version
) {
}
