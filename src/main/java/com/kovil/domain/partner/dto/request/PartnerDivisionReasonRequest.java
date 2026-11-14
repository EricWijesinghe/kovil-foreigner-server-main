package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

// 정지 사유. 정지는 관리자가 본 판(version)을 싣지 않는다 — 닫는 방향이라 그사이의 재제출과 무관하게 막아야 한다.
@Schema(description = "정지 사유")
public record PartnerDivisionReasonRequest(
        @Schema(description = "정지 사유. 관리자 내부 기록이다(파트너 응답 · 알림에 실리지 않는다).",
                example = "허위 매물 신고가 누적되었습니다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 255)
        String reason
) {
}
