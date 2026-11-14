package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        토큰 회전. **응답의 리프레시 토큰으로 저장값을 교체해야 한다.**

        폐기된 토큰을 다시 보내면 탈취로 판정해 **그 계정의 모든 세션이 끊긴다**.
        """)
public record RefreshTokenRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("REFRESH_TOKEN_BLANK")
        String refreshToken
) {
}
