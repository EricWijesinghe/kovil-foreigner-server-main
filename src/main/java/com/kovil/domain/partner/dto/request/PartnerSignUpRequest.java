package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.PatternWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(description = """
        파트너 가입. **휴대폰 인증이 선행되어야 한다** — `verificationToken` 이 그 증표다.
        가입하면서 부문을 하나 신청한다(지금은 공인중개사 `REAL_ESTATE_BROKER` 뿐).
        등록증은 가입 뒤 받은 토큰으로 올리고 `PUT /api/v1/partner/divisions/REAL_ESTATE_BROKER` 로 제출한다.
        """)
public record PartnerSignUpRequest(

        @Schema(description = "`/auth/phone/verify`가 준 증표. **한 번만 쓸 수 있다.**",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("VERIFICATION_TOKEN_INVALID")
        String verificationToken,

        @Schema(description = "로그인 식별자. 대소문자·공백은 서버가 정규화한다.",
                example = "partner@example.test", requiredMode = Schema.RequiredMode.REQUIRED)
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

        @Schema(description = "가입자(대표자) 이름", example = "홍길동", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 100)
        String name,

        @Schema(description = "UI 언어(선택). 지원하지 않는 값이면 `en`으로 떨어진다.", example = "ko")
        String preferredLanguage,

        @Schema(description = "신청할 부문. 지금은 `REAL_ESTATE_BROKER` 뿐이다.",
                example = "REAL_ESTATE_BROKER", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        PartnerDivisionType division,

        @Schema(description = "공인중개사 부문의 상세 — 중개사무소 정보", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        @Valid
        BrokerageOfficeForm office
) {
}
