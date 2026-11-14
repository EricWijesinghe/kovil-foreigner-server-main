package com.kovil.infra.oauth.apple;

import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FakeAppleTokenExchanger implements AppleTokenExchanger {

    @Override
    public String exchange(String authorizationCode, String expectedSub) {
        return "fake-apple-refresh-" + authorizationCode;
    }

    // fake 모드의 Apple 웹은 FakeOAuthClient 가 받아 여기까지 오지 않는다.
    @Override
    public AppleTokenResponse exchangeWeb(String authorizationCode) {
        throw new UnsupportedOperationException("fake 모드의 Apple 웹은 FakeOAuthClient 가 처리한다");
    }

    @Override
    public void revoke(String refreshToken, String clientId) {
        log.info("[FakeOAuth] Apple 토큰 철회 clientId={}", clientId);
    }
}
