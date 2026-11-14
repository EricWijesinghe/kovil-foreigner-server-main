package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        아이디(이메일) 찾기. 비밀번호 재설정과 **같은 축**으로 본인을 확인한다 — 휴대폰이다.
        같은 목적(`PASSWORD_RESET`)의 인증 토큰을 쓴다: 둘 다 "로그인 수단을 잃었다"는 같은 상황이고,
        SMS 본문도 목적에 따라 다르지 않다.
        """)
public record AccountLookupRequest(
        @Schema(description = "`/auth/phone/verify`(purpose=PASSWORD_RESET)가 준 토큰",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("VERIFICATION_TOKEN_INVALID")
        String verificationToken
) {
}
