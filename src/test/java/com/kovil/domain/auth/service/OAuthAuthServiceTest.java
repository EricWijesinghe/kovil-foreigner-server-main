package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.auth.exception.SignUpException;
import com.kovil.domain.auth.service.support.SignUpProcessor;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.infra.oauth.OAuthClient;
import com.kovil.infra.oauth.OAuthClientRouter;
import com.kovil.infra.oauth.OAuthReturnTarget;
import com.kovil.infra.oauth.OAuthStateStore;
import com.kovil.infra.oauth.OAuthTempTokenStore;
import com.kovil.infra.oauth.PkceValidator;
import com.kovil.infra.oauth.dto.OAuthTempData;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OAuthAuthServiceTest {

    private static final AuthType PROVIDER = AuthType.APPLE;
    private static final String SUB = "apple-sub-1";
    private static final String STATE = "state-1";
    // 실제 S256 짝 (RFC 7636 §4.2). PkceValidator 는 목이 아니라 진짜를 쓴다 —
    // 여기서 목을 쓰면 "검증을 부르긴 하는데 계산은 틀린" 상태를 놓친다.
    private static final String VERIFIER = "wonjoomin-foreigner-pkce-test-verifier-0123456789";
    private static final String CHALLENGE = "EI7uJUdGyTH_Hx7H28FU3awPzco6gxxv10wChDLeuPg";

    @Mock TokenIssuer tokenIssuer;
    @Mock OAuthStateStore stateStore;
    @Mock SignUpProcessor signUpProcessor;
    @Mock OAuthTempTokenStore tempTokenStore;
    @Mock OAuthClientRouter oAuthClientRouter;
    @Mock UserAuthRepository userAuthRepository;
    @Mock OAuthClient oAuthClient;

    private OAuthAuthService service;

    @BeforeEach
    void setUp() {
        service = new OAuthAuthService(tokenIssuer, new PkceValidator(), stateStore, signUpProcessor,
                tempTokenStore, oAuthClientRouter, userAuthRepository);
    }

    private static User user(UserStatus status) {
        return withId(User.builder().uuid("uuid-1").role(UserRole.USER)
                .status(status).preferredLanguage("ko").build(), 7L);
    }

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    @Nested
    @DisplayName("소셜 로그인")
    class Login {

        @BeforeEach
        void stubClient() {
            given(stateStore.consume(STATE)).willReturn(CHALLENGE);
            given(oAuthClientRouter.getClient(PROVIDER)).willReturn(oAuthClient);
            given(oAuthClient.getUserInfo("code", STATE, null, VERIFIER))
                    .willReturn(new OAuthUserInfo(SUB, "a@b.com", null));
        }

        @Test
        @DisplayName("이미 붙어 있는 신원이면 바로 로그인시킨다")
        void known_identity_logs_in() {
            User user = user(UserStatus.ACTIVE);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.of(UserAuth.builder().user(user)
                            .authType(PROVIDER).identifier(SUB).build()));
            given(tokenIssuer.issue(user)).willReturn(new TokenPair("at", "rt", 1800));

            OAuthLoginResponse response = service.login(PROVIDER, "code", STATE, null, VERIFIER);

            assertThat(response.phoneVerificationRequired()).isFalse();
            assertThat(response.signIn().accessToken()).isEqualTo("at");
            then(tempTokenStore).should(never()).save(any());
        }

        // 웹으로 들어온 Apple 토큰은 Services ID 로만 철회된다. 토큰과 client_id 가 어긋나면 탈퇴 철회가 조용히 실패한다.
        @Test
        @DisplayName("Apple 웹 로그인은 기존 수단 행의 토큰 암호문과 client_id 를 함께 갈아 끼운다")
        void web_apple_login_replaces_token_with_its_client_id() {
            given(oAuthClient.getUserInfo("code", STATE, null, VERIFIER))
                    .willReturn(new OAuthUserInfo(SUB, "a@b.com", null, "v1:web", "com.kovil.web"));
            User user = user(UserStatus.ACTIVE);
            UserAuth auth = UserAuth.builder().user(user).authType(PROVIDER).identifier(SUB)
                    .providerRefreshToken("v1:native").providerClientId("com.kovil.app").build();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.of(auth));
            given(tokenIssuer.issue(user)).willReturn(new TokenPair("at", "rt", 1800));

            service.login(PROVIDER, "code", STATE, null, VERIFIER);

            assertThat(auth.getProviderRefreshToken()).isEqualTo("v1:web");
            assertThat(auth.getProviderClientId()).isEqualTo("com.kovil.web");
        }

        @Test
        @DisplayName("처음 보는 Apple 웹 신원은 임시 증표에 토큰 암호문과 client_id 를 싣는다")
        void web_apple_new_identity_holds_token_and_client_id() {
            given(oAuthClient.getUserInfo("code", STATE, null, VERIFIER))
                    .willReturn(new OAuthUserInfo(SUB, "a@b.com", "Van A", "v1:web", "com.kovil.web"));
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tempTokenStore.save(any(OAuthTempData.class))).willReturn("temp-1");

            service.login(PROVIDER, "code", STATE, null, VERIFIER);

            ArgumentCaptor<OAuthTempData> captor = ArgumentCaptor.forClass(OAuthTempData.class);
            then(tempTokenStore).should().save(captor.capture());
            assertThat(captor.getValue().encryptedProviderRefreshToken()).isEqualTo("v1:web");
            assertThat(captor.getValue().providerClientId()).isEqualTo("com.kovil.web");
            assertThat(captor.getValue().name()).isEqualTo("Van A");
        }

        // FR-104 — 소셜 계정만으로는 한국 번호를 확인할 수 없고, 그게 우리 신원 앵커다.
        @Test
        @DisplayName("처음 보는 신원이면 로그인시키지 않고 휴대폰 인증을 요구한다")
        void new_identity_requires_phone_verification() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tempTokenStore.save(any(OAuthTempData.class))).willReturn("temp-1");

            OAuthLoginResponse response = service.login(PROVIDER, "code", STATE, null, VERIFIER);

            assertThat(response.phoneVerificationRequired()).isTrue();
            assertThat(response.oauthToken()).isEqualTo("temp-1");
            assertThat(response.signIn()).isNull();
            then(tokenIssuer).shouldHaveNoInteractions();
        }

        /*
         * 정지 계정은 소셜로도 못 들어온다. **탈퇴 계정은 여기 오지 않는다** —
         * 탈퇴가 user_auth 를 해제하므로 이 조회(deletedAt IS NULL)에 걸리지 않고
         * 신규 신원으로 취급되어 재가입 경로를 탄다(PO 확정 2026-09-01).
         */
        @Test
        @DisplayName("정지 계정은 소셜로도 들어오지 못한다 — 정지된 계정이라고 알린다")
        void suspended_user_cannot_login() {
            User suspended = user(UserStatus.SUSPENDED);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.of(UserAuth.builder().user(suspended)
                            .authType(PROVIDER).identifier(SUB).build()));

            assertThatThrownBy(() -> service.login(PROVIDER, "code", STATE, null, VERIFIER))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("SUSPENDED_USER");
        }
    }

    // 이름은 여기서 임시 저장소에 실려 번호 인증을 기다린다 — 여기서 빠지면 link가 받을 수 없다.
    @Test
    @DisplayName("provider가 준 이름이 임시 증표에 실린다")
    void provider_name_is_carried_in_temp_data() {
        given(stateStore.consume(STATE)).willReturn(CHALLENGE);
        given(oAuthClientRouter.getClient(PROVIDER)).willReturn(oAuthClient);
        given(oAuthClient.getUserInfo("code", STATE, "{\"name\":{\"firstName\":\"Van A\"}}", VERIFIER))
                .willReturn(new OAuthUserInfo(SUB, "a@b.com", "NGUYEN VAN A"));
        given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                .willReturn(Optional.empty());

        service.login(PROVIDER, "code", STATE, "{\"name\":{\"firstName\":\"Van A\"}}", VERIFIER);

        ArgumentCaptor<OAuthTempData> captor = ArgumentCaptor.forClass(OAuthTempData.class);
        then(tempTokenStore).should().save(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("NGUYEN VAN A");
    }

    // state를 검증하지 않으면 CSRF로 남의 소셜 계정을 붙일 수 있다.
    @Test
    @DisplayName("state가 유효하지 않으면 provider를 호출조차 하지 않는다")
    void invalid_state_short_circuits() {
        given(stateStore.consume("bad")).willReturn(null);

        assertThatThrownBy(() -> service.login(PROVIDER, "code", "bad", null, VERIFIER))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_STATE_INVALID");

        then(oAuthClientRouter).shouldHaveNoInteractions();
    }

    @Nested
    @DisplayName("PKCE — 가로챈 code 무력화")
    class Pkce {

        /*
         * ★ 이 프로젝트에서 가장 중요한 소셜 로그인 방어다.
         *
         * 되돌림 페이지가 code·state 를 `kovil://` 로 넘기는데, 안드로이드에서는
         * 같은 스킴을 등록한 다른 앱이 그 값을 함께 받을 수 있다(RFC 8252 §8.1).
         * 그 앱이 훔친 code·state 로 여기를 호출하는 것이 정확한 공격 시나리오이고,
         * verifier 는 브라우저를 지나간 적이 없으므로 그쪽에는 없다.
         */
        @Test
        @DisplayName("verifier 가 1단계 challenge 와 맞지 않으면 provider 를 호출조차 하지 않는다")
        void mismatched_verifier_short_circuits() {
            given(stateStore.consume(STATE)).willReturn(CHALLENGE);

            assertThatThrownBy(() ->
                    service.login(PROVIDER, "code", STATE, null, "b".repeat(48)))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_PKCE_INVALID");

            then(oAuthClientRouter).shouldHaveNoInteractions();
        }

        // code·state 만 가로챈 앱에는 verifier 가 없다 — 빈 값으로 통과되면 방어가 통째로 무의미하다.
        @Test
        @DisplayName("verifier 가 아예 없으면 거부한다")
        void missing_verifier_is_rejected() {
            given(stateStore.consume(STATE)).willReturn(CHALLENGE);

            assertThatThrownBy(() -> service.login(PROVIDER, "code", STATE, null, null))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_PKCE_INVALID");

            then(oAuthClientRouter).shouldHaveNoInteractions();
        }

        /*
         * ★ 이번 방어의 본체. 서버 자체 대조만으로는 뚫린다 — 공격자가 공개 authorize 로
         *   **자기 state·challenge** 를 발급받아 자기 verifier 와 짝을 맞춘 뒤 훔친 피해자 code 를
         *   실어 보내면 그 대조를 통과하고, 서버가 client_secret 으로 교환해 피해자 토큰을 내준다
         *   (실증 2026-09-01: 격리 서버에서 피해자 계정 탈취 재현).
         *   code 를 발급한 provider 만 code↔challenge 결속을 검사할 수 있으므로
         *   **challenge 는 인가 URL 로, verifier 는 토큰 교환으로** 반드시 넘어가야 한다.
         */
        @Test
        @DisplayName("verifier 가 provider 의 토큰 교환까지 넘어간다 — 서버 대조만으로는 못 막는다")
        void verifier_reaches_the_provider() {
            given(stateStore.consume(STATE)).willReturn(CHALLENGE);
            given(oAuthClientRouter.getClient(PROVIDER)).willReturn(oAuthClient);
            given(oAuthClient.getUserInfo("code", STATE, null, VERIFIER))
                    .willReturn(new OAuthUserInfo(SUB, "a@b.com", null));
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tempTokenStore.save(any(OAuthTempData.class))).willReturn("temp-1");

            service.login(PROVIDER, "code", STATE, null, VERIFIER);

            then(oAuthClient).should().getUserInfo("code", STATE, null, VERIFIER);
        }

        @Test
        @DisplayName("1단계가 challenge 를 state 에 묶어 인가 URL 을 만든다")
        void authorize_binds_challenge_to_state() {
            given(stateStore.generate(CHALLENGE, OAuthReturnTarget.APP)).willReturn(STATE);
            given(oAuthClientRouter.getClient(PROVIDER)).willReturn(oAuthClient);
            given(oAuthClient.buildAuthorizationUri(STATE, CHALLENGE)).willReturn("https://provider/auth?state=" + STATE);

            assertThat(service.buildAuthorizationUri(PROVIDER, CHALLENGE, OAuthReturnTarget.APP))
                    .isEqualTo("https://provider/auth?state=" + STATE);
            // challenge 가 provider 로 넘어가야 provider 가 code 를 그 값에 묶는다.
            then(oAuthClient).should().buildAuthorizationUri(STATE, CHALLENGE);
        }

        // 돌아갈 곳은 state 와 한 몸이어야 한다. 요청마다 따로 받으면 되돌림 페이지가 남의 말을 믿게 된다.
        @Test
        @DisplayName("웹으로 돌아갈 시도는 그 목적지를 state 에 함께 묶는다")
        void web_target_is_bound_to_state() {
            given(stateStore.generate(CHALLENGE, OAuthReturnTarget.WEB)).willReturn(STATE);
            given(oAuthClientRouter.getClient(PROVIDER)).willReturn(oAuthClient);
            given(oAuthClient.buildAuthorizationUri(STATE, CHALLENGE)).willReturn("https://provider/auth?state=" + STATE);

            service.buildAuthorizationUri(PROVIDER, CHALLENGE, OAuthReturnTarget.WEB);

            then(stateStore).should().generate(CHALLENGE, OAuthReturnTarget.WEB);
        }

        // 잠긴 수단이면 쓰이지 않을 state 를 Redis 에 쌓지 않는다.
        @Test
        @DisplayName("잠긴 수단이면 state 를 발급하지 않는다")
        void disabled_provider_issues_no_state() {
            given(oAuthClientRouter.getClient(PROVIDER))
                    .willThrow(new OAuthException(AuthErrorCode.OAUTH_PROVIDER_DISABLED));

            assertThatThrownBy(() -> service.buildAuthorizationUri(PROVIDER, CHALLENGE, OAuthReturnTarget.WEB))
                    .isInstanceOf(OAuthException.class);
            then(stateStore).shouldHaveNoInteractions();
        }

        // 형식이 깨진 challenge 를 저장하면 2단계에서 무엇과도 짝이 맞지 않아 로그인이 통째로 막힌다.
        // 그 실패를 1단계에서 즉시 드러낸다.
        @Test
        @DisplayName("challenge 형식이 어긋나면 state 를 발급하지 않는다")
        void malformed_challenge_is_rejected_upfront() {
            assertThatThrownBy(() -> service.buildAuthorizationUri(PROVIDER, "too-short", OAuthReturnTarget.APP))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_PKCE_INVALID");

            then(stateStore).shouldHaveNoInteractions();
            then(oAuthClientRouter).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("휴대폰 인증 후 가입 확정")
    class Link {

        @BeforeEach
        void stubTempToken() {
            given(tempTokenStore.find("temp-1"))
                    .willReturn(Optional.of(new OAuthTempData(PROVIDER, SUB, "a@b.com", null)));
        }

        private void givenCreated(User created) {
            given(signUpProcessor.createUser("ver-1", null, null, "ko", UserRole.USER)).willReturn(created);
        }

        /*
         * ★ 같은 번호 계정에 붙이지 않는다. 휴대폰 인증은 같은 사람인지 알려 주지 않아서,
         *   번호를 넘겨받은 사람이 이전 주인의 계정에 들어갈 수 있다.
         *   로컬 가입과 같은 판정(createUser)을 지나야 하고, 거절이면 아무것도 남기지 않는다.
         */
        @Test
        @DisplayName("인증된 번호의 계정이 이미 있으면 DUPLICATE_PHONE 으로 거절하고 아무것도 붙이지 않는다")
        void existing_phone_is_rejected() {
            given(signUpProcessor.createUser("ver-1", null, null, "ko", UserRole.USER))
                    .willThrow(new SignUpException(AuthErrorCode.DUPLICATE_PHONE));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("DUPLICATE_PHONE");

            then(userAuthRepository).shouldHaveNoInteractions();
            then(tokenIssuer).shouldHaveNoInteractions();
            then(tempTokenStore).should(never()).delete(anyString());
        }

        // ★ 페이스북 표준 액세스는 실사용자에게 email 을 주지 않는다. 그대로 통과하면 그 사용자는
        //   이메일 로그인도 비밀번호 재설정도 못 한다. 개발자 계정에서는 이메일이 정상으로 오기 때문에
        //   테스트로 고정하지 않으면 출시 후에야 드러난다.
        @Test
        @DisplayName("이메일이 없으면 가입을 끊는다")
        void missing_email_is_rejected() {
            given(tempTokenStore.find("temp-1"))
                    .willReturn(Optional.of(new OAuthTempData(PROVIDER, SUB, null, null)));
            givenCreated(user(UserStatus.ACTIVE));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_EMAIL_REQUIRED");

            then(userAuthRepository).should(never()).saveAndFlush(any(UserAuth.class));
        }

        @Test
        @DisplayName("이메일이 공백 문자열이어도 끊는다")
        void blank_email_is_rejected() {
            given(tempTokenStore.find("temp-1"))
                    .willReturn(Optional.of(new OAuthTempData(PROVIDER, SUB, "   ", null)));
            givenCreated(user(UserStatus.ACTIVE));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_EMAIL_REQUIRED");
        }

        // 번호 판정이 이메일 판정보다 먼저다. 이미 가입된 번호에 "다른 방법으로 가입하라"를 주면
        // 사용자는 가입 화면에서 다시 DUPLICATE_PHONE 을 만난다.
        @Test
        @DisplayName("이미 가입된 번호면 이메일이 없어도 DUPLICATE_PHONE 이 먼저다")
        void duplicate_phone_wins_over_missing_email() {
            given(tempTokenStore.find("temp-1"))
                    .willReturn(Optional.of(new OAuthTempData(PROVIDER, SUB, null, null)));
            given(signUpProcessor.createUser("ver-1", null, null, "ko", UserRole.USER))
                    .willThrow(new SignUpException(AuthErrorCode.DUPLICATE_PHONE));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("DUPLICATE_PHONE");
        }

        // 이메일은 계정 생성에 넘기지 않고 생성 뒤에 채운다 — 이메일 판정을 번호 판정 뒤에 두려는 것이다.
        @Test
        @DisplayName("처음 보는 번호면 USER 계정을 만들고 이메일 · 수단을 붙인다")
        void new_phone_creates_account() {
            User created = user(UserStatus.ACTIVE);
            givenCreated(created);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

            assertThat(service.link("temp-1", "ver-1", "ko").accessToken()).isEqualTo("at");

            then(signUpProcessor).should().createUser("ver-1", null, null, "ko", UserRole.USER);
            assertThat(created.getEmail()).isEqualTo("a@b.com");
            ArgumentCaptor<UserAuth> captor = ArgumentCaptor.forClass(UserAuth.class);
            then(userAuthRepository).should().saveAndFlush(captor.capture());
            assertThat(captor.getValue().getUser()).isSameAs(created);
            assertThat(captor.getValue().getAuthType()).isEqualTo(PROVIDER);
            assertThat(captor.getValue().getIdentifier()).isEqualTo(SUB);
            // 소셜은 비밀번호가 없다.
            assertThat(captor.getValue().getPasswordHash()).isNull();
        }

        // ★ 프로바이더가 준 이름은 **신규 계정 생성에 실려야** 한다. 여기서 흘리면
        //   소셜 가입자는 이름이 영영 비고, 관리자가 화면에서 읽은 이름으로 검색해도 못 찾는다.
        @Test
        @DisplayName("프로바이더가 준 이름이 계정 생성으로 넘어간다")
        void provider_name_reaches_account_creation() {
            // 공용 스텁을 이름 있는 값으로 덮는다(새 토큰 id를 쓰면 공용 스텁이 미사용으로 남는다).
            given(tempTokenStore.find("temp-1"))
                    .willReturn(Optional.of(new OAuthTempData(PROVIDER, SUB, "a@b.com", "NGUYEN VAN A")));
            User created = user(UserStatus.ACTIVE);
            given(signUpProcessor.createUser("ver-1", "NGUYEN VAN A", null, "ko", UserRole.USER))
                    .willReturn(created);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

            service.link("temp-1", "ver-1", "ko");

            then(signUpProcessor).should().createUser("ver-1", "NGUYEN VAN A", null, "ko", UserRole.USER);
        }

        // 탈퇴 때 Apple 에 철회를 보내려면 가입 순간의 refresh_token 이 수단 행에 있어야 한다. 여기서 흘리면 영영 못 끊는다.
        @Test
        @DisplayName("임시 증표의 Apple 토큰 암호문이 수단 행에 그대로 저장된다")
        void provider_refresh_token_is_stored_with_link() {
            given(tempTokenStore.find("temp-1"))
                    .willReturn(Optional.of(new OAuthTempData(PROVIDER, SUB, "x@privaterelay.appleid.com", null,
                            "v1:cipher", "com.kovil.web")));
            User created = user(UserStatus.ACTIVE);
            givenCreated(created);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

            service.link("temp-1", "ver-1", "ko");

            ArgumentCaptor<UserAuth> captor = ArgumentCaptor.forClass(UserAuth.class);
            then(userAuthRepository).should().saveAndFlush(captor.capture());
            assertThat(captor.getValue().getProviderRefreshToken()).isEqualTo("v1:cipher");
            // 철회는 이 토큰을 낸 client_id 로 보내야 한다. 웹 가입이면 Services ID 다.
            assertThat(captor.getValue().getProviderClientId()).isEqualTo("com.kovil.web");
            assertThat(created.getEmail()).isEqualTo("x@privaterelay.appleid.com");
        }

        // 칸이 늘기 전에 저장된 증표(배포 순간의 10분)와 Google 은 토큰이 없다. 그래도 가입은 된다.
        @Test
        @DisplayName("토큰 암호문이 없는 증표도 가입되고 칸은 비어 있다")
        void link_without_provider_token_leaves_column_empty() {
            User created = user(UserStatus.ACTIVE);
            givenCreated(created);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

            service.link("temp-1", "ver-1", "ko");

            ArgumentCaptor<UserAuth> captor = ArgumentCaptor.forClass(UserAuth.class);
            then(userAuthRepository).should().saveAndFlush(captor.capture());
            assertThat(captor.getValue().getProviderRefreshToken()).isNull();
            assertThat(captor.getValue().getProviderClientId()).isNull();
        }

        // 애플은 이름을 안 줄 수 있다(최초 인가가 아니면 아예 안 온다). 그래도 가입은 되어야 한다.
        @Test
        @DisplayName("이름이 없어도 가입은 진행된다")
        void missing_name_still_signs_up() {
            User created = user(UserStatus.ACTIVE);
            givenCreated(created);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

            assertThat(service.link("temp-1", "ver-1", "ko").accessToken()).isEqualTo("at");
        }

        /*
         * ★ 살아 있는 행은 뺏어 오지 않는다. 임시 토큰 두 개를 받아 서로 다른 번호로 연결을 두 번
         *   끝내면 — 남의 도움 없이 혼자서 — 먼저 만든 계정이 **유일한 로그인 수단을 잃는다.**
         *   그 계정은 로그인도 탈퇴도 못 하고, 붙잡고 있는 번호 슬롯 때문에 그 번호로 새 계정도 못 만든다.
         */
        @Test
        @DisplayName("살아 있는 남의 소셜 수단이 있으면 거부한다 — 계정이 잠기는 것을 막는다")
        void rejects_when_another_account_owns_the_link() {
            User other = user(UserStatus.ACTIVE);
            UserAuth live = UserAuth.builder()
                    .user(other).authType(PROVIDER).identifier(SUB).build();
            User rejoined = withId(User.builder().uuid("uuid-2").role(UserRole.USER)
                    .status(UserStatus.ACTIVE).preferredLanguage("ko").build(), 8L);
            givenCreated(rejoined);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.of(live));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("OAUTH_ALREADY_LINKED");

            assertThat(live.getUser()).isSameAs(other);
            then(userAuthRepository).should(never()).saveAndFlush(any(UserAuth.class));
        }

        /*
         * ★ 재가입의 정본. 탈퇴한 계정의 옛 행은 **건드리지 않고 새 행을 넣는다.**
         *   유니크가 살아 있는 행에만 걸려 있어(V19 의 active_identifier) 같은 신원이어도
         *   INSERT 가 막히지 않고, 옛 행은 옛 계정에 남아 가입 이력이 된다.
         */
        @Test
        @DisplayName("해제된 옛 행은 건드리지 않고 새 행을 넣는다 — 재가입의 정본")
        void inserts_a_new_row_for_resignup() {
            User rejoined = user(UserStatus.ACTIVE);
            givenCreated(rejoined);
            // 해제된 옛 행은 이 조회(deletedAt IS NULL)에 잡히지 않는다.
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(rejoined)).willReturn(new TokenPair("at", "rt", 1800));

            service.link("temp-1", "ver-1", "ko");

            ArgumentCaptor<UserAuth> captor = ArgumentCaptor.forClass(UserAuth.class);
            then(userAuthRepository).should().saveAndFlush(captor.capture());
            assertThat(captor.getValue().getUser()).isSameAs(rejoined);
            assertThat(captor.getValue().getIdentifier()).isEqualTo(SUB);
        }

        /*
         * ★ 경합 번역. 조회와 INSERT 사이에 다른 요청이 같은 신원을 먼저 붙이면 유니크가 잡는데,
         *   그대로 두면 사용자에게 500 이 나간다(실측 2026-09-01).
         *   방금 만든 계정에는 수단이 없으니 먼저 붙인 쪽은 늘 다른 계정이다. 다만 rollback-only 라
         *   다시 조회할 수 없어 재시도 가능함만 전하고, 재시도하면 OAUTH_ALREADY_LINKED 를 받는다.
         */
        @Test
        @DisplayName("수단 유니크 경합은 500 이 아니라 CONFLICT 다")
        void unique_violation_becomes_a_retryable_conflict() {
            givenCreated(user(UserStatus.ACTIVE));
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(userAuthRepository.saveAndFlush(any(UserAuth.class)))
                    .willThrow(new DataIntegrityViolationException("dup",
                            new ConstraintViolationException(
                                    "dup", null, "uq_user_auth_active")));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(OAuthException.class)
                    .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                    .isEqualTo("CONFLICT");
        }

        // 제약 이름을 확인하지 않고 싸잡으면 엉뚱한 위반(FK·다른 유니크)이 "연결 충돌"로 둔갑한다.
        @Test
        @DisplayName("수단 유니크가 아닌 위반은 번역하지 않고 그대로 올려보낸다")
        void other_constraint_is_not_translated() {
            givenCreated(user(UserStatus.ACTIVE));
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(userAuthRepository.saveAndFlush(any(UserAuth.class)))
                    .willThrow(new DataIntegrityViolationException("fk",
                            new ConstraintViolationException(
                                    "fk", null, "fk_user_auth_user")));

            assertThatThrownBy(() -> service.link("temp-1", "ver-1", "ko"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /*
         * ★ 삭제는 커밋 이후다. 이 뒤의 토큰 발급·커밋이 실패해 롤백되면 DB 는 되돌아가는데
         *   Redis 키만 사라져, 같은 oauthToken 재시도가 OAUTH_TEMP_TOKEN_EXPIRED 로 끊긴다
         *   (적대적 리뷰 2026-09-02). 즉시 삭제로 되돌리면 이 테스트가 죽는다.
         */
        @Test
        @DisplayName("임시 토큰은 커밋 전에 지우지 않는다 — 롤백이 Redis 를 되돌려 주지 않는다")
        void temp_token_is_deleted_only_after_commit() {
            TransactionSynchronizationManager.initSynchronization();
            try {
                User created = user(UserStatus.ACTIVE);
                givenCreated(created);
                given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                        .willReturn(Optional.empty());
                given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

                service.link("temp-1", "ver-1", "ko");

                then(tempTokenStore).should(Mockito.never()).delete("temp-1");
                TransactionSynchronizationManager
                        .getSynchronizations().forEach(
                                TransactionSynchronization::afterCommit);
                then(tempTokenStore).should().delete("temp-1");
            } finally {
                TransactionSynchronizationManager
                        .clearSynchronization();
            }
        }

        @Test
        @DisplayName("성공하면 임시 토큰을 지운다 — 같은 토큰으로 두 번 붙일 수 없다")
        void temp_token_is_deleted() {
            User created = user(UserStatus.ACTIVE);
            givenCreated(created);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(PROVIDER, SUB))
                    .willReturn(Optional.empty());
            given(tokenIssuer.issue(created)).willReturn(new TokenPair("at", "rt", 1800));

            service.link("temp-1", "ver-1", "ko");

            then(tempTokenStore).should().delete("temp-1");
        }
    }

    @Test
    @DisplayName("임시 토큰이 만료됐으면 인증 소비 없이 거절한다")
    void expired_temp_token_consumes_nothing() {
        given(tempTokenStore.find("gone")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.link("gone", "ver-1", "ko"))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_TEMP_TOKEN_EXPIRED");

        then(signUpProcessor).shouldHaveNoInteractions();
    }
}
