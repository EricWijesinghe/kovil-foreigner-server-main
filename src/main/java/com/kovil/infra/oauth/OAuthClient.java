package com.kovil.infra.oauth;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.dto.OAuthUserInfo;

public interface OAuthClient {

    AuthType provider();

    /**
     * @param codeChallenge PKCE(S256)
     */
    String buildAuthorizationUri(String state, String codeChallenge);

    /**
     * @param userPayload Apple 전용 — 최초 인가 때만 form_post로 오는 {@code user} JSON 원문.
     *                    다른 프로바이더는 무시. 없으면 null.
     * @param codeVerifier PKCE 원문. 토큰 교환에 실어야 provider 가 code↔challenge 결속을 검사
     */
    OAuthUserInfo getUserInfo(String code, String state, String userPayload, String codeVerifier);
}
