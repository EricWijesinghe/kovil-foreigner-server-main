package com.kovil.domain.user.service;

import com.kovil.domain.user.service.AccountBlockService;
import com.kovil.common.exception.CustomException;
import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.language.service.LanguageQueryService;
import com.kovil.domain.device.service.DeviceTokenService;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.dto.request.ConsentRequest;
import com.kovil.domain.user.dto.request.UpdateMeRequest;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.entity.UserConsent;
import com.kovil.domain.user.enums.ConsentType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.repository.UserConsentRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.support.NicknamePolicy;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.service.BrokerOffboardingService;
import com.kovil.domain.listing.service.ListingFavoriteService;
import com.kovil.domain.listing.service.SavedAreaService;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.service.PartnerWithdrawalService;
import com.kovil.domain.rentalrequest.service.RentalRequestService;
import com.kovil.domain.verification.service.VerificationPurgeService;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.oauth.apple.AppleTokenExchanger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.BDDMockito;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.util.List;
import java.time.Instant;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String UUID = "user-uuid";
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock UserRepository userRepository;
    @Mock UserQueryService userQueryService;
    @Mock CountryRepository countryRepository;
    @Mock LanguageQueryService languageQueryService;
    @Mock NicknamePolicy nicknamePolicy;
    @Mock NicknameService nicknameService;
    @Mock UserAuthRepository userAuthRepository;
    @Mock AccountBlockService accountBlockService;
    @Mock UserConsentRepository userConsentRepository;
    @Mock RefreshTokenService refreshTokenService;
    @Mock DeviceTokenService deviceTokenService;
    @Mock FileDeletionService fileDeletionService;
    @Mock VerificationPurgeService verificationPurgeService;
    @Mock RentalRequestService rentalRequestService;
    @Mock ListingFavoriteService listingFavoriteService;
    @Mock SavedAreaService savedAreaService;
    @Mock PartnerWithdrawalService partnerWithdrawalService;
    @Mock BrokerOffboardingService brokerOffboardingService;
    @Mock AppleTokenExchanger appleTokenExchanger;
    @Mock Encryptor encryptor;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, userQueryService, countryRepository,
                languageQueryService, nicknamePolicy, nicknameService, userAuthRepository,
                accountBlockService, userConsentRepository, refreshTokenService,
                deviceTokenService,
                fileDeletionService, verificationPurgeService,
                rentalRequestService, listingFavoriteService, savedAreaService,
                partnerWithdrawalService, brokerOffboardingService,
                appleTokenExchanger, encryptor,
                Clock.fixed(NOW, ZONE));
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

    // 사용자가 직접 넣은 닉네임(실명일 수 있음) — 탈퇴 시 재발급 대상이다.
    private User customNicknameUser() {
        return withId(User.builder().uuid(UUID).name("Nguyen").nickname("RealName Kim")
                .nicknameNormalized("realname kim").isNicknameCustom(true)
                .phone("cipher").phoneHash("phone-hash").email("a@b.com")
                .preferredLanguage("en").status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L);
    }

    private User user() {
        return withId(User.builder().uuid(UUID).name("Nguyen").nickname("CalmRiver0042")
                .nicknameNormalized("calmriver0042").isNicknameCustom(false)
                .phone("cipher").phoneHash("phone-hash").email("a@b.com")
                .phoneVerifiedAt(LocalDateTime.now(Clock.fixed(NOW, ZONE)))
                .profileImageUrl("https://x/y.png")
                .preferredLanguage("en").status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L);
    }

    @Nested
    @DisplayName("내 정보 수정")
    class UpdateMe {

        // PATCH다 — 보내지 않은 필드는 손대지 않는다. 여기가 뒤집히면
        // 언어만 바꾸려던 요청이 이름을 지운다.
        @Test
        @DisplayName("null인 필드는 건드리지 않는다")
        void null_fields_untouched() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);

            service.updateMe(UUID, new UpdateMeRequest(null, null, null, null));

            assertThat(user.getName()).isEqualTo("Nguyen");
            assertThat(user.getPreferredLanguage()).isEqualTo("en");
            assertThat(user.getNickname()).isEqualTo("CalmRiver0042");
        }

        @Test
        @DisplayName("지원하지 않는 언어는 거절한다 — FK 위반으로 500이 되면 안 된다")
        void unsupported_language_rejected() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(languageQueryService.isSupported("zz")).willReturn(false);

            assertThatThrownBy(() -> service.updateMe(UUID, new UpdateMeRequest(null, null, "zz", null)))
                    .isInstanceOf(CustomException.class);
        }

        @Test
        @DisplayName("국적은 활성 국가 코드로만 바뀐다")
        void nationality_resolved_by_code() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            given(countryRepository.findByCodeAndIsActiveTrue("VN"))
                    .willReturn(Optional.of(withId(Country.builder().code("VN").nameEn("Vietnam").build(), 11L)));

            service.updateMe(UUID, new UpdateMeRequest(null, null, null, "vn"));

            assertThat(user.getNationalityCountryId()).isEqualTo(11L);
        }

        // FR-108 — 중복은 409, 금칙어·형식은 정책이 본다.
        @Test
        @DisplayName("이미 쓰이는 닉네임이면 NICKNAME_TAKEN")
        void duplicate_nickname_rejected() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(nicknamePolicy.validate("taken")).willReturn("taken");
            given(userRepository.existsByNicknameNormalized("taken")).willReturn(true);

            assertThatThrownBy(() -> service.updateMe(UUID, new UpdateMeRequest(null, "taken", null, null)))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode().getCode())
                    .isEqualTo("NICKNAME_TAKEN");
        }

        /*
         * ★ 사전 컷과 커밋 사이에 같은 닉네임이 먼저 들어오면 최종 방어선
         *   (uq_users_nickname_normalized)이 잡는다. 무번역이면 진 쪽에만 500 — 순차 답
         *   (NICKNAME_TAKEN)과 경합 답이 갈린다(적대적 리뷰 2026-09-02). 번역을 빼면 죽는다.
         */
        @Test
        @DisplayName("닉네임 유니크 경합도 500 이 아니라 NICKNAME_TAKEN 이다")
        void nickname_unique_race_becomes_taken() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(nicknamePolicy.validate("taken")).willReturn("taken");
            given(userRepository.existsByNicknameNormalized("taken")).willReturn(false);
            given(userRepository.saveAndFlush(any(User.class))).willThrow(
                    new DataIntegrityViolationException("dup",
                            new ConstraintViolationException(
                                    "dup", null, "users.uq_users_nickname_normalized")));

            assertThatThrownBy(() -> service.updateMe(UUID, new UpdateMeRequest(null, "taken", null, null)))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode().getCode())
                    .isEqualTo("NICKNAME_TAKEN");
        }

        // 제약 이름을 안 보면 엉뚱한 위반이 "사용 중인 닉네임"으로 둔갑한다.
        @Test
        @DisplayName("닉네임 유니크가 아닌 위반은 번역하지 않고 그대로 올린다")
        void other_constraint_is_not_translated_on_nickname_change() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(nicknamePolicy.validate("taken")).willReturn("taken");
            given(userRepository.existsByNicknameNormalized("taken")).willReturn(false);
            given(userRepository.saveAndFlush(any(User.class))).willThrow(
                    new DataIntegrityViolationException("fk",
                            new ConstraintViolationException(
                                    "fk", null, "fk_users_country")));

            assertThatThrownBy(() -> service.updateMe(UUID, new UpdateMeRequest(null, "taken", null, null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        // 대소문자만 바꾼 자기 닉네임이 자기 자신과 충돌하면 안 된다.
        @Test
        @DisplayName("자기 닉네임과 같은 정규화값이면 중복으로 보지 않는다")
        void same_normalized_is_not_duplicate() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            given(nicknamePolicy.validate("CALMRIVER0042")).willReturn("calmriver0042");

            service.updateMe(UUID, new UpdateMeRequest(null, "CALMRIVER0042", null, null));

            assertThat(user.getNickname()).isEqualTo("CALMRIVER0042");
            then(userRepository).should(never()).existsByNicknameNormalized(anyString());
        }

        @Test
        @DisplayName("사용자가 직접 바꾸면 custom 표시와 변경 시각이 남는다")
        void custom_nickname_is_flagged() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            given(nicknamePolicy.validate("Sunny")).willReturn("sunny");
            given(userRepository.existsByNicknameNormalized("sunny")).willReturn(false);

            service.updateMe(UUID, new UpdateMeRequest(null, "Sunny", null, null));

            assertThat(user.getIsNicknameCustom()).isTrue();
            assertThat(user.getNicknameChangedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("약관 동의")
    class Consent {

        // FR-111 — 철회도 UPDATE가 아니라 새 행이다. 개보법 보존 의무 때문에 기존 행을 건드리지 않는다.
        @Test
        @DisplayName("동의도 철회도 새 행으로 쌓인다")
        void consent_is_append_only() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(userConsentRepository.save(any(UserConsent.class))).willAnswer(inv -> inv.getArgument(0));

            service.recordConsent(UUID, new ConsentRequest(ConsentType.MARKETING, false, "v1"));

            ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
            then(userConsentRepository).should().save(captor.capture());
            assertThat(captor.getValue().getAgreed()).isFalse();
            assertThat(captor.getValue().getUserId()).isEqualTo(7L);
            then(userConsentRepository).should(never()).delete(any());
        }
    }

    @Nested
    @DisplayName("탈퇴")
    class Withdraw {

        /*
         * ★ 부문이 정지된 파트너는 탈퇴하지 못한다(PO 확정 2026-10-07). 탈퇴가 등록번호를 놓으므로 막지 않으면
         *   정지 → 탈퇴 → 같은 번호로 재가입으로 정지가 씻긴다. 거절은 **무엇이든 바꾸기 전**이어야 한다 —
         *   세션 취소선(Redis)은 트랜잭션이 롤백돼도 남아, 탈퇴는 안 됐는데 로그아웃만 되는 상태가 된다.
         */
        @Test
        @DisplayName("부문이 정지된 파트너는 탈퇴하지 못하고, 아무것도 바뀌지 않는다")
        void suspended_partner_cannot_withdraw() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            BDDMockito.willThrow(new PartnerException(
                            PartnerErrorCode.PARTNER_SUSPENDED_CANNOT_WITHDRAW))
                    .given(partnerWithdrawalService).assertWithdrawable(7L);

            assertThatThrownBy(() -> service.withdraw(UUID))
                    .isInstanceOf(PartnerException.class);

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getPhoneHash()).isNotNull();
            then(refreshTokenService).shouldHaveNoInteractions();
            then(brokerOffboardingService).shouldHaveNoInteractions();
            then(partnerWithdrawalService).should(Mockito.never()).purgeOnWithdrawal(7L);
            then(rentalRequestService).shouldHaveNoInteractions();
        }

        /*
         * ★ 무엇이든 쓰기 전에 내 users 행을 배타 잠금으로 잡는다. 안 잡으면 상태 이력 INSERT 가 이 행을 FK 검사로(S) 먼저 쥐고
         *   flush 의 UPDATE 에서 X 로 올리는데, 같은 계정의 탈퇴 두 요청이 겹치면 둘 다 S 를 쥔 채 X 를 기다려 교착한다.
         *   관리자 가드(관리자 행 전부 잠금)보다는 뒤다 — 앞이면 두 관리자의 동시 탈퇴가 교착한다.
         */
        @Test
        @DisplayName("관리자 가드 뒤 · 상태 이력과 파트너 정리 앞에서 내 users 행을 잠금 읽기로 잡는다")
        void locks_own_row_before_any_write() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);

            service.withdraw(UUID);

            InOrder order = Mockito.inOrder(userRepository, partnerWithdrawalService,
                    accountBlockService, refreshTokenService);
            order.verify(userRepository).findAuthSnapshotForUpdate(7L);
            order.verify(partnerWithdrawalService).assertWithdrawable(7L);
            order.verify(accountBlockService).recordStatusChange(any(), any(), any(), any(), any(), any());
            order.verify(partnerWithdrawalService).purgeOnWithdrawal(7L);
            order.verify(refreshTokenService).revokeAllFor(7L);
        }

        @Test
        @DisplayName("관리자의 탈퇴는 관리자 가드의 잠금 뒤에 내 행을 잡는다")
        void admin_locks_own_row_after_last_admin_guard() {
            User admin = withId(User.builder().uuid(UUID).name("Admin").nickname("CalmRiver0042")
                    .nicknameNormalized("calmriver0042").isNicknameCustom(false)
                    .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.ADMIN).build(), 7L);
            given(userQueryService.getActiveUser(UUID)).willReturn(admin);
            given(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).willReturn(List.of(7L, 8L));

            service.withdraw(UUID);

            InOrder order = Mockito.inOrder(userRepository);
            order.verify(userRepository).findIdsByRoleForUpdate(UserRole.ADMIN);
            order.verify(userRepository).findAuthSnapshotForUpdate(7L);
        }

        // FR-110 — PII를 지우고 번호 슬롯을 푼다. 이력은 FK가 잡고 있어 남는다.
        @Test
        @DisplayName("PII를 지우고 번호 슬롯을 풀어 같은 번호로 재가입할 수 있게 한다")
        void anonymizes_and_releases_phone_slot() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);

            service.withdraw(UUID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
            assertThat(user.getDeletedAt()).isNotNull();
            assertThat(user.getName()).isNull();
            assertThat(user.getPhone()).isNull();
            assertThat(user.getPhoneHash()).isNull();
            assertThat(user.getEmail()).isNull();
            assertThat(user.getProfileImageUrl()).isNull();
        }

        /*
         * ★ 재가입의 핵심. user_auth 를 남겨 두면 같은 구글 계정으로 다시 들어올 때
         *   옛 행이 매치되어 **탈퇴한 계정**을 집어 들고 USER_NOT_ACTIVE 로 막힌다
         *   (운영 실측 2026-09-01). soft delete 로는 못 푼다 — 유니크 키가
         *   (auth_type, identifier) 뿐이라 deleted_at 을 채워도 슬롯이 남는다.
         *   그래서 스키마 주석이 "탈퇴 시엔 하드 삭제"라고 못 박아 두었다.
         */
        /*
         * 탈퇴는 수단을 **해제**만 한다 — 행도 식별자도 남긴다(PO 확정 2026-09-01).
         * 식별자를 남기는 것이 핵심이다: 재가입 때 "이 신원에 정지 이력이 있나"를 조회하는 키다.
         * 해제하지 않으면 같은 구글 계정으로 재가입할 때 옛 행이 매치되어 막힌다(운영 실측).
         */
        @Test
        @DisplayName("로그인 수단을 해제한다 — 행도 식별자도 지우지 않는다")
        void unlinks_auth_methods_keeping_identifier() {
            UserAuth google = UserAuth.builder()
                    .authType(AuthType.GOOGLE).identifier("google-sub-1").build();
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(userAuthRepository.findAllByUserId(7L)).willReturn(List.of(google));

            service.withdraw(UUID);

            assertThat(google.getDeletedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
            assertThat(google.getIdentifier()).isEqualTo("google-sub-1");
        }

        /*
         * ★ Apple 은 계정 삭제 때 토큰 철회가 의무다(2022-06-30 부터). 외부 호출이라 롤백으로 되돌릴 수 없으니
         *   커밋 뒤에만 나가야 한다 — 트랜잭션 안에서 보내면 탈퇴가 롤백돼도 Apple 쪽 연결은 이미 끊겨 있다.
         */
        @Test
        @DisplayName("Apple 토큰은 칸에서 지우고, 철회는 커밋 뒤에만 복호화한 값과 그 토큰의 client_id 로 보낸다")
        void apple_token_is_revoked_only_after_commit() {
            UserAuth apple = UserAuth.builder().authType(AuthType.APPLE).identifier("apple-sub-1")
                    .providerRefreshToken("v1:cipher").providerClientId("com.kovil.web").build();
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(userAuthRepository.findAllByUserId(7L)).willReturn(List.of(apple));
            given(encryptor.decrypt("v1:cipher")).willReturn("r.refresh-token");

            TransactionSynchronizationManager.initSynchronization();
            try {
                service.withdraw(UUID);

                assertThat(apple.getProviderRefreshToken()).isNull();
                assertThat(apple.getProviderClientId()).isNull();
                assertThat(apple.getDeletedAt()).isNotNull();
                then(appleTokenExchanger).shouldHaveNoInteractions();
                then(encryptor).shouldHaveNoInteractions();

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);
                then(appleTokenExchanger).should().revoke("r.refresh-token", "com.kovil.web");
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("탈퇴가 롤백되면 철회를 보내지 않는다")
        void rolled_back_withdrawal_sends_no_revoke() {
            UserAuth apple = UserAuth.builder().authType(AuthType.APPLE).identifier("apple-sub-1")
                    .providerRefreshToken("v1:cipher").providerClientId("com.kovil.app").build();
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(userAuthRepository.findAllByUserId(7L)).willReturn(List.of(apple));

            TransactionSynchronizationManager.initSynchronization();
            try {
                service.withdraw(UUID);
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            then(appleTokenExchanger).shouldHaveNoInteractions();
            then(encryptor).shouldHaveNoInteractions();
        }

        // 철회 · 복호화가 실패해도 탈퇴 응답은 성공이어야 한다. 계정은 이미 지워졌다.
        @Test
        @DisplayName("철회나 복호화가 실패해도 삼키고 탈퇴는 끝난다")
        void revoke_failure_is_swallowed() {
            UserAuth first = UserAuth.builder().authType(AuthType.APPLE).identifier("apple-sub-1")
                    .providerRefreshToken("v1:broken").providerClientId("com.kovil.app").build();
            UserAuth second = UserAuth.builder().authType(AuthType.APPLE).identifier("apple-sub-2")
                    .providerRefreshToken("v1:cipher").providerClientId("com.kovil.app").build();
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            given(userAuthRepository.findAllByUserId(7L)).willReturn(List.of(first, second));
            given(encryptor.decrypt("v1:broken")).willThrow(new IllegalStateException("bad cipher"));
            given(encryptor.decrypt("v1:cipher")).willReturn("r.refresh-token");
            BDDMockito.willThrow(new IllegalStateException("apple down"))
                    .given(appleTokenExchanger).revoke("r.refresh-token", "com.kovil.app");

            service.withdraw(UUID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
            // 앞의 실패가 뒤의 철회를 막지 않는다.
            then(appleTokenExchanger).should().revoke("r.refresh-token", "com.kovil.app");
        }

        @Test
        @DisplayName("Apple 수단이 없거나 토큰이 없으면 철회를 보내지 않는다")
        void no_apple_token_means_no_revoke() {
            UserAuth google = UserAuth.builder().authType(AuthType.GOOGLE).identifier("google-sub-1").build();
            UserAuth appleWithoutToken = UserAuth.builder().authType(AuthType.APPLE).identifier("apple-sub-1").build();
            given(userQueryService.getActiveUser(UUID)).willReturn(user());
            given(userAuthRepository.findAllByUserId(7L)).willReturn(List.of(google, appleWithoutToken));

            service.withdraw(UUID);

            assertThat(appleWithoutToken.getDeletedAt()).isNotNull();
            then(appleTokenExchanger).shouldHaveNoInteractions();
            then(encryptor).shouldHaveNoInteractions();
        }

        /*
         * ★ 사다리 소진 시 allocate 는 점유된 후보를 알면서 반환한다 — 무번역이면 그 상태에서
         *   탈퇴 자체가 확정 500 으로 막힌다. 재시도하면 다른 후보가 뽑히니 CONFLICT 가 답이다.
         */
        @Test
        @DisplayName("재발급 닉네임이 유니크에 걸리면 500 이 아니라 CONFLICT 다 — 탈퇴가 막히면 안 된다")
        void reissue_race_becomes_conflict() {
            User user = customNicknameUser();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            given(nicknameService.allocate())
                    .willReturn(NicknameService.Allocation.of("CalmRiver0042", "calmriver0042"));
            given(userRepository.saveAndFlush(any(User.class))).willThrow(
                    new DataIntegrityViolationException("dup",
                            new ConstraintViolationException(
                                    "dup", null, "users.uq_users_nickname_normalized")));

            assertThatThrownBy(() -> service.withdraw(UUID))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode().getCode())
                    .isEqualTo("CONFLICT");
        }

        // 탈퇴했는데 남은 리프레시 토큰으로 계속 쓸 수 있으면 탈퇴가 아니다.
        @Test
        @DisplayName("남아 있는 세션을 전부 끊는다")
        void revokes_all_sessions() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            then(refreshTokenService).should().revokeAllFor(7L);
        }

        // users는 soft delete라 device_tokens의 ON DELETE CASCADE가 걸리지 않는다.
        // 지우지 않으면 탈퇴한 사람의 기기로 월세 알림이 계속 간다.
        @Test
        @DisplayName("디바이스 토큰도 지운다 — 탈퇴 후 푸시가 가면 안 된다")
        void removes_device_tokens() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            then(deviceTokenService).should().removeAllFor(7L);
        }

        /*
         * ★ 중개사였다면 사무소에서 뗀다 — 남은 중개사가 없으면 그 사무소 공개 매물을 내린다
         *   (PO 확정 2026-10-06). 이 연결이 없던 동안 탈퇴한 계정이 사무소 소속으로 계속 세어졌고
         *   광고는 만료까지 그대로 나갔다. 일반 회원이면 offboard 가 아무것도 하지 않는다.
         */
        @Test
        @DisplayName("탈퇴하면 중개사 사무소 정리를 부른다")
        void offboards_a_broker_on_withdrawal() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            then(brokerOffboardingService).should().offboard(7L);
        }

        /*
         * ★★ 파트너 정리(대기 부문 닫기 · 등록번호 놓기 · 등록증 폐기)는 사무소 이탈보다 **먼저**다.
         *    사무소는 대표자 연결로 찾는데 이탈이 그 연결을 지운다 — 순서가 바뀌면 번호가 영영 잡힌 채 남아
         *    같은 번호로 새 대표자가 승인받지 못하고, 등록증도 남는다.
         */
        @Test
        @DisplayName("파트너 정리는 사무소 이탈보다 먼저 부른다")
        void partner_purge_runs_before_offboarding() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            InOrder order = Mockito.inOrder(partnerWithdrawalService, brokerOffboardingService);
            order.verify(partnerWithdrawalService).purgeOnWithdrawal(7L);
            order.verify(brokerOffboardingService).offboard(7L);
        }

        // 사용자가 직접 넣은 닉네임은 실명일 수 있다.
        @Test
        @DisplayName("커스텀 닉네임은 자동 생성값으로 갈아 PII를 지운다")
        void custom_nickname_is_reissued() {
            User user = user();
            user.changeNickname("RealName", "realname", true, LocalDateTime.now(Clock.fixed(NOW, ZONE)));
            given(userQueryService.getActiveUser(UUID)).willReturn(user);
            given(nicknameService.allocate())
                    .willReturn(NicknameService.Allocation.of("QuietStone7788", "quietstone7788"));

            service.withdraw(UUID);

            assertThat(user.getNickname()).isEqualTo("QuietStone7788");
            assertThat(user.getIsNicknameCustom()).isFalse();
        }

        // 자동 생성 닉네임은 PII가 아니다 — 커뮤니티 글의 표시명이라 그대로 둔다.
        @Test
        @DisplayName("자동 생성 닉네임은 그대로 둔다")
        void generated_nickname_is_kept() {
            User user = user();
            given(userQueryService.getActiveUser(UUID)).willReturn(user);

            service.withdraw(UUID);

            assertThat(user.getNickname()).isEqualTo("CalmRiver0042");
            then(nicknameService).shouldHaveNoInteractions();
        }

        /*
         * 탈퇴하면 프로필 사진과 신분 증빙은 버린다 — 실제 이동·파기는 정리 배치와 R2 수명
         * 규칙이 한다. 여기서 스토리지를 건드리면 이 트랜잭션이 롤백돼도 사진은 이미 사라진다.
         *
         * ★ 커뮤니티 글의 사진은 **버리지 않는다** — 글은 익명으로 남기 때문이다.
         */
        @Test
        @DisplayName("프로필 사진과 신분 증빙을 버린다")
        void discards_profile_image_and_verification_documents() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            then(fileDeletionService).should().discardByUrls(List.of("https://x/y.png"));
            then(verificationPurgeService).should().purgeOnWithdrawal(7L);
        }

        // 프로필 사진이 없는 계정도 탈퇴한다 — null 을 그대로 넘기면 여기서 터진다.
        @Test
        @DisplayName("프로필 사진이 없으면 버릴 것도 없다")
        void missing_profile_image_is_not_discarded() {
            User noPhoto = withId(User.builder().uuid(UUID).name("Nguyen")
                    .nickname("CalmRiver0042").nicknameNormalized("calmriver0042")
                    .isNicknameCustom(false).phone("cipher").phoneHash("phone-hash")
                    .email("a@b.com").preferredLanguage("en")
                    .status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L);
            given(userQueryService.getActiveUser(UUID)).willReturn(noPhoto);

            service.withdraw(UUID);

            then(fileDeletionService).should().discardByUrls(List.of());
        }

        /*
         * ★ 열린 건을 남기면 사무소들의 대기 목록에 **연락이 닿지 않는 행**이 그대로 떠 있는다.
         *   상담을 시작해도 통보할 계정이 없다(PO 확정 2026-09-29).
         *
         *   해지 요청 철회는 계약 도메인과 함께 빠졌다 — 나머지 정리는 하나도 빠지면 안 된다.
         */
        @Test
        @DisplayName("열린 임대 문의를 닫고 인증·찜·저장한 구역·중개사 소속도 함께 정리한다")
        void closes_open_rental_requests_and_runs_every_cleanup() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            then(rentalRequestService).should().cancelOpenOnWithdrawal(7L);
            then(verificationPurgeService).should().purgeOnWithdrawal(7L);
            then(listingFavoriteService).should().purgeOnWithdrawal(7L);
            then(savedAreaService).should().purgeOnWithdrawal(7L);
            then(partnerWithdrawalService).should().purgeOnWithdrawal(7L);
            then(brokerOffboardingService).should().offboard(7L);
        }

        /*
         * ★ 저장한 구역 정리는 파트너 정리 · 사무소 이탈 **뒤**여야 한다. 정리 DELETE 가 saved_areas 에 갭 잠금을 건 채
         *   파트너 정리(알림 INSERT 가 남의 users 행을 S 로 잡는다)로 넘어가면, 그 사람의 첫 저장(users FOR UPDATE → INSERT)과 교착한다.
         */
        @Test
        @DisplayName("저장한 구역 정리는 파트너 정리 · 사무소 이탈 뒤에 한다")
        void saved_areas_are_purged_after_partner_cleanup() {
            given(userQueryService.getActiveUser(UUID)).willReturn(user());

            service.withdraw(UUID);

            InOrder order = Mockito.inOrder(partnerWithdrawalService, brokerOffboardingService,
                    savedAreaService);
            order.verify(partnerWithdrawalService).purgeOnWithdrawal(7L);
            order.verify(brokerOffboardingService).offboard(7L);
            order.verify(savedAreaService).purgeOnWithdrawal(7L);
        }
    }

    /*
     * Apple 연결 철회 · 계정 삭제 알림(PO 확정 2026-11-10). 연결을 풀면 그 사람이 Apple 로 다시 왔을 때 새 신원이 되고
     * 번호는 옛 계정에 묶여 DUPLICATE_PHONE 으로 막힌다. 그래서 세션과 토큰만 지우고 수단은 남긴다.
     */
    @Nested
    @DisplayName("Apple 연결 철회 알림")
    class EndAppleSession {

        @Test
        @DisplayName("세션을 끊고 토큰 칸을 비우되 수단은 남긴다 · Apple 에 철회를 다시 보내지 않는다")
        void ends_sessions_and_keeps_the_link() {
            User user = user();
            UserAuth apple = UserAuth.builder().id(11L).user(user).authType(AuthType.APPLE).identifier("apple-sub-1")
                    .providerRefreshToken("v1:cipher").providerClientId("com.kovil.app").build();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, "apple-sub-1"))
                    .willReturn(Optional.of(apple));

            assertThat(service.endAppleSession("apple-sub-1")).isTrue();

            InOrder order = Mockito.inOrder(refreshTokenService, userAuthRepository);
            order.verify(refreshTokenService).revokeAllFor(7L);
            order.verify(userAuthRepository).clearProviderRefreshTokenIfLinked(11L);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            then(appleTokenExchanger).shouldHaveNoInteractions();
        }

        // 엔티티를 고치면 커밋 때 전 칼럼 UPDATE 가 나가 겹친 탈퇴의 deleted_at 을 NULL 로 덮는다.
        // 칸 비우기는 살아 있는 행에만 거는 조건부 갱신이어야 하고, 읽어 온 엔티티는 그대로여야 한다.
        @Test
        @DisplayName("토큰 칸은 조건부 갱신으로만 비우고 읽어 온 엔티티는 고치지 않는다")
        void clears_by_conditional_update_not_by_dirty_entity() {
            UserAuth apple = UserAuth.builder().id(11L).user(user()).authType(AuthType.APPLE).identifier("apple-sub-1")
                    .providerRefreshToken("v1:cipher").providerClientId("com.kovil.app").build();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, "apple-sub-1"))
                    .willReturn(Optional.of(apple));

            service.endAppleSession("apple-sub-1");

            assertThat(apple.getProviderRefreshToken()).isEqualTo("v1:cipher");
            assertThat(apple.getProviderClientId()).isEqualTo("com.kovil.app");
            then(userAuthRepository).should(never()).save(any());
        }

        // 같은 알림이 다시 와도 같은 결과다. 이미 빈 칸을 다시 비우고 남은 세션이 없을 뿐이다.
        @Test
        @DisplayName("두 번 와도 같은 결과다")
        void is_idempotent() {
            UserAuth apple = UserAuth.builder().id(11L).user(user()).authType(AuthType.APPLE).identifier("apple-sub-1").build();
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, "apple-sub-1"))
                    .willReturn(Optional.of(apple));

            assertThat(service.endAppleSession("apple-sub-1")).isTrue();
            assertThat(service.endAppleSession("apple-sub-1")).isTrue();

            then(userAuthRepository).should(times(2)).clearProviderRefreshTokenIfLinked(11L);
            assertThat(apple.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("연결된 회원이 없으면 아무것도 하지 않고 false 다")
        void unknown_sub_does_nothing() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, "nobody"))
                    .willReturn(Optional.empty());

            assertThat(service.endAppleSession("nobody")).isFalse();

            then(refreshTokenService).shouldHaveNoInteractions();
        }
    }
}
