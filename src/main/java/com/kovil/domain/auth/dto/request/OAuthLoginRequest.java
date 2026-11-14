package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "소셜 로그인 1단계. authorize에서 받은 code·state를 그대로 보낸다.")
public record OAuthLoginRequest(

        @Schema(description = "provider가 redirect_uri로 돌려준 인가 코드",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_TOKEN_INVALID")
        String code,

        @Schema(description = "authorize가 준 state. **재사용 불가**하며 틀리면 `OAUTH_STATE_INVALID`.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_STATE_INVALID")
        String state,

        @Schema(description = """
                보내지 않는다. Apple 웹의 이름(form_post `user`)은 서버 콜백이 받아 두고 여기서 꺼내 쓴다.
                iOS 앱의 Apple 은 `POST /apple/native` 의 `fullName` 으로 이름을 받는다. Google·Facebook 은 이름을 userinfo 에서 읽는다.
                값을 보내면 Apple 웹에서 서버가 받아 둔 이름보다 먼저 쓴다.
                """)
        String user,

        @Schema(description = """
                1단계에서 만든 `code_verifier` **원문**(PKCE, RFC 7636). 43~128자.
                1단계 `authorize` 에는 이것의 SHA-256을 base64url로 적은 `codeChallenge`를 보냈고,
                서버가 그 짝을 대조한다. 틀리면 `OAUTH_PKCE_INVALID`.
                **커스텀 스킴으로 돌아오는 code 가 가로채여도 무력화되도록 하는 장치다.**
                """,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_PKCE_INVALID")
        String codeVerifier
) {
}
