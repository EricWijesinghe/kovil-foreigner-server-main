package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        소셜 로그인 2단계 — 휴대폰 인증을 마친 뒤 확정한다.

        인증된 번호로 **새 계정을 만든다.** 그 번호의 계정이 이미 있으면 붙이지 않고 `DUPLICATE_PHONE` 으로 거절한다.
        """)
public record OAuthLinkRequest(

        @Schema(description = "1단계가 준 임시 증표(10분 유효)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_TEMP_TOKEN_EXPIRED")
        String oauthToken,

        @Schema(description = "`/auth/phone/verify`가 준 증표",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("VERIFICATION_TOKEN_INVALID")
        String verificationToken,

        @Schema(description = "UI 언어(선택). 신규 계정에만 적용된다.", example = "ko")
        String preferredLanguage
) {
}
