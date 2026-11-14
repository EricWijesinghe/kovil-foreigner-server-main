package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.request.PasswordResetRequest;
import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.SignInException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final String TOKEN = "reset-token";
    private static final String HASH = "phone-hash";

    @Mock PhoneVerificationStore verificationStore;
    @Mock PhoneVerificationRepository verificationRepository;
    @Mock UserRepository userRepository;
    @Mock UserAuthRepository userAuthRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RefreshTokenService refreshTokenService;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(verificationStore, verificationRepository,
                userRepository, userAuthRepository, passwordEncoder, refreshTokenService,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
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

    private static PhoneVerification verification(VerificationPurpose purpose, boolean consumed) {
        PhoneVerification built = withId(PhoneVerification.builder()
                .phone("enc").phoneHash(HASH).purpose(purpose)
                .verifiedAt(LocalDateTime.of(2026, 8, 14, 8, 0)).build(), 40L);
        if (consumed) {
            built.consume(1L, LocalDateTime.of(2026, 8, 14, 8, 30));
        }
        return built;
    }

    private static User user(UserStatus status) {
        return withId(User.builder().uuid("u").name("n").phoneHash(HASH)
                .status(status).role(UserRole.USER).build(), 77L);
    }

    private static UserAuth localAuth(User user) {
        return withId(UserAuth.builder().user(user).authType(AuthType.LOCAL)
                .identifier("mail@example.invalid").passwordHash("old-hash").build(), 5L);
    }

    private void givenToken(PhoneVerification verification) {
        given(verificationStore.findVerificationIdByToken(TOKEN)).willReturn(Optional.of(40L));
        given(verificationRepository.findById(40L)).willReturn(Optional.of(verification));
    }

    private PasswordResetRequest request() {
        return new PasswordResetRequest(TOKEN, "newPassw0rd");
    }

    private static String codeOf(Throwable e) {
        return ((SignInException) e).getErrorCode().getCode();
    }

    // 재설정 = 해시 교체 + 인증 소비 + 전 세션 회수. 셋 중 하나라도 빠지면 보안 구멍이다.
    @Test
    @DisplayName("재설정은 비밀번호를 교체하고 인증을 소비하며 모든 세션을 끊는다")
    void reset_replaces_password_and_revokes_sessions() {
        PhoneVerification verification = verification(VerificationPurpose.PASSWORD_RESET, false);
        givenToken(verification);
        User user = user(UserStatus.ACTIVE);
        UserAuth auth = localAuth(user);
        given(userRepository.findByPhoneHash(HASH)).willReturn(Optional.of(user));
        given(userAuthRepository.findByUserIdAndAuthTypeAndDeletedAtIsNull(77L, AuthType.LOCAL))
                .willReturn(Optional.of(auth));
        given(passwordEncoder.encode("newPassw0rd")).willReturn("new-hash");

        service.reset(request());

        assertThat(auth.getPasswordHash()).isEqualTo("new-hash");
        assertThat(verification.isConsumed()).isTrue();
        then(verificationStore).should().deleteToken(TOKEN);
        then(refreshTokenService).should().revokeAllFor(77L);
    }

    /*
     * ★ Redis 토큰 삭제는 커밋 이후다. revokeAllFor 의 행 잠금 경합 등으로 롤백되면
     *   consume 은 되돌아가는데 키만 사라져 SMS 인증(과금)부터 다시 해야 한다
     *   (적대적 리뷰 2026-09-02). 즉시 삭제로 되돌리면 이 테스트가 죽는다.
     */
    @Test
    @DisplayName("인증 토큰은 커밋 전에 지우지 않는다 — 롤백이 Redis 를 되돌려 주지 않는다")
    void token_is_deleted_only_after_commit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            PhoneVerification verification = verification(VerificationPurpose.PASSWORD_RESET, false);
            givenToken(verification);
            User user = user(UserStatus.ACTIVE);
            given(userRepository.findByPhoneHash(HASH)).willReturn(Optional.of(user));
            given(userAuthRepository.findByUserIdAndAuthTypeAndDeletedAtIsNull(77L, AuthType.LOCAL))
                    .willReturn(Optional.of(localAuth(user)));
            given(passwordEncoder.encode("newPassw0rd")).willReturn("new-hash");

            service.reset(request());

            then(verificationStore).should(Mockito.never()).deleteToken(TOKEN);
            TransactionSynchronizationManager
                    .getSynchronizations().forEach(
                            TransactionSynchronization::afterCommit);
            then(verificationStore).should().deleteToken(TOKEN);
        } finally {
            TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    // 가입용 인증으로 남의 비밀번호를 바꾸는 경로 — 목적 불일치는 무효다.
    @Test
    @DisplayName("SIGNUP 용도의 토큰은 거절한다")
    void signup_purpose_token_rejected() {
        givenToken(verification(VerificationPurpose.SIGNUP, false));

        assertThatThrownBy(() -> service.reset(request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("VERIFICATION_TOKEN_INVALID"));
        then(userRepository).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미 소비된 인증은 재사용할 수 없다")
    void consumed_token_rejected() {
        givenToken(verification(VerificationPurpose.PASSWORD_RESET, true));

        assertThatThrownBy(() -> service.reset(request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("VERIFICATION_ALREADY_USED"));
    }

    @Test
    @DisplayName("가입되지 않은 번호는 404다")
    void unregistered_phone_is_404() {
        givenToken(verification(VerificationPurpose.PASSWORD_RESET, false));
        given(userRepository.findByPhoneHash(HASH)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RESET_ACCOUNT_NOT_FOUND"));
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    // 소셜 전용 계정엔 바꿀 비밀번호가 없다 — 어느 로그인인지 알려주는 게 유일한 도움이다.
    @Test
    @DisplayName("소셜 전용 계정은 409로 안내한다")
    void social_only_account_conflicts() {
        givenToken(verification(VerificationPurpose.PASSWORD_RESET, false));
        given(userRepository.findByPhoneHash(HASH)).willReturn(Optional.of(user(UserStatus.ACTIVE)));
        given(userAuthRepository.findByUserIdAndAuthTypeAndDeletedAtIsNull(77L, AuthType.LOCAL))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RESET_NOT_LOCAL_ACCOUNT"));
    }

    /*
     * ★ 답은 USER_NOT_ACTIVE 가 아니라 SUSPENDED_USER 다 — 이 분기에 도달하는 상태는
     *   정지뿐이고(탈퇴는 phone_hash 가 비어 조회에서 걸러진다), 정지자가 SMS(과금)까지
     *   쓴 뒤 원인 불명의 일반 오류를 보면 안 된다. signIn 과 같은 규칙이다
     *   (행동 변경 2026-09-02, 적대적 리뷰).
     */
    @Test
    @DisplayName("정지 계정은 SUSPENDED_USER 로 거절한다 — 고객센터 안내가 답이다")
    void suspended_account_rejected() {
        givenToken(verification(VerificationPurpose.PASSWORD_RESET, false));
        given(userRepository.findByPhoneHash(HASH)).willReturn(Optional.of(user(UserStatus.SUSPENDED)));

        assertThatThrownBy(() -> service.reset(request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("SUSPENDED_USER"));
        then(passwordEncoder).shouldHaveNoInteractions();
        then(refreshTokenService).should(never()).revokeAllFor(anyLong());
    }

    @Test
    @DisplayName("무효 토큰은 400이고 어떤 상태도 바꾸지 않는다")
    void invalid_token_touches_nothing() {
        given(verificationStore.findVerificationIdByToken(TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("VERIFICATION_TOKEN_INVALID"));
        then(verificationStore).should(never()).deleteToken(any());
    }
}
