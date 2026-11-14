package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.PatternWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        비밀번호 재설정. **이메일이 아니라 휴대폰이 본인 확인 축이다** — 신원 앵커가
        phone_hash인 서비스라, 번호 인증(purpose=`PASSWORD_RESET`)을 통과한 토큰만 받는다.
        """)
public record PasswordResetRequest(

        @Schema(description = "`/auth/phone/verify`(purpose=PASSWORD_RESET)가 준 토큰",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("VERIFICATION_TOKEN_INVALID")
        String verificationToken,

        @Schema(description = "새 비밀번호. 8~64자, 영문과 숫자를 포함해야 한다.",
                example = "newPassw0rd!", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("PASSWORD_REQUIRED")
        @SizeWithErrorCode(value = "PASSWORD_INVALID_FORMAT", min = 8, max = 64)
        @PatternWithErrorCode(value = "PASSWORD_INVALID_FORMAT",
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$")
        String newPassword
) {
}
