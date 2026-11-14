package com.kovil.domain.verification.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        승인 무효화. **사유는 내부 기록이며 사용자에게 노출되지 않는다.**

        반려 사유와 다른 자리에 남는다 — "반려했다"와 "승인했다가 취소했다"는 다른 사실이다.
        """)
public record VerificationRevokeRequest(

        @Schema(description = "무효화 사유(내부용)",
                example = "제출된 재직증명서가 위조로 확인됨. 발급기관 확인 완료.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("REVOKE_REASON_REQUIRED")
        @SizeWithErrorCode(value = "REVOKE_REASON_REQUIRED", min = 1, max = 255)
        String reason
) {
}
