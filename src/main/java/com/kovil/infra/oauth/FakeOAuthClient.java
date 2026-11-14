package com.kovil.infra.oauth;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 개발·테스트용. 세 provider 앱 등록이 끝나기 전에도 소셜 연결 흐름을 돌려볼 수 있어야 한다.
// code를 그대로 provider sub로 쓴다 — 같은 code면 같은 사용자다.
@Slf4j
@RequiredArgsConstructor
public class FakeOAuthClient implements OAuthClient {

    private final AuthType provider;

    @Override
    public AuthType provider() {
        return provider;
    }

    @Override
    public String buildAuthorizationUri(String state, String codeChallenge) {
        return "https://example.invalid/fake-oauth/" + provider.name().toLowerCase() + "?state=" + state;
    }

    @Override
    public OAuthUserInfo getUserInfo(String code, String state, String userPayload,
                                     String codeVerifier) {
        log.info("[FakeOAuth] provider={} code={}", provider, code);
        return new OAuthUserInfo("fake-" + provider.name().toLowerCase() + "-" + code, null, null);
    }
}
