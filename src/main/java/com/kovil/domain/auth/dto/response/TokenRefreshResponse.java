package com.kovil.domain.auth.dto.response;

import com.kovil.domain.auth.dto.TokenPair;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 회전 결과. **리프레시 토큰도 함께 바뀐다** — 옛 값은 버려야 한다.")
public record TokenRefreshResponse(

        @Schema(description = "새 액세스 토큰")
        String accessToken,

        @Schema(description = "새 리프레시 토큰. 저장해 둔 값을 이것으로 교체한다.")
        String refreshToken,

        @Schema(description = "액세스 토큰 만료까지 남은 초", example = "1800")
        long accessTokenExpiresIn
) {
    public static TokenRefreshResponse from(TokenPair tokens) {
        return new TokenRefreshResponse(
                tokens.accessToken(), tokens.refreshToken(), tokens.accessTokenExpiresIn());
    }
}
