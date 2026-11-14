package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.auth.service.support.SignUpProcessor;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.oauth.OAuthClientRouter;
import com.kovil.infra.oauth.OAuthStateStore;
import com.kovil.infra.oauth.OAuthTempTokenStore;
import com.kovil.infra.oauth.PkceValidator;
import com.kovil.infra.oauth.apple.AppleIdTokenClaims;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifier;
import com.kovil.infra.oauth.apple.AppleNonceStore;
import com.kovil.infra.oauth.apple.AppleOAuthProperties;
import com.kovil.infra.oauth.apple.AppleTokenExchanger;
import com.kovil.infra.oauth.dto.OAuthTempData;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * 합류 지점(OAuthAuthService.signInOrHold)은 진짜를 쓴다. 목으로 두면 "Apple 은 따로 분기한다" 는 결함을
 * 놓친다 — 기존 회원 · 정지 · 새 회원 판정은 웹 로그인과 한 자리여야 한다.
 */
@ExtendWith(MockitoExtension.class)
class AppleNativeAuthServiceTest {

    private static final String BUNDLE_ID = "com.kovil.app";
    private static final String SUB = "001234.abcdef.0001";
    private static final String NONCE = "nonce-1";
    private static final String RELAY = "x1y2z3@privaterelay.appleid.com";

    @Mock Encryptor encryptor;
    @Mock AppleNonceStore nonceStore;
    @Mock AppleTokenExchanger tokenExchanger;
    @Mock AppleIdTokenVerifier idTokenVerifier;

    @Mock TokenIssuer tokenIssuer;
    @Mock OAuthStateStore stateStore;
    @Mock SignUpProcessor signUpProcessor;
    @Mock OAuthTempTokenStore tempTokenStore;
    @Mock OAuthClientRouter oAuthClientRouter;
    @Mock UserAuthRepository userAuthRepository;

    private AppleNativeAuthService service(boolean nativeEnabled) {
        OAuthAuthService oAuthAuthService = new OAuthAuthService(tokenIssuer, new PkceValidator(), stateStore,
                signUpProcessor, tempTokenStore, oAuthClientRouter, userAuthRepository);
        return new AppleNativeAuthService(encryptor, nonceStore, oAuthAuthService,
                new AppleOAuthProperties(nativeEnabled, BUNDLE_ID, "com.kovil.web", "TEAMID1234", "KEYID12345", "pem",
                        null, null, null, null, null),
                tokenExchanger, idTokenVerifier);
    }

    private AppleNativeAuthService service() {
        return service(true);
    }

    private static User user(UserStatus status) {
        return User.builder().uuid("uuid-1").role(UserRole.USER).status(status).preferredLanguage("ko").build();
    }

    private void givenAppleAccepts() {
        given(nonceStore.consume(NONCE)).willReturn(true);
        given(idTokenVerifier.verify("id-token", NONCE, BUNDLE_ID))
                .willReturn(new AppleIdTokenClaims(SUB, RELAY, true, true));
        given(tokenExchanger.exchange("auth-code", SUB)).willReturn("r.refresh-token");
        given(encryptor.encrypt("r.refresh-token")).willReturn("v1:cipher");
    }

    private static String codeOf(Throwable e) {
        return ((OAuthException) e).getErrorCode().getCode();
    }

    @Nested
    @DisplayName("스위치")
    class Switch {

        // 운영 기본값은 꺼짐이다. 꺼진 채로 nonce 가 발급되면 앱은 버튼을 보여 주고 마지막에 막힌다.
        @Test
        @DisplayName("꺼져 있으면 nonce 도 로그인도 OAUTH_PROVIDER_DISABLED 이고 아무것도 건드리지 않는다")
        void disabled_switch_rejects_everything() {
            assertThatThrownBy(() -> service(false).issueNonce())
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("OAUTH_PROVIDER_DISABLED");
            assertThatThrownBy(() -> service(false).login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("OAUTH_PROVIDER_DISABLED");

            then(nonceStore).shouldHaveNoInteractions();
            then(idTokenVerifier).shouldHaveNoInteractions();
            then(tokenExchanger).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("켜져 있으면 저장소가 만든 nonce 를 준다")
        void enabled_switch_issues_nonce() {
            given(nonceStore.generate()).willReturn("fresh-nonce");

            assertThat(service().issueNonce()).isEqualTo("fresh-nonce");
        }
    }

    @Nested
    @DisplayName("재전송 · 검증 실패")
    class Rejections {

        // 가로챈 identityToken 을 같은 nonce 로 다시 보내는 재전송. 검증기까지 가면 안 된다.
        @Test
        @DisplayName("nonce 가 소모됐거나 모르면 OAUTH_STATE_INVALID 이고 검증기를 부르지 않는다")
        void reused_nonce_short_circuits() {
            given(nonceStore.consume(NONCE)).willReturn(false);

            assertThatThrownBy(() -> service().login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("OAUTH_STATE_INVALID");

            then(idTokenVerifier).shouldHaveNoInteractions();
            then(tokenExchanger).shouldHaveNoInteractions();
            then(userAuthRepository).shouldHaveNoInteractions();
        }

        // 검증이 실패해도 nonce 는 이미 사라져 있어야 한다. 검증 뒤에 소모하면 실패한 시도의 nonce 가 남는다.
        @Test
        @DisplayName("nonce 는 검증보다 먼저 소모하고, 검증기에는 소모한 그 값을 넘긴다")
        void nonce_is_consumed_before_verification() {
            givenAppleAccepts();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, SUB))
                    .willReturn(Optional.empty());

            service().login("id-token", "auth-code", NONCE, null, null);

            InOrder order = Mockito.inOrder(nonceStore, idTokenVerifier, tokenExchanger);
            order.verify(nonceStore).consume(NONCE);
            order.verify(idTokenVerifier).verify("id-token", NONCE, BUNDLE_ID);
            order.verify(tokenExchanger).exchange("auth-code", SUB);
        }

        @Test
        @DisplayName("identityToken 검증이 실패하면 code 를 교환하지 않는다")
        void invalid_token_skips_exchange() {
            given(nonceStore.consume(NONCE)).willReturn(true);
            given(idTokenVerifier.verify("id-token", NONCE, BUNDLE_ID))
                    .willThrow(new OAuthException(AuthErrorCode.OAUTH_TOKEN_INVALID));

            assertThatThrownBy(() -> service().login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("OAUTH_TOKEN_INVALID");

            then(tokenExchanger).shouldHaveNoInteractions();
            then(userAuthRepository).shouldHaveNoInteractions();
        }

        // 교환 응답의 sub 가 다르다 = 남의 code 를 섞어 보냈다.
        @Test
        @DisplayName("교환 단계의 sub 불일치는 OAUTH_TOKEN_INVALID 이고 계정을 찾지도 않는다")
        void sub_mismatch_stops_before_lookup() {
            given(nonceStore.consume(NONCE)).willReturn(true);
            given(idTokenVerifier.verify("id-token", NONCE, BUNDLE_ID)).willReturn(new AppleIdTokenClaims(SUB, RELAY, true, true));
            given(tokenExchanger.exchange("auth-code", SUB))
                    .willThrow(new OAuthException(AuthErrorCode.OAUTH_TOKEN_INVALID));

            assertThatThrownBy(() -> service().login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("OAUTH_TOKEN_INVALID");

            then(userAuthRepository).shouldHaveNoInteractions();
            then(tempTokenStore).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Apple 장애는 OAUTH_PROVIDER_UNAVAILABLE 그대로 올라가고 아무것도 남기지 않는다")
        void apple_outage_propagates() {
            given(nonceStore.consume(NONCE)).willReturn(true);
            given(idTokenVerifier.verify("id-token", NONCE, BUNDLE_ID)).willReturn(new AppleIdTokenClaims(SUB, RELAY, true, true));
            given(tokenExchanger.exchange("auth-code", SUB))
                    .willThrow(new OAuthException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE));

            assertThatThrownBy(() -> service().login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("OAUTH_PROVIDER_UNAVAILABLE");

            then(encryptor).shouldHaveNoInteractions();
            then(tempTokenStore).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("합류")
    class Join {

        // 같은 이메일 · 같은 번호의 기존 계정이 있어도 여기서는 모른다. 판정은 link 의 번호 판정 하나다.
        @Test
        @DisplayName("처음 보는 Apple 신원이면 휴대폰 인증을 요구하고, 임시 증표에는 토큰 암호문만 싣는다")
        void new_identity_holds_encrypted_token() {
            givenAppleAccepts();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, SUB))
                    .willReturn(Optional.empty());
            given(tempTokenStore.save(any(OAuthTempData.class))).willReturn("temp-1");

            OAuthLoginResponse response = service().login("id-token", "auth-code", NONCE, "Van A", "NGUYEN");

            assertThat(response.phoneVerificationRequired()).isTrue();
            assertThat(response.oauthToken()).isEqualTo("temp-1");
            ArgumentCaptor<OAuthTempData> captor = ArgumentCaptor.forClass(OAuthTempData.class);
            then(tempTokenStore).should().save(captor.capture());
            OAuthTempData temp = captor.getValue();
            assertThat(temp.provider()).isEqualTo(AuthType.APPLE);
            assertThat(temp.providerUserId()).isEqualTo(SUB);
            assertThat(temp.email()).isEqualTo(RELAY);
            assertThat(temp.name()).isEqualTo("Van A NGUYEN");
            // Redis 에 평문 refresh_token 이 들어가면 안 된다.
            assertThat(temp.encryptedProviderRefreshToken()).isEqualTo("v1:cipher");
            // 철회를 번들 ID 로 보내야 하므로 그 값도 같이 싣는다.
            assertThat(temp.providerClientId()).isEqualTo(BUNDLE_ID);
            assertThat(temp.toString()).doesNotContain("r.refresh-token");
            then(tokenIssuer).shouldHaveNoInteractions();
        }

        // 철회할 때는 가장 최근에 받은 토큰이 살아 있는 값이다.
        @Test
        @DisplayName("이미 붙은 Apple 신원이면 바로 로그인시키고 저장된 토큰을 새 암호문으로 바꾼다")
        void known_identity_replaces_token() {
            givenAppleAccepts();
            User user = user(UserStatus.ACTIVE);
            UserAuth auth = UserAuth.builder().user(user).authType(AuthType.APPLE).identifier(SUB)
                    .providerRefreshToken("v1:old").build();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, SUB))
                    .willReturn(Optional.of(auth));
            given(tokenIssuer.issue(user)).willReturn(new TokenPair("at", "rt", 1800));

            OAuthLoginResponse response = service().login("id-token", "auth-code", NONCE, null, null);

            assertThat(response.phoneVerificationRequired()).isFalse();
            assertThat(response.signIn().accessToken()).isEqualTo("at");
            assertThat(auth.getProviderRefreshToken()).isEqualTo("v1:cipher");
            assertThat(auth.getProviderClientId()).isEqualTo(BUNDLE_ID);
            then(tempTokenStore).should(never()).save(any());
        }

        @Test
        @DisplayName("정지 계정은 SUSPENDED_USER 이고 저장된 토큰도 바꾸지 않는다")
        void suspended_user_is_rejected() {
            givenAppleAccepts();
            UserAuth auth = UserAuth.builder().user(user(UserStatus.SUSPENDED)).authType(AuthType.APPLE)
                    .identifier(SUB).providerRefreshToken("v1:old").build();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, SUB))
                    .willReturn(Optional.of(auth));

            assertThatThrownBy(() -> service().login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("SUSPENDED_USER");

            assertThat(auth.getProviderRefreshToken()).isEqualTo("v1:old");
            then(tokenIssuer).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("활성이 아닌 계정은 USER_NOT_ACTIVE 다")
        void inactive_user_is_rejected() {
            givenAppleAccepts();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, SUB))
                    .willReturn(Optional.of(UserAuth.builder().user(user(UserStatus.WITHDRAWN))
                            .authType(AuthType.APPLE).identifier(SUB).build()));

            assertThatThrownBy(() -> service().login("id-token", "auth-code", NONCE, null, null))
                    .extracting(AppleNativeAuthServiceTest::codeOf).isEqualTo("USER_NOT_ACTIVE");
        }
    }

    @Nested
    @DisplayName("이름")
    class Name {

        private String nameFor(String given, String family) {
            givenAppleAccepts();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, SUB))
                    .willReturn(Optional.empty());
            given(tempTokenStore.save(any(OAuthTempData.class))).willReturn("temp-1");
            service().login("id-token", "auth-code", NONCE, given, family);
            ArgumentCaptor<OAuthTempData> captor = ArgumentCaptor.forClass(OAuthTempData.class);
            then(tempTokenStore).should().save(captor.capture());
            return captor.getValue().name();
        }

        // 두 번째 로그인부터 Apple 은 이름을 주지 않는다. 그래도 가입은 진행된다(users.name 은 NULL 허용).
        @Test
        @DisplayName("이름이 오지 않으면 null 이다")
        void missing_name_is_null() {
            assertThat(nameFor(null, null)).isNull();
        }

        @Test
        @DisplayName("공백만 오면 null 이다")
        void blank_name_is_null() {
            assertThat(nameFor("  ", " ")).isNull();
        }

        @Test
        @DisplayName("한쪽만 와도 공백을 정리해 쓴다")
        void single_part_is_trimmed() {
            assertThat(nameFor("  Van   A ", null)).isEqualTo("Van A");
        }

        // 두 칸이 각 50자라 합치면 users.name(100자)을 넘을 수 있다. 넘기면 가입이 DB 오류로 죽는다.
        @Test
        @DisplayName("합친 이름이 100자를 넘으면 100자로 자른다")
        void long_name_is_capped() {
            assertThat(nameFor("가".repeat(50), "나".repeat(50))).hasSize(100);
        }
    }

    @Test
    @DisplayName("Google 웹 로그인은 저장된 토큰 칸을 건드리지 않는다")
    void web_login_leaves_token_column() {
        User user = user(UserStatus.ACTIVE);
        UserAuth google = UserAuth.builder().user(user).authType(AuthType.GOOGLE).identifier("g-1").build();
        given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.GOOGLE, "g-1"))
                .willReturn(Optional.of(google));
        given(tokenIssuer.issue(user)).willReturn(new TokenPair("at", "rt", 1800));
        OAuthAuthService oAuthAuthService = new OAuthAuthService(tokenIssuer, new PkceValidator(), stateStore,
                signUpProcessor, tempTokenStore, oAuthClientRouter, userAuthRepository);

        oAuthAuthService.signInOrHold(AuthType.GOOGLE, new OAuthUserInfo("g-1", "a@b.com", null));

        assertThat(google.getProviderRefreshToken()).isNull();
        then(encryptor).shouldHaveNoInteractions();
        then(nonceStore).should(never()).consume(anyString());
    }
}
