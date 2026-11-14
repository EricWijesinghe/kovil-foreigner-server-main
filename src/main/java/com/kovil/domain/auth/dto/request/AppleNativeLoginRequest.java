package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(description = """
        iOS 앱의 Apple 로그인 결과를 그대로 보낸다(`expo-apple-authentication` 의 `signInAsync` 응답).
        이메일 칸은 받지 않는다. 이메일은 서버가 검증한 `identityToken` 에서 읽는다.
        """)
public record AppleNativeLoginRequest(

        @Schema(description = "`credential.identityToken` 원문(JWT)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_TOKEN_INVALID")
        @SizeWithErrorCode(value = "OAUTH_TOKEN_INVALID", max = 4096)
        String identityToken,

        @Schema(description = "`credential.authorizationCode`. 5분 안에 한 번만 쓸 수 있다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_TOKEN_INVALID")
        @SizeWithErrorCode(value = "OAUTH_TOKEN_INVALID", max = 512)
        String authorizationCode,

        @Schema(description = "`GET /apple/nonce` 가 준 값. `signInAsync` 에 넘긴 원문 그대로 보낸다. **재사용 불가**",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_STATE_INVALID")
        @SizeWithErrorCode(value = "OAUTH_STATE_INVALID", max = 128)
        String nonce,

        @Schema(description = """
                `credential.fullName`. **처음 로그인할 때 한 번만 온다.** 그 뒤로는 null 이므로 받은 그대로 보낸다.
                없으면 비운다.
                """)
        @Valid FullName fullName
) {

    @Schema(name = "AppleNativeFullName")
    public record FullName(
            @SizeWithErrorCode(value = "INVALID_REQUEST", max = 50) String givenName,
            @SizeWithErrorCode(value = "INVALID_REQUEST", max = 50) String familyName
    ) {
    }

    public String givenNameOrNull() {
        return fullName == null ? null : fullName.givenName();
    }

    public String familyNameOrNull() {
        return fullName == null ? null : fullName.familyName();
    }
}
