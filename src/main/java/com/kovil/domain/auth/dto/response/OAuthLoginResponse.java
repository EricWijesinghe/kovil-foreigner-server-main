package com.kovil.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        소셜 로그인 1단계 결과. 두 갈래다.

        - `phoneVerificationRequired=false` → `signIn`에 토큰쌍이 있다. 그대로 로그인 완료.
        - `phoneVerificationRequired=true`  → **아직 로그인이 아니다.** `oauthToken`을 들고
          휴대폰 인증(`/auth/phone/send` → `/verify`)을 거친 뒤 `/auth/oauth/{provider}/link`를 부른다.

        소셜 계정만으로는 한국 번호를 확인할 수 없고, 그게 이 서비스의 신원 축이다.
        """)
public record OAuthLoginResponse(

        @Schema(description = "true면 아직 로그인이 아니다. 휴대폰 인증 화면으로 보낸다.", example = "true")
        boolean phoneVerificationRequired,

        @Schema(description = "휴대폰 인증 후 link 호출에 실을 임시 증표. 10분 유효.")
        String oauthToken,

        @Schema(description = "이미 연결된 계정이면 여기에 토큰쌍이 들어온다.")
        SignInResponse signIn
) {
    public static OAuthLoginResponse existingUser(SignInResponse signIn) {
        return new OAuthLoginResponse(false, null, signIn);
    }

    public static OAuthLoginResponse phoneVerificationRequired(String oauthToken) {
        return new OAuthLoginResponse(true, oauthToken, null);
    }
}
