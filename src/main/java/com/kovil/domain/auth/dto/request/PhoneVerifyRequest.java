package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.PatternWithErrorCode;
import com.kovil.domain.auth.enums.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        인증번호 확인.

        **틀림·만료·미발급·시도초과가 전부 `VERIFICATION_CODE_INVALID` 하나로 나간다** —
        구분하면 어떤 번호로 발송 이력이 있는지 외부에서 알아낼 수 있기 때문이다.
        FE는 이 코드 하나에 "인증번호를 다시 확인하거나 재발송해 주세요"로 응대하면 된다.
        """)
public record PhoneVerifyRequest(

        @Schema(description = "발송 때 보낸 것과 같은 번호", example = "010-1234-5678",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("PHONE_REQUIRED")
        String phone,

        @Schema(description = "6자리 숫자", example = "123456",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("VERIFICATION_CODE_REQUIRED")
        @PatternWithErrorCode(value = "VERIFICATION_CODE_INVALID", regexp = "^\\d{6}$")
        String code,

        @Schema(description = "발송 때와 같은 목적", example = "SIGNUP", defaultValue = "SIGNUP")
        VerificationPurpose purpose
) {
    public VerificationPurpose purposeOrDefault() {
        return purpose == null ? VerificationPurpose.SIGNUP : purpose;
    }
}
