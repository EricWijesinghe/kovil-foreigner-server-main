package com.kovil.domain.auth.service;

import com.kovil.common.ratelimit.RateLimitException;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimitStore;
import com.kovil.domain.auth.config.PhoneVerificationProperties;
import com.kovil.domain.auth.dto.response.PhoneSendResponse;
import com.kovil.domain.auth.dto.response.PhoneVerifyResponse;
import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.PhoneVerificationException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.crypto.Hasher;
import com.kovil.infra.sms.SmsMessage;
import com.kovil.infra.sms.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

    private static final String RAW_PHONE = "010-1234-5678";
    private static final String NORMALIZED = "01012345678";
    private static final String HASH = "hashed-phone";
    private static final VerificationPurpose PURPOSE = VerificationPurpose.SIGNUP;

    @Mock PhoneVerificationStore store;
    @Mock PhoneVerificationRepository repository;
    @Mock RateLimitStore rateLimitStore;
    @Mock Encryptor encryptor;
    @Mock Hasher hasher;
    @Mock SmsSender smsSender;
    @Mock VerificationCodeGenerator codeGenerator;

    private PhoneVerificationService service;

    @BeforeEach
    void setUp() {
        service = new PhoneVerificationService(
                new PhoneNumberNormalizer(),
                codeGenerator,
                store,
                repository,
                new PhoneVerificationProperties(
                        Duration.ofMinutes(5), Duration.ofSeconds(60), 5, Duration.ofMinutes(10)),
                rateLimitStore,
                encryptor,
                hasher,
                smsSender,
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    private void allowRateLimit() {
        given(rateLimitStore.tryConsume(any(), anyString()))
                .willReturn(RateLimitStore.RateLimitResult.allow());
    }

    // 번호 검증에서 끊기는 경로다 — 해시조차 계산되지 않으므로 중첩 클래스의 스텁 밖에 둔다.
    @Test
    @DisplayName("한국 번호가 아니면 해시·한도·문자 어느 것도 건드리지 않는다")
    void non_kr_short_circuits() {
        assertThatThrownBy(() -> service.send("+8613800138000", VerificationPurpose.SIGNUP))
                .isInstanceOf(PhoneVerificationException.class);

        then(hasher).shouldHaveNoInteractions();
        then(rateLimitStore).shouldHaveNoInteractions();
        then(smsSender).shouldHaveNoInteractions();
    }

    @Nested
    @DisplayName("발송")
    class Send {

        @BeforeEach
        void stubHash() {
            given(hasher.hash(NORMALIZED)).willReturn(HASH);
        }

        @Test
        @DisplayName("정규화된 번호로 문자를 보내고 코드를 Redis에 담는다 — 응답에는 만료 초만 있다")
        void sends_and_stores_code() {
            allowRateLimit();
            given(codeGenerator.generate()).willReturn("123456");

            PhoneSendResponse response = service.send(RAW_PHONE, PURPOSE);

            then(store).should().saveCode(HASH, PURPOSE, "123456", Duration.ofMinutes(5));
            then(store).should().startCooldown(HASH, Duration.ofSeconds(60));

            ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);
            then(smsSender).should().send(captor.capture());
            assertThat(captor.getValue().toPhone()).isEqualTo(NORMALIZED);
            assertThat(captor.getValue().text()).contains("123456");
            // 수신자는 외국인이다 — 한국어로 되돌아가면 읽지 못한다.
            // 비ASCII 가 섞이면 SMS 가 LMS 로 승격되거나 인코딩이 깨져 인증번호가 안 보인다.
            assertThat(captor.getValue().text())
                    .as("본문은 ASCII 영어여야 한다")
                    .matches("\\p{ASCII}+")
                    .contains("verification code")
                    .contains("5 minutes");

            assertThat(response.expiresInSeconds()).isEqualTo(300);
            // 이 값이 없으면 FE가 재발송 버튼을 언제 열지 몰라 사용자가 눌렀다가 429를 본다.
            assertThat(response.resendAvailableInSeconds()).isEqualTo(60);
        }

        // 쿨다운을 한도보다 먼저 보지 않으면, 연타만으로 번호축 한도(5회/1시간)가 소진되어
        // 정상 사용자가 한 시간 동안 가입하지 못한다.
        @Test
        @DisplayName("쿨다운 중이면 번호축 한도를 소비하지 않고 거절한다")
        void cooldown_checked_before_rate_limit() {
            given(store.isCoolingDown(HASH)).willReturn(true);

            assertThatThrownBy(() -> service.send(RAW_PHONE, PURPOSE))
                    .isInstanceOf(PhoneVerificationException.class)
                    .extracting(e -> ((PhoneVerificationException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_RESEND_TOO_SOON");

            then(rateLimitStore).shouldHaveNoInteractions();
            then(smsSender).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("번호축 한도를 넘으면 문자를 보내지 않는다")
        void rate_limited_does_not_send() {
            given(rateLimitStore.tryConsume(RateLimitPolicy.PHONE_VERIFICATION_SEND, HASH))
                    .willReturn(RateLimitStore.RateLimitResult.deny(120));

            assertThatThrownBy(() -> service.send(RAW_PHONE, PURPOSE))
                    .isInstanceOf(RateLimitException.class);

            then(smsSender).shouldHaveNoInteractions();
            then(store).should(never()).saveCode(anyString(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("확인")
    class Verify {

        @BeforeEach
        void stubHash() {
            given(hasher.hash(NORMALIZED)).willReturn(HASH);
        }

        private void givenIssuedCode(String code) {
            given(store.findCode(HASH, PURPOSE)).willReturn(Optional.of(code));
        }

        private void givenSavedVerification() {
            given(encryptor.encrypt(NORMALIZED)).willReturn("cipher");
            given(repository.save(any(PhoneVerification.class))).willAnswer(invocation -> {
                PhoneVerification v = invocation.getArgument(0);
                var field = PhoneVerification.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(v, 42L);
                return v;
            });
        }

        @Test
        @DisplayName("코드가 맞으면 암호문·해시로 성공 이력을 남기고 토큰을 발급한다")
        void success_persists_and_issues_token() {
            allowRateLimit();
            givenIssuedCode("123456");
            given(store.increaseAttempt(eq(HASH), eq(PURPOSE), any())).willReturn(1L);
            givenSavedVerification();

            PhoneVerifyResponse response = service.verify(RAW_PHONE, "123456", PURPOSE);

            ArgumentCaptor<PhoneVerification> captor = ArgumentCaptor.forClass(PhoneVerification.class);
            then(repository).should().save(captor.capture());
            PhoneVerification saved = captor.getValue();

            assertThat(saved.getPhone()).isEqualTo("cipher");
            assertThat(saved.getPhoneHash()).isEqualTo(HASH);
            assertThat(saved.getVerifiedAt()).isNotNull();
            assertThat(saved.isConsumed()).isFalse();

            assertThat(response.verificationToken()).isNotBlank();
            assertThat(response.expiresInSeconds()).isEqualTo(600);
            then(store).should().saveToken(response.verificationToken(), 42L, Duration.ofMinutes(10));
        }

        // 인증번호는 DB에 어떤 형태로도 남지 않는다(FR-102).
        @Test
        @DisplayName("저장된 행 어디에도 인증번호가 없다")
        void code_never_reaches_database() {
            allowRateLimit();
            givenIssuedCode("123456");
            given(store.increaseAttempt(eq(HASH), eq(PURPOSE), any())).willReturn(1L);
            givenSavedVerification();

            service.verify(RAW_PHONE, "123456", PURPOSE);

            ArgumentCaptor<PhoneVerification> captor = ArgumentCaptor.forClass(PhoneVerification.class);
            then(repository).should().save(captor.capture());
            PhoneVerification saved = captor.getValue();

            assertThat(saved.getPhone()).doesNotContain("123456");
            assertThat(saved.getPhoneHash()).doesNotContain("123456");
        }

        /*
         * ★ 성공 폐기는 **커밋 이후**다 — 트랜잭션 안에서 지우면 save·커밋 실패로 롤백될 때
         *   코드만 사라져, 올바른 코드를 넣은 사용자가 SMS(과금)부터 다시 해야 한다
         *   (적대적 리뷰 2026-09-02). 즉시 폐기로 되돌리면 이 테스트가 죽는다.
         */
        @Test
        @DisplayName("성공 폐기는 커밋 전이 아니라 커밋 후다 — 롤백이 코드를 되살려 주지 않는다")
        void code_discarded_only_after_commit() {
            TransactionSynchronizationManager.initSynchronization();
            try {
                allowRateLimit();
                givenIssuedCode("123456");
                given(store.increaseAttempt(eq(HASH), eq(PURPOSE), any())).willReturn(1L);
                givenSavedVerification();

                service.verify(RAW_PHONE, "123456", PURPOSE);

                then(store).should(never()).discardCode(HASH, PURPOSE);
                TransactionSynchronizationManager
                        .getSynchronizations().forEach(
                                TransactionSynchronization::afterCommit);
                then(store).should().discardCode(HASH, PURPOSE);
            } finally {
                TransactionSynchronizationManager
                        .clearSynchronization();
            }
        }

        @Test
        @DisplayName("코드가 틀리면 이력을 남기지 않고 거절한다")
        void mismatch_persists_nothing() {
            allowRateLimit();
            givenIssuedCode("123456");
            given(store.increaseAttempt(eq(HASH), eq(PURPOSE), any())).willReturn(1L);

            assertThatThrownBy(() -> service.verify(RAW_PHONE, "999999", PURPOSE))
                    .isInstanceOf(PhoneVerificationException.class)
                    .extracting(e -> ((PhoneVerificationException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_CODE_INVALID");

            then(repository).shouldHaveNoInteractions();
        }

        // 발급된 적 없는 번호와 만료된 코드는 같은 코드로 답한다 — 구분하면
        // 어떤 번호로 발송 이력이 있는지를 외부에서 알아낼 수 있다.
        @Test
        @DisplayName("발급된 코드가 없으면 틀린 것과 같은 응답을 준다")
        void missing_code_is_indistinguishable() {
            allowRateLimit();
            given(store.findCode(HASH, PURPOSE)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.verify(RAW_PHONE, "123456", PURPOSE))
                    .isInstanceOf(PhoneVerificationException.class)
                    .extracting(e -> ((PhoneVerificationException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_CODE_INVALID");
        }

        /*
         * ★ 이쪽은 커밋 이후로 옮기면 안 된다 — 예외로 롤백되는 경로라 afterCommit 이 실행되지
         *   않아 징벌 폐기가 유실된다. 성공 폐기(위)와의 비대칭은 의도다.
         */
        @Test
        @DisplayName("시도 한도를 넘으면 코드를 폐기한다 — 남은 시간 동안 대입을 이어갈 수 없다")
        void exceeding_attempts_discards_code() {
            allowRateLimit();
            givenIssuedCode("123456");
            given(store.increaseAttempt(eq(HASH), eq(PURPOSE), any())).willReturn(6L);

            assertThatThrownBy(() -> service.verify(RAW_PHONE, "123456", PURPOSE))
                    .isInstanceOf(PhoneVerificationException.class);

            then(store).should().discardCode(HASH, PURPOSE);
            then(repository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("번호축 한도를 넘으면 코드를 조회조차 하지 않는다")
        void rate_limited_before_lookup() {
            given(rateLimitStore.tryConsume(RateLimitPolicy.PHONE_VERIFICATION_VERIFY, HASH))
                    .willReturn(RateLimitStore.RateLimitResult.deny(30));

            assertThatThrownBy(() -> service.verify(RAW_PHONE, "123456", PURPOSE))
                    .isInstanceOf(RateLimitException.class);

            then(store).shouldHaveNoInteractions();
        }
    }
}
