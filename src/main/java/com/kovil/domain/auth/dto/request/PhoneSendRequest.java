package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.domain.auth.enums.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증번호 발송 요청.")
public record PhoneSendRequest(

        // 표기는 서버가 정규화한다 — 하이픈·공백·+82 어느 형태로 보내도 된다.
        @Schema(description = """
                한국 휴대폰 번호. **표기는 자유다** — `010-1234-5678` · `+82 10 1234 5678` 모두 받는다.
                서버가 하나의 형태로 정규화한다. 한국 번호가 아니면 `PHONE_NOT_KR`.
                """, example = "010-1234-5678", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("PHONE_REQUIRED")
        String phone,

        @Schema(description = "인증 목적. 생략하면 SIGNUP.", example = "SIGNUP",
                defaultValue = "SIGNUP")
        VerificationPurpose purpose
) {
    public VerificationPurpose purposeOrDefault() {
        return purpose == null ? VerificationPurpose.SIGNUP : purpose;
    }
}
