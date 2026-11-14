package com.kovil.domain.user.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        역할 변경(승급·강등). **ADMIN 을 만들 수 있는 유일한 경로다** — 가입 화면에서는
        신청할 수 없다(PO 확정 2026-09-17). USER 와 ADMIN 사이만 오간다 — PARTNER 는 이 경로로 주거나 뺄 수 없다.
        """)
public record RoleAssignRequest(
        @Schema(description = "바꿀 역할 이름 — USER · ADMIN", example = "ADMIN",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        String role
) {
}
