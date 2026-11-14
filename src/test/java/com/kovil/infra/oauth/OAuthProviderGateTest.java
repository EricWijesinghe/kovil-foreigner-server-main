package com.kovil.infra.oauth;

import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 허용 목록 게이트 — 애플 잠금(개발자 계정 확보 전)의 정본 스위치.
// 빈이 있어도 목록에 없으면 잠긴다: "구현이 없다"와 "정책상 껐다"는 다른 실패다.
class OAuthProviderGateTest {

    private static OAuthClient fakeFor(AuthType type) {
        return new OAuthClient() {
            @Override public AuthType provider() { return type; }
            @Override public String buildAuthorizationUri(String state, String challenge) { return "https://fake/" + state; }
            @Override public OAuthUserInfo getUserInfo(String code, String state, String userPayload, String verifier) {
                return new OAuthUserInfo(type + "-user", null, null);
            }
        };
    }

    @Test
    @DisplayName("목록에 없는 프로바이더는 빈이 있어도 OAUTH_PROVIDER_DISABLED다")
    void disabled_provider_is_rejected_even_with_bean() {
        var router = new OAuthClientRouter(
                List.of(fakeFor(AuthType.GOOGLE), fakeFor(AuthType.APPLE)),
                new OAuthProviderProperties(List.of(AuthType.GOOGLE, AuthType.FACEBOOK)));

        assertThatThrownBy(() -> router.getClient(AuthType.APPLE))
                .isInstanceOf(OAuthException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.OAUTH_PROVIDER_DISABLED);
    }

    @Test
    @DisplayName("목록에 있는데 빈이 없으면 여전히 UNSUPPORTED — 정책과 구현 결손을 구분한다")
    void enabled_but_missing_bean_is_unsupported() {
        var router = new OAuthClientRouter(
                List.of(fakeFor(AuthType.GOOGLE)),
                new OAuthProviderProperties(List.of(AuthType.GOOGLE, AuthType.FACEBOOK)));

        assertThat(router.getClient(AuthType.GOOGLE)).isNotNull();
        assertThatThrownBy(() -> router.getClient(AuthType.FACEBOOK))
                .isInstanceOf(OAuthException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }
}
