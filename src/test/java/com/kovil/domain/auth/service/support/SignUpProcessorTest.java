package com.kovil.domain.auth.service.support;

import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.service.AccountBlockService;
import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.SignUpException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.domain.language.service.LanguageQueryService;
import com.kovil.domain.user.service.NicknameService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.Assertions;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Nested;
import org.mockito.BDDMockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SignUpProcessorTest {

    private static final String TOKEN = "verification-token";
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Mock PhoneVerificationStore verificationStore;
    @Mock PhoneVerificationRepository verificationRepository;
    @Mock UserRepository userRepository;
    @Mock AccountBlockService accountBlockService;
    @Mock LanguageQueryService languageQueryService;
    @Mock NicknameService nicknameService;

    private SignUpProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SignUpProcessor(verificationStore, verificationRepository, userRepository,
                accountBlockService, languageQueryService, nicknameService,
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

    private PhoneVerification verification(LocalDateTime consumedAt) {
        PhoneVerification v = withId(PhoneVerification.builder()
                .phone("cipher").phoneHash("phone-hash")
                .purpose(VerificationPurpose.SIGNUP)
                .verifiedAt(LocalDateTime.now(Clock.fixed(NOW, ZoneId.of("Asia/Seoul"))))
                .build(), 42L);
        if (consumedAt != null) {
            v.consume(1L, consumedAt);
        }
        return v;
    }

    private void givenTokenResolves(PhoneVerification verification) {
        given(verificationStore.findVerificationIdByToken(TOKEN)).willReturn(Optional.of(42L));
        given(verificationRepository.findById(42L)).willReturn(Optional.of(verification));
    }

    private void givenUserSaved() {
        given(nicknameService.allocate())
                .willReturn(NicknameService.Allocation.of("CalmRiver0042", "calmriver0042"));
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(inv -> withId(inv.getArgument(0), 7L));
    }

    @Test
    @DisplayName("인증 행의 암호문·해시를 그대로 옮기고 인증 시각을 박는다 — 평문을 다시 다루지 않는다")
    void carries_ciphertext_from_verification() {
        PhoneVerification verification = verification(null);
        givenTokenResolves(verification);
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        givenUserSaved();

        User user = processor.createUser(TOKEN, "Nguyen", "tenant@example.invalid", "ko");

        assertThat(user.getPhone()).isEqualTo("cipher");
        assertThat(user.getPhoneHash()).isEqualTo("phone-hash");
        assertThat(user.getPhoneVerifiedAt()).isEqualTo(verification.getVerifiedAt());
        assertThat(user.getUuid()).isNotBlank();
    }

    // 계정 타임라인의 시작점. from_status 가 NULL 인 행이 곧 "가입"이다.
    @Test
    @DisplayName("가입도 상태 이력에 남는다 — 타임라인의 시작점")
    void signup_is_recorded_in_status_history() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        givenUserSaved();

        processor.createUser(TOKEN, null, "tenant@example.invalid", "en");

        then(accountBlockService).should().recordStatusChange(
                any(), isNull(), eq(UserStatus.ACTIVE), any(),
                eq(UserStatusHistory.ACTOR_SELF), isNull());
    }

    /*
     * ★ 경합 번역. 같은 번호로 두 요청이 동시에 오면 uq_users_phone_hash 가 잡는데,
     *   그대로 두면 진 쪽에 500 이 나간다(실측 2026-09-01). 순차와 같은 답을 줘야 한다.
     */
    @Test
    @DisplayName("번호 유니크 경합은 500 이 아니라 DUPLICATE_PHONE 으로 끊는다")
    void phone_unique_race_becomes_duplicate_phone() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        given(nicknameService.allocate())
                .willReturn(NicknameService.Allocation.of("CalmRiver0042", "calmriver0042"));
        given(userRepository.saveAndFlush(any(User.class))).willThrow(
                new DataIntegrityViolationException("dup",
                        new ConstraintViolationException(
                                "dup", null, "users.uq_users_phone_hash")));

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("DUPLICATE_PHONE");
    }

    /*
     * ★ 가입 관문의 실구현 고정. LocalAuthServiceTest 는 SignUpProcessor 를 목으로 두므로
     *   이 메서드 본문을 비워도(위임 삭제) 스위트가 그린이었다 — 열거 오라클 봉쇄가 무방비였다
     *   (적대적 리뷰 2026-09-02, 5라운드). 위임을 지우면 아래 두 테스트가 죽는다.
     */
    @Nested
    @DisplayName("가입 토큰 사전 검증")
    class AssertUsableSignupToken {

        @Test
        @DisplayName("무효 토큰을 거절한다 — 본문이 비면 오라클이 복귀한다")
        void rejects_unknown_token() {
            given(verificationStore.findVerificationIdByToken(TOKEN)).willReturn(Optional.empty());

            assertThatThrownBy(() -> processor.assertUsableSignupToken(TOKEN))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_TOKEN_INVALID");
        }

        @Test
        @DisplayName("소비된 토큰을 거절한다")
        void rejects_consumed_token() {
            PhoneVerification consumed = verification(null);
            consumed.consume(7L, LocalDateTime.of(2026, 9, 1, 10, 0));
            givenTokenResolves(consumed);

            assertThatThrownBy(() -> processor.assertUsableSignupToken(TOKEN))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_ALREADY_USED");
        }

        // "검증은 소비하지 않는다"가 문서 계약이다 — 소비하면 DUPLICATE_EMAIL 후
        // 이메일만 바꿔 재시도하는 흐름이 죽는다.
        @Test
        @DisplayName("정상 토큰은 통과하되 소비하지 않는다")
        void passes_without_consuming() {
            PhoneVerification verification = verification(null);
            givenTokenResolves(verification);

            processor.assertUsableSignupToken(TOKEN);

            assertThat(verification.isConsumed()).isFalse();
            then(verificationStore).should(never()).deleteToken(TOKEN);
        }
    }

    /*
     * ★ 닉네임 유니크는 CONFLICT 다. NicknameService 는 사다리를 소진하면 점유된
     *   후보를 알면서 그대로 반환하므로, 사전 소진 상태에서는 이 위반이 확정으로 난다 —
     *   무번역이면 신규 가입 전부가 500 이다(적대적 리뷰 2026-09-02). 재시도하면 다른 후보가
     *   뽑히니 "잠시 후 다시"가 정확한 답이다.
     */
    @Test
    @DisplayName("닉네임 유니크 위반은 500 이 아니라 CONFLICT 다 — 사다리 소진이 내부 오류로 보이면 안 된다")
    void nickname_race_becomes_conflict() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        given(nicknameService.allocate())
                .willReturn(NicknameService.Allocation.of("CalmRiver0042", "calmriver0042"));
        given(userRepository.saveAndFlush(any(User.class))).willThrow(
                new DataIntegrityViolationException("dup",
                        new ConstraintViolationException(
                                "dup", null, "users.uq_users_nickname_normalized")));

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("CONFLICT");
    }

    /*
     * ★ 커밋 후 정리 실패는 삼켜야 한다. 커밋은 이미 성공했는데 afterCommit 예외가 호출자까지
     *   올라가면 "가입은 됐는데 응답은 500" 이 된다 — 계정·토큰이 만들어졌는데 클라이언트는
     *   실패로 처리한다(적대적 리뷰 2026-09-02). 삭제 실패의 대가는 TTL 까지 남는 죽은 키뿐이다.
     */
    @Test
    @DisplayName("커밋 후 토큰 삭제가 실패해도 예외가 올라가지 않는다 — 성공한 가입이 500 이 되면 안 된다")
    void token_cleanup_failure_after_commit_is_swallowed() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            givenTokenResolves(verification(null));
            given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
            givenUserSaved();
            BDDMockito.willThrow(new RuntimeException("redis down"))
                    .given(verificationStore).deleteToken(TOKEN);

            processor.createUser(TOKEN, null, "tenant@example.invalid", "en");

            Assertions.assertThatCode(() ->
                    TransactionSynchronizationManager
                            .getSynchronizations().forEach(
                                    TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    /*
     * ★ Redis 토큰 삭제는 커밋 이후여야 한다. 트랜잭션 안에서 지우면 롤백이 되돌려 주지 않는다 —
     *   소셜 연결은 이 뒤에도 호출부가 OAUTH_EMAIL_REQUIRED 등으로 실패할 수 있고,
     *   그때 DB 의 consume 은 롤백되지만 Redis 키는 사라져 사용자가 인증부터 다시 해야 한다.
     */
    @Test
    @DisplayName("인증 토큰은 커밋 전에 지우지 않는다 — 롤백이 Redis 를 되돌려 주지 않는다")
    void verification_token_is_deleted_only_after_commit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            givenTokenResolves(verification(null));
            given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
            givenUserSaved();

            processor.createUser(TOKEN, null, "tenant@example.invalid", "en");

            // 트랜잭션이 아직 살아 있는 동안에는 지우지 않는다.
            then(verificationStore).should(never()).deleteToken(TOKEN);

            TransactionSynchronizationManager
                    .getSynchronizations().forEach(
                            TransactionSynchronization::afterCommit);
            then(verificationStore).should().deleteToken(TOKEN);
        } finally {
            TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    // 예상 밖 위반(FK 등)까지 싸잡으면 진짜 원인이 사용자 안내 뒤로 숨는다.
    @Test
    @DisplayName("번호·닉네임이 아닌 제약 위반은 번역하지 않고 그대로 올린다")
    void other_constraint_is_not_translated() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        given(nicknameService.allocate())
                .willReturn(NicknameService.Allocation.of("CalmRiver0042", "calmriver0042"));
        given(userRepository.saveAndFlush(any(User.class))).willThrow(
                new DataIntegrityViolationException("dup",
                        new ConstraintViolationException(
                                "fk", null, "fk_user_auth_user")));

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // FR-105 — 한 인증은 한 번만 소비된다. 소비 표시와 토큰 삭제가 같이 일어나야
    // 같은 토큰으로 두 번째 가입이 성립하지 않는다.
    @Test
    @DisplayName("가입이 끝나면 인증이 소비 표시되고 토큰이 삭제된다")
    void consumes_verification_and_deletes_token() {
        PhoneVerification verification = verification(null);
        givenTokenResolves(verification);
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        givenUserSaved();

        processor.createUser(TOKEN, null, "tenant@example.invalid", "en");

        assertThat(verification.isConsumed()).isTrue();
        assertThat(verification.getUserId()).isEqualTo(7L);
        then(verificationStore).should().deleteToken(TOKEN);
    }

    @Test
    @DisplayName("이미 소비된 인증으로는 다시 가입할 수 없다")
    void consumed_verification_is_rejected() {
        givenTokenResolves(verification(LocalDateTime.now(Clock.fixed(NOW, ZoneId.of("Asia/Seoul")))));

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_ALREADY_USED");

        then(userRepository).should(never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("토큰이 없거나 만료됐으면 가입이 시작되지 않는다")
    void unknown_token_is_rejected() {
        given(verificationStore.findVerificationIdByToken(TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_TOKEN_INVALID");
    }

    /*
     * INV-01 — 한 번호 = 한 계정. 소셜 연결도 이 판정을 지난다 — 같은 번호 계정에 수단을 붙이지 않는다.
     * 거절은 인증을 건드리지 않는다. 앱에는 이 거절 뒤에도 휴대폰 인증이 살아 있다고 알려 두었다.
     */
    @Test
    @DisplayName("같은 번호의 활성 계정이 있으면 거절하고, 인증은 소비하지 않는다")
    void duplicate_active_phone_is_rejected() {
        PhoneVerification verification = verification(null);
        givenTokenResolves(verification);
        given(userRepository.findByPhoneHash("phone-hash"))
                .willReturn(Optional.of(User.builder().status(UserStatus.ACTIVE).build()));

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("DUPLICATE_PHONE");

        assertThat(verification.isConsumed()).isFalse();
        then(userRepository).should(never()).saveAndFlush(any(User.class));
        then(verificationStore).should(never()).deleteToken(TOKEN);
    }

    /*
     * ★ 세탁 차단(번호 축). 정지 계정이 탈퇴하면 users 에서 phone_hash 가 사라져
     *   위의 "정지 계정의 번호" 판정에 걸리지 않는다 — 원장이 그 자리를 메운다.
     *   로컬 가입과 소셜 연결이 모두 createUser 를 지나므로 이 테스트가 양쪽을 고정한다.
     */
    @Test
    @DisplayName("차단 원장에 오른 번호는 users 에 흔적이 없어도 막는다")
    void blocked_phone_cannot_create_user() {
        givenTokenResolves(verification(null));
        given(accountBlockService.isPhoneBlocked("phone-hash")).willReturn(true);

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("SUSPENDED_USER");
    }

    @Test
    @DisplayName("정지 계정의 번호로는 새 계정을 만들 수 없다")
    void suspended_phone_is_rejected() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash"))
                .willReturn(Optional.of(User.builder().status(UserStatus.SUSPENDED).build()));

        assertThatThrownBy(() -> processor.createUser(TOKEN, null, "tenant@example.invalid", "en"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("SUSPENDED_USER");
    }

    // FR-110 — 탈퇴하면 번호 슬롯이 풀려 같은 번호로 재가입할 수 있다.
    // 슬롯을 비우지 않으면 유니크 제약이 재가입을 영원히 막는다.
    @Test
    @DisplayName("탈퇴 계정의 번호는 슬롯을 비우고 재가입을 허용한다")
    void withdrawn_phone_is_released_for_resignup() {
        User withdrawn = User.builder().status(UserStatus.WITHDRAWN).phoneHash("phone-hash").build();
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.of(withdrawn));
        givenUserSaved();

        User created = processor.createUser(TOKEN, null, "tenant@example.invalid", "en");

        assertThat(withdrawn.getPhoneHash()).isNull();
        then(userRepository).should().saveAndFlush(withdrawn);
        assertThat(created.getPhoneHash()).isEqualTo("phone-hash");
    }

    @Test
    @DisplayName("지원하지 않는 언어를 보내면 기본 언어(en)로 떨어진다")
    void unsupported_language_falls_back() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        given(languageQueryService.isSupported("zz")).willReturn(false);
        givenUserSaved();

        assertThat(processor.createUser(TOKEN, null, "tenant@example.invalid", "zz").getPreferredLanguage()).isEqualTo("en");
    }

    // FR-106 — 닉네임은 가입 트랜잭션에서 발급된다. NULL로 두면 비익명 게시판을 여는 날
    // 전 회원 백필이 필요해진다(V1 주석이 NULL을 "발급 실패의 흔적"이라 못박는다).
    @Test
    @DisplayName("가입과 같은 트랜잭션에서 닉네임이 발급된다 — NULL로 만들어지지 않는다")
    void nickname_is_allocated_in_signup() {
        givenTokenResolves(verification(null));
        given(userRepository.findByPhoneHash("phone-hash")).willReturn(Optional.empty());
        givenUserSaved();

        User user = processor.createUser(TOKEN, null, "tenant@example.invalid", "en");

        assertThat(user.getNickname()).isEqualTo("CalmRiver0042");
        assertThat(user.getNicknameNormalized()).isEqualTo("calmriver0042");
        assertThat(user.getIsNicknameCustom()).isFalse();
    }
}
