package com.kovil.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "휴대폰 인증 성공 결과.")
public record PhoneVerifyResponse(

        // 이 토큰이 "번호를 확인했다"는 증표다. 가입·소셜 연결이 이걸 요구한다.
        @Schema(description = "가입·소셜 연결에 쓸 인증 증표. **한 번만 소비된다.**",
                example = "a1b2c3d4e5f6...")
        String verificationToken,

        @Schema(description = "이 증표의 유효 시간(초). 안에 가입을 마쳐야 한다.", example = "600")
        long expiresInSeconds
) {
}
