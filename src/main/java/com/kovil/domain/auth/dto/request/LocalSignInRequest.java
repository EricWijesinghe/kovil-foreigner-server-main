package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        이메일 로그인.

        **계정 없음과 비밀번호 불일치가 같은 `INVALID_CREDENTIALS`로 나간다** — 가입 여부를
        응답 차이로 알아낼 수 없게 하기 위해서다. FE는 두 경우를 구분해 안내하지 않아야 한다.
        """)
public record LocalSignInRequest(

        @Schema(example = "someone@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("EMAIL_REQUIRED")
        String email,

        @Schema(description = "형식 검사를 하지 않는다(가입 시 규칙이 바뀌어도 기존 회원이 잠기지 않도록).",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("PASSWORD_REQUIRED")
        String password
) {
}
