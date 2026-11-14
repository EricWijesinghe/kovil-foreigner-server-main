package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.PatternWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        이메일 가입. **휴대폰 인증이 선행되어야 한다** — `verificationToken`이 그 증표다.

        성공하면 **바로 토큰쌍을 준다**(재로그인 불필요).
        """)
public record LocalSignUpRequest(

        @Schema(description = "`/auth/phone/verify`가 준 증표. **한 번만 쓸 수 있다.**",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("VERIFICATION_TOKEN_INVALID")
        String verificationToken,

        @Schema(description = "로그인 식별자. 대소문자·공백은 서버가 정규화한다.",
                example = "someone@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("EMAIL_REQUIRED")
        @PatternWithErrorCode(value = "EMAIL_INVALID_FORMAT",
                regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")
        String email,

        @Schema(description = "8~64자, 영문과 숫자를 포함해야 한다.", example = "passw0rd!",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("PASSWORD_REQUIRED")
        @SizeWithErrorCode(value = "PASSWORD_INVALID_FORMAT", min = 8, max = 64)
        @PatternWithErrorCode(value = "PASSWORD_INVALID_FORMAT",
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$")
        String password,

        @Schema(description = "표시용 이름(선택). 계약 명의의 정본이 아니다.", example = "Nguyen Van A")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 100)
        String name,

        @Schema(description = "UI 언어(선택). 지원하지 않는 값이면 `en`으로 떨어진다.", example = "ko")
        String preferredLanguage
) {
}
