package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사무소 정보 변경 반려. 사유와 관리자가 화면에서 본 판(`version`)을 싣는다.")
public record OfficeChangeRejectRequest(
        @Schema(description = "반려 사유. **신청자에게 그대로 보여 준다**(알림 본문에도 실린다) — 내부 은어를 쓰지 않는다.",
                example = "새 등록증의 상호와 입력한 상호가 다릅니다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 255)
        String reason,

        @Schema(description = """
                변경 신청 상세 · 대기열에서 받은 `version` 그대로. 그 뒤에 신청자가 다시 냈거나 다른 관리자가 처리했으면
                `OFFICE_CHANGE_REQUEST_CHANGED`(409).
                """, example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        Long version
) {
}
