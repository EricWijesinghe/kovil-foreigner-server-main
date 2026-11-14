package com.kovil.domain.verification.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        인증 반려. **사유는 사용자에게 그대로 노출된다**(FR-206).

        무엇을 고쳐야 다시 통과하는지 적을 것 — 사용자는 이 문구만 보고 재제출한다.
        """)
public record VerificationRejectRequest(

        @Schema(description = "반려 사유. 사용자 화면에 그대로 나간다.",
                example = "여권 사진면 하단이 잘려 있습니다. 전체가 보이게 다시 촬영해 주세요.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("REJECT_REASON_REQUIRED")
        @SizeWithErrorCode(value = "REJECT_REASON_REQUIRED", min = 1, max = 255)
        String reason
) {
}
