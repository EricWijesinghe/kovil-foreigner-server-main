package com.kovil.infra.oauth.apple.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Apple은 userinfo 엔드포인트가 없다 — 신원은 id_token 안에 있다.
public record AppleTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("refresh_token") String refreshToken
) {
}
