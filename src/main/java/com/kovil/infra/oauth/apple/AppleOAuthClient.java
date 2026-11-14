package com.kovil.infra.oauth.apple;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.oauth.OAuthClient;
import com.kovil.infra.oauth.OAuthProviderProperties;
import com.kovil.infra.oauth.PkceValidator;
import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

// 웹(kovil.co.kr)의 Apple 로그인. client_id 는 Services ID 이고, Apple 은 결과를 form_post 로
// 서버 콜백(POST /oauth/apple/callback)에 보낸다. 앱 iOS 는 이 흐름 대신 네이티브 로그인을 쓴다.
//
// ★ code 와 PKCE 의 결속을 provider 가 걸어 주지 않는다. Apple 이 code_challenge 지원을 광고하지 않아서다
//   (2026-09-01 실측). 대신 challenge 를 nonce 로 실어 보내면 Apple 이 그 값을 id_token 에 박는다.
//   교환 응답의 id_token nonce 가 verifier 에서 계산한 challenge 와 같아야 통과하므로, 훔친 code 를
//   공격자 자신의 state · verifier 로 바꿔 내면 여기서 끊긴다(PkceValidator 주석의 09-01 탈취와 같은 모양).
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class AppleOAuthClient implements OAuthClient {

    private final Encryptor encryptor;
    private final PkceValidator pkceValidator;
    private final AppleOAuthProperties properties;
    private final AppleTokenExchanger tokenExchanger;
    private final AppleIdTokenVerifier idTokenVerifier;
    private final AppleCallbackNameStore nameStore;
    private final OAuthProviderProperties providerProperties;

    // 목록에 APPLE 을 넣고 값을 빠뜨리면 웹 Apple 로그인이 전부 실패한다. 부팅에서 멈춰 배포 헬스체크가 실패하게.
    @PostConstruct
    void requireCredentials() {
        if (providerProperties.enabledProviders() == null
                || !providerProperties.enabledProviders().contains(AuthType.APPLE)) {
            return;
        }
        if (isBlank(properties.webClientId()) || isBlank(properties.redirectUri())
                || isBlank(properties.teamId()) || isBlank(properties.keyId()) || isBlank(properties.privateKey())) {
            throw new IllegalStateException(
                    "oauth.enabled-providers 에 APPLE 이 있는데 APPLE_WEB_CLIENT_ID · REDIRECT_URI · TEAM_ID · KEY_ID · PRIVATE_KEY 중 빈 값이 있다");
        }
    }

    @Override
    public AuthType provider() {
        return AuthType.APPLE;
    }

    @Override
    public String buildAuthorizationUri(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.webClientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", "name email")
                // Apple은 scope를 요청하면 응답을 form_post로 돌려준다.
                .queryParam("response_mode", "form_post")
                .queryParam("state", state)
                .queryParam("nonce", codeChallenge)
                // ★ encode() 없이 toUriString() 하면 값 안의 공백·특수문자가 그대로 남는다.
                // 실사고(2026-08-22): 구글 scope "openid email profile" 의 공백이 인코딩되지 않아
                // authorize URL 이 깨졌다(브라우저·curl 이 거부). 지금 scope 가 한 단어인
                // 프로바이더도 값이 늘면 같은 사고가 나므로 셋 다 인코딩한다.
                .encode().build().toUriString();
    }

    @Override
    public OAuthUserInfo getUserInfo(String code, String state, String userPayload,
                                     String codeVerifier) {
        AppleTokenResponse token = tokenExchanger.exchangeWeb(code);
        AppleIdTokenClaims claims = idTokenVerifier.verify(
                token.idToken(), pkceValidator.challengeOf(codeVerifier), properties.webClientId());

        // ★ Apple은 **id_token에 이름을 넣지 않는다.** 최초 인가 때 form_post 본문의 `user` 필드로
        //   단 한 번 온다. 그 POST 는 서버 콜백이 받아 state 에 묶어 두었다(AppleCallbackNameStore).
        //   예전 클라이언트가 원문을 넘기면 그 값을 먼저 쓴다.
        String name = nameStore.parseName(userPayload);
        String stored = nameStore.consume(state);
        return new OAuthUserInfo(claims.sub(), claims.email(), name != null ? name : stored,
                encryptor.encrypt(token.refreshToken()), properties.webClientId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
