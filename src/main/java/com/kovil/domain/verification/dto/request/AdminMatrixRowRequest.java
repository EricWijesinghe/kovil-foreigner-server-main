package com.kovil.domain.verification.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        요건 매트릭스 행 — "이 신분은 이 문서를 낸다". 이미 제출된 인증은 제출 시점
        스냅샷을 따르므로, 여기 변경은 **새 제출부터** 적용된다.
        """)
public record AdminMatrixRowRequest(
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String residentStatusCode,
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String documentTypeCode,
        Boolean isRequired
) {
}
