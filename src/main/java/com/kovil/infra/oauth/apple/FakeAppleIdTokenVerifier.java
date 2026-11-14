package com.kovil.infra.oauth.apple;

import com.kovil.domain.auth.exception.OAuthException;
import lombok.extern.slf4j.Slf4j;

import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_TOKEN_INVALID;

// 개발·테스트용. FakeOAuthClient 와 같은 약속으로 identityToken 문자열을 그대로 sub 로 쓴다. 같은 값이면 같은 사용자다.
// 이메일은 사용자가 이메일을 숨긴 경우의 꼴(릴레이 주소)로 준다. nonce 소모는 서비스가 진짜 Redis 로 한다.
// 알림은 "종류:sub" 문자열로 흉내 낸다(예: consent-revoked:fake-apple-abc).
@Slf4j
public class FakeAppleIdTokenVerifier implements AppleIdTokenVerifier {

    @Override
    public AppleIdTokenClaims verify(String idToken, String nonce, String audience) {
        if (idToken == null || idToken.isBlank()) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        log.info("[FakeOAuth] Apple identityToken={}", idToken);
        String sub = "fake-apple-" + idToken;
        return new AppleIdTokenClaims(sub, sub + "@privaterelay.appleid.com", true, true);
    }

    @Override
    public AppleAccountEvent verifyEvent(String payload) {
        int colon = payload == null ? -1 : payload.indexOf(':');
        if (colon <= 0 || colon == payload.length() - 1) {
            throw new OAuthException(OAUTH_TOKEN_INVALID);
        }
        return new AppleAccountEvent(payload.substring(0, colon), payload.substring(colon + 1));
    }
}
