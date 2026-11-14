package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.oauth.apple.AppleIdTokenClaims;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifier;
import com.kovil.infra.oauth.apple.AppleNonceStore;
import com.kovil.infra.oauth.apple.AppleOAuthProperties;
import com.kovil.infra.oauth.apple.AppleTokenExchanger;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_PROVIDER_DISABLED;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_STATE_INVALID;

// iOS 네이티브 Apple 로그인. 신원을 확인한 뒤의 분기(기존 회원 · 휴대폰 인증 요구)는 웹 로그인과 같은 자리
// (OAuthAuthService.signInOrHold)로 합류하고, 새 회원의 가입 확정도 기존 link 그대로다.
//
// ★ 이 클래스에는 트랜잭션을 걸지 않는다. Apple 호출(공개키 · code 교환)을 기다리는 동안 DB 커넥션을 쥐지 않게
//   외부 호출을 다 끝낸 뒤 합류 지점에서만 트랜잭션을 연다.
@Service
@RequiredArgsConstructor
public class AppleNativeAuthService {

    private static final int NAME_MAX_LENGTH = 100;

    private final Encryptor encryptor;
    private final AppleNonceStore nonceStore;
    private final OAuthAuthService oAuthAuthService;
    private final AppleOAuthProperties properties;
    private final AppleTokenExchanger tokenExchanger;
    private final AppleIdTokenVerifier idTokenVerifier;

    public String issueNonce() {
        requireEnabled();
        return nonceStore.generate();
    }

    public OAuthLoginResponse login(String identityToken, String authorizationCode, String nonce,
                                    String givenName, String familyName) {
        requireEnabled();
        // 검증보다 먼저 소모한다. 검증이 실패해도 같은 nonce 로 다시 보낼 수 없어야 재전송이 막힌다.
        if (!nonceStore.consume(nonce)) {
            throw new OAuthException(OAUTH_STATE_INVALID);
        }
        AppleIdTokenClaims claims = idTokenVerifier.verify(identityToken, nonce, properties.clientId());
        String refreshToken = tokenExchanger.exchange(authorizationCode, claims.sub());

        return oAuthAuthService.signInOrHold(AuthType.APPLE,
                new OAuthUserInfo(claims.sub(), claims.email(), joinName(givenName, familyName),
                        encryptor.encrypt(refreshToken), properties.clientId()));
    }

    private void requireEnabled() {
        if (!properties.nativeEnabled()) {
            throw new OAuthException(OAUTH_PROVIDER_DISABLED);
        }
    }

    // 웹 흐름의 form_post user 와 같은 규칙(이름 + " " + 성, 공백 정리). 비면 null 이다.
    // 각 칸이 50자라 합치면 users.name(100자)을 넘을 수 있어 끝을 자른다.
    private static String joinName(String givenName, String familyName) {
        String joined = ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName))
                .trim().replaceAll("\\s+", " ");
        if (joined.isEmpty()) {
            return null;
        }
        if (joined.codePointCount(0, joined.length()) <= NAME_MAX_LENGTH) {
            return joined;
        }
        return joined.substring(0, joined.offsetByCodePoints(0, NAME_MAX_LENGTH)).trim();
    }
}
