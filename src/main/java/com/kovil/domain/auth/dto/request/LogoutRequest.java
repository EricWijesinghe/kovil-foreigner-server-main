package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그아웃. **이 기기의 세션만 끊는다** — 다른 기기는 유지된다.")
public record LogoutRequest(
        @Schema(description = "이 기기가 들고 있는 리프레시 토큰",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("REFRESH_TOKEN_BLANK")
        String refreshToken
) {
}
