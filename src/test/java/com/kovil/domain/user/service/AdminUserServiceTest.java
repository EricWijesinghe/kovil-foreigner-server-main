package com.kovil.domain.user.service;

import com.kovil.domain.user.service.AccountBlockService;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.verification.service.VerificationQueryService;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.domain.auth.service.PhoneNumberNormalizer;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.CommunityReportRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.rentalrequest.service.RentalRequestPoolReturner;
import com.kovil.domain.user.entity.AdminPhoneReveal;
import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.repository.AdminPhoneRevealRepository;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.repository.UserStatusHistoryRepository;
import com.kovil.infra.crypto.Hasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.Nested;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// FR-907 — 정지는 전면 차단이다(관문은 getActiveUser 한 곳).
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final String ADMIN_UUID = "admin-uuid";
    private static final String TARGET_UUID = "target-uuid";

    private static final Instant NOW = Instant.parse("2026-08-15T03:00:00Z");

    @Mock UserRepository userRepository;
    @Mock UserAuthRepository userAuthRepository;
    @Mock UserStatusHistoryRepository userStatusHistoryRepository;
    @Mock UserQueryService userQueryService;
    @Mock VerificationQueryService verificationQueryService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock AccountBlockService accountBlockService;
    @Mock RentalRequestRepository rentalRequestRepository;
    @Mock CommunityPostRepository communityPostRepository;
    @Mock CommunityReportRepository communityReportRepository;
    @Mock AdminPhoneRevealRepository phoneRevealRepository;
    @Mock Encryptor encryptor;
    @Mock Hasher hasher;
    @Mock RentalRequestPoolReturner rentalRequestPoolReturner;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        // 정규화기는 실물 — 검색어가 전화번호 축으로 갈리는 판정이 가입과 같은 규칙이어야 한다.
        service = new AdminUserService(userRepository, userAuthRepository, userStatusHistoryRepository,
                userQueryService,
                verificationQueryService, refreshTokenService, accountBlockService,
                rentalRequestRepository, communityPostRepository, communityReportRepository,
                phoneRevealRepository, encryptor, hasher,
                new PhoneNumberNormalizer(),
                rentalRequestPoolReturner,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    // 검색어 하나가 텍스트/전화 축으로 갈리는 판정 — 틀리면 "있는 회원을 못 찾는" 조용한 실패다.
    @Nested
    @DisplayName("회원 검색 축 판정")
    class SearchAxis {

        private void stubEmpty() {
            BDDMockito.given(userRepository.searchForAdmin(
                            ArgumentMatchers.any(), ArgumentMatchers.any(),
                            ArgumentMatchers.any(), ArgumentMatchers.any(),
                            ArgumentMatchers.any()))
                    .willReturn(new PageImpl<>(List.of()));
        }

        @Test
        @DisplayName("전체 전화번호는 정규화·해시를 거쳐 phone_hash 축으로 간다")
        void full_phone_goes_to_hash_axis() {
            BDDMockito.given(hasher.hash("01012341234")).willReturn("HASHED");
            stubEmpty();

            service.search(null, null, "010-1234-1234", PageRequest.of(0, 20));

            BDDMockito.then(userRepository).should().searchForAdmin(
                    null, null, null, "HASHED", PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("부분 번호·일반 텍스트는 텍스트 축으로 간다 — 해시를 거치지 않는다")
        void partial_phone_stays_on_text_axis() {
            stubEmpty();

            service.search(null, null, "010-1234", PageRequest.of(0, 20));

            BDDMockito.then(userRepository).should().searchForAdmin(
                    null, null, "010-1234", null, PageRequest.of(0, 20));
            BDDMockito.then(hasher).should(Mockito.never()).hash(ArgumentMatchers.any());
        }
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

    private static User user(Long id, String uuid, UserStatus status) {
        return withId(User.builder().uuid(uuid).name("n").phone("cipher").preferredLanguage("en")
                .status(status).role(UserRole.USER).build(), id);
    }

    private static String codeOf(Throwable e) {
        return ((UserException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("정지는 상태를 바꾸고 리프레시 토큰을 전량 회수한다")
    void suspend_blocks_and_revokes_sessions() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        User target = user(10L, TARGET_UUID, UserStatus.ACTIVE);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(target));

        service.suspend(ADMIN_UUID, TARGET_UUID, null);

        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        then(refreshTokenService).should().revokeAllFor(10L);
    }

    /*
     * ★ 적대적 리뷰 2026-09-17 2라운드에서 잡은 것.
     *
     * 강등·탈퇴만 막고 정지를 열어 두면 **정지 → 탈퇴 두 수로 관리자가 0명**이 된다.
     * 게다가 세는 쿼리가 status 를 보지 않아, 정지된 관리자를 관리자로 세면서
     * "아직 2명"이라 통과시켰다 — 실제로 들어갈 수 있는 사람은 없는데도.
     */
    @Test
    @DisplayName("마지막 관리자는 정지할 수 없다 — 정지도 관리자를 줄인다")
    void last_admin_cannot_be_suspended() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        User lastAdmin = withId(User.builder().uuid(TARGET_UUID).name("n").preferredLanguage("en")
                .status(UserStatus.ACTIVE).role(UserRole.ADMIN).build(), 10L);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(lastAdmin));
        given(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).willReturn(List.of(10L));

        assertThatThrownBy(() -> service.suspend(ADMIN_UUID, TARGET_UUID, null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LAST_ADMIN_CANNOT_BE_SUSPENDED"));
        assertThat(lastAdmin.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("관리자가 둘 이상이면 정지된다")
    void admin_can_be_suspended_when_another_remains() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        User admin = withId(User.builder().uuid(TARGET_UUID).name("n").preferredLanguage("en")
                .status(UserStatus.ACTIVE).role(UserRole.ADMIN).build(), 10L);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(admin));
        given(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).willReturn(List.of(2L, 10L));

        service.suspend(ADMIN_UUID, TARGET_UUID, null);

        assertThat(admin.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("자기 자신은 정지할 수 없다")
    void self_suspend_is_rejected() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        given(userRepository.findByUuidAndDeletedAtIsNull(ADMIN_UUID))
                .willReturn(Optional.of(user(2L, ADMIN_UUID, UserStatus.ACTIVE)));

        assertThatThrownBy(() -> service.suspend(ADMIN_UUID, ADMIN_UUID, null))
                .extracting(AdminUserServiceTest::codeOf).isEqualTo("SELF_SUSPEND");
    }

    @Test
    @DisplayName("이미 정지된 계정 재정지는 조용히 지나가고 토큰 회수도 다시 하지 않는다")
    void suspend_is_idempotent() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID))
                .willReturn(Optional.of(user(10L, TARGET_UUID, UserStatus.SUSPENDED)));

        service.suspend(ADMIN_UUID, TARGET_UUID, null);

        then(refreshTokenService).should(never()).revokeAllFor(10L);
    }

    /*
     * ★ 사무소 대표자(파트너)의 계정을 정지하면 그 사무소가 맡은 진행 중 임대 요청은 대기로 돌아간다 — 정지된 사람은 연락을
     *   이어 갈 수 없다(부문 정지와 같은 판단). 세션을 끊은 **뒤**(그 사람의 users 행을 거기서 잡는다)에 한다.
     *   유학생 · 관리자 정지, 이미 정지된 계정, 정지 해제는 건드리지 않는다(되찾지 않는다).
     */
    @Test
    @DisplayName("파트너 계정 정지는 세션을 끊은 뒤 그 사람이 대표인 사무소의 진행 중 요청을 대기로 되돌린다")
    void partner_suspension_returns_held_requests() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        User partner = withId(User.builder().uuid(TARGET_UUID).name("n").preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(UserRole.PARTNER).build(), 10L);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(partner));

        service.suspend(ADMIN_UUID, TARGET_UUID, "허위 매물");

        InOrder order = Mockito.inOrder(refreshTokenService, rentalRequestPoolReturner);
        order.verify(refreshTokenService).revokeAllFor(10L);
        order.verify(rentalRequestPoolReturner).returnHeldByRepresentative(10L,
                LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("유학생 정지 · 이미 정지된 계정 · 정지 해제는 임대 요청을 건드리지 않는다")
    void non_partner_and_repeat_and_unsuspend_return_nothing() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID))
                .willReturn(Optional.of(user(10L, TARGET_UUID, UserStatus.ACTIVE)));
        service.suspend(ADMIN_UUID, TARGET_UUID, null);

        User suspendedPartner = withId(User.builder().uuid(TARGET_UUID).name("n").preferredLanguage("ko")
                .status(UserStatus.SUSPENDED).role(UserRole.PARTNER).build(), 10L);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(suspendedPartner));
        service.suspend(ADMIN_UUID, TARGET_UUID, null);
        service.unsuspend(ADMIN_UUID, TARGET_UUID);

        then(rentalRequestPoolReturner).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("해제는 ACTIVE로 되돌린다")
    void unsuspend_restores() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        User target = user(10L, TARGET_UUID, UserStatus.SUSPENDED);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(target));

        service.unsuspend(ADMIN_UUID, TARGET_UUID);

        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // ★ 화면이 "열람 기록이 남습니다"라고 적으려면 실제로 남아야 한다.
    // 상세에 평문을 실어 두면 화면을 열기만 해도 번호가 흘러 그 약속이 거짓이 된다.
    @Nested
    @DisplayName("전화번호 열람 경계")
    class PhoneReveal {

        @Test
        @DisplayName("회원 상세 응답에는 전화번호가 없다")
        void detail_carries_no_phone() {
            User target = user(10L, TARGET_UUID, UserStatus.ACTIVE);
            // 상세는 읽기 경로다 — 탈퇴자도 보여야 하므로 deletedAt 을 거르지 않는 조회를 쓴다.
            given(userRepository.findByUuid(TARGET_UUID)).willReturn(Optional.of(target));
            given(verificationQueryService.findEffective(10L)).willReturn(Optional.empty());
            given(userAuthRepository.findByUserIdAndDeletedAtIsNull(10L)).willReturn(List.of());
            given(rentalRequestRepository.countByUserIdAndStatusIn(any(), any())).willReturn(0L);
            given(communityPostRepository.countByUserIdAndDeletedAtIsNull(10L)).willReturn(3L);
            given(communityReportRepository.countReceivedBy(10L)).willReturn(0L);

            var detail = service.getDetail(TARGET_UUID);

            String json = JsonMapper.builder().build()
                    .writeValueAsString(detail);
            assertThat(json).doesNotContain("01012345678").doesNotContain("phone");
            // 정지 판단은 이 사람이 무엇을 하고 있었는지를 한 화면에서 봐야 선다.
            assertThat(detail.activity().communityPostCount()).isEqualTo(3);
            then(encryptor).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("열람은 평문을 주고 그 사실을 기록으로 남긴다")
        void reveal_writes_an_audit_row() {
            given(userQueryService.getActiveUser(ADMIN_UUID))
                    .willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
            User target = user(10L, TARGET_UUID, UserStatus.ACTIVE);
            given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(target));
            given(encryptor.decrypt("cipher")).willReturn("01012345678");
            given(phoneRevealRepository.countByTargetUserId(10L)).willReturn(3L);

            var revealed = service.revealPhone(ADMIN_UUID, TARGET_UUID);

            assertThat(revealed.phone()).isEqualTo("01012345678");
            assertThat(revealed.revealCount()).isEqualTo(3);

            var saved = ArgumentCaptor.forClass(
                    AdminPhoneReveal.class);
            then(phoneRevealRepository).should().save(saved.capture());
            assertThat(saved.getValue().getAdminUserId()).isEqualTo(2L);
            assertThat(saved.getValue().getTargetUserId()).isEqualTo(10L);
        }
    }

    // 정지 화면이 "언제·왜"를 말할 수 있어야 한다. 해제하면 그 흔적도 함께 지운다 —
    // 남겨 두면 지금 정지 중인 것처럼 읽힌다.
    @Test
    @DisplayName("정지는 시각과 사유를 남기고 해제는 둘 다 지운다")
    void suspend_records_when_and_why() {
        given(userQueryService.getActiveUser(ADMIN_UUID)).willReturn(user(2L, ADMIN_UUID, UserStatus.ACTIVE));
        User target = user(10L, TARGET_UUID, UserStatus.ACTIVE);
        given(userRepository.findByUuidAndDeletedAtIsNull(TARGET_UUID)).willReturn(Optional.of(target));

        service.suspend(ADMIN_UUID, TARGET_UUID, "  광고 반복 게시  ");

        assertThat(target.getSuspendedAt()).isNotNull();
        assertThat(target.getSuspendReason()).isEqualTo("광고 반복 게시");

        service.unsuspend(ADMIN_UUID, TARGET_UUID);

        assertThat(target.getSuspendedAt()).isNull();
        assertThat(target.getSuspendReason()).isNull();
    }

    /*
     * ★ unsuspend 가 users.suspend_reason 을 NULL 로 지운다(바로 위 테스트가 그걸 고정한다).
     *   그러면 "왜 정지했었나"를 답할 수 있는 곳은 이 원장뿐인데, 읽는 경로가 하나도 없었다 —
     *   쌓기만 하고 안 읽으면 없는 것과 같다(적대적 리뷰 2026-09-02).
     */
    @Test
    @DisplayName("상태 이력을 최신순으로 돌려준다 — 해제 뒤에도 정지 사유가 남는다")
    void status_histories_are_readable() {
        User target = user(10L, TARGET_UUID, UserStatus.ACTIVE);
        given(userRepository.findByUuid(TARGET_UUID)).willReturn(Optional.of(target));
        given(userStatusHistoryRepository.findByUserIdOrderByIdDesc(10L)).willReturn(List.of(
                UserStatusHistory.builder().userId(10L).fromStatus(UserStatus.SUSPENDED)
                        .toStatus(UserStatus.ACTIVE).actorNote(UserStatusHistory.ACTOR_ADMIN).build(),
                UserStatusHistory.builder().userId(10L).fromStatus(UserStatus.ACTIVE)
                        .toStatus(UserStatus.SUSPENDED).actorNote(UserStatusHistory.ACTOR_ADMIN)
                        .reason("커뮤니티 광고 반복").build()));

        var histories = service.getStatusHistories(TARGET_UUID);

        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).toStatus()).isEqualTo(UserStatus.ACTIVE);
        // 해제 행에는 사유가 없지만, 그 아래 정지 행이 사유를 그대로 들고 있다.
        assertThat(histories.get(1).toStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(histories.get(1).reason()).isEqualTo("커뮤니티 광고 반복");
    }

    // 목록이 탈퇴자를 보여주게 됐으므로 상세·이력도 열려야 한다 — 클릭하면 404 인 행은 고장이다.
    @Test
    @DisplayName("탈퇴자도 이력을 볼 수 있다 — 읽기 경로는 deleted_at 을 거르지 않는다")
    void withdrawn_user_histories_are_readable() {
        User gone = user(10L, TARGET_UUID, UserStatus.WITHDRAWN);
        gone.withdraw(LocalDateTime.of(2026, 9, 2, 10, 0));
        given(userRepository.findByUuid(TARGET_UUID)).willReturn(Optional.of(gone));
        given(userStatusHistoryRepository.findByUserIdOrderByIdDesc(10L)).willReturn(List.of());

        assertThat(service.getStatusHistories(TARGET_UUID)).isEmpty();
        // 변경 경로(정지)는 그대로 탈퇴자를 막는다 — 떠난 계정을 정지하는 것은 조치가 아니다.
        then(userRepository).should(Mockito.never())
                .findByUuidAndDeletedAtIsNull(TARGET_UUID);
    }
}
