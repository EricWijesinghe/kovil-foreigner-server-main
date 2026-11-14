package com.kovil.domain.partner.service;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileQueryService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionDetailResponse;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionSummaryResponse;
import com.kovil.domain.partner.dto.response.RegNoSuspensionView;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.enums.SameRegNoApplicationKind;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.service.RentalRequestPoolReturner;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.assertj.core.groups.Tuple;
import org.mockito.BDDMockito;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/*
 * 부문 심사. 판정 격자와 함께 고정하는 것:
 *   · 세션은 **정지에만** 끊는다 — 승인 · 반려 · 복구에서 끊으면 파트너가 이유도 모른 채 로그아웃된다.
 *   · 잠금 순서 — 정지는 세션(users 잠금)을 먼저 끊고, 승인 · 복구는 users 행을 잠금 읽기로 본 **뒤에** 부문을 고친다.
 *     부문을 먼저 고치면 같은 순간의 로그인(발급 경로)과 교착한다.
 *   · 관리자가 본 판(version)과 다르면 승인 · 반려하지 않는다.
 *   · 승인 · 복구가 등록번호를 잡고, 다른 사무소가 잡고 있으면 409(사전 검사와 유니크 위반 둘 다).
 *   · 네 처분 모두 파트너에게 알린다 — 반려만 사유를 싣고, 처분이 성립하지 않으면 알리지 않는다.
 *   · 정지할 때마다 그 사무소의 등록번호를 정지 이력에 남긴다 — 번호는 사무소 행을 잠그고 부문을 고치기 전에 읽는다.
 *     심사 상세 · 대기열은 그 이력으로 경고만 한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminPartnerDivisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
    private static final long ADMIN_ID = 1L;
    private static final long PARTNER_ID = 7L;
    private static final long OFFICE_ID = 70L;
    private static final long SEEN = 3L;
    private static final String REG_NO = "99999-2026-00002";

    @Mock PartnerDivisionRepository divisionRepository;
    @Mock UserRepository userRepository;
    @Mock UserQueryService userQueryService;
    @Mock BrokerProfileRepository profileRepository;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock ListingRepository listingRepository;
    @Mock StoredFileRepository storedFileRepository;
    @Mock FileQueryService fileQueryService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock NotificationPublisher notificationPublisher;
    @Mock RegNoSuspensionService regNoSuspensionService;
    @Mock BrokerageOfficeChangeRequestRepository changeRequestRepository;
    @Mock RentalRequestPoolReturner poolReturner;

    private AdminPartnerDivisionService service;

    @BeforeEach
    void setUp() {
        service = new AdminPartnerDivisionService(divisionRepository, userRepository, userQueryService,
                profileRepository, officeRepository, listingRepository, storedFileRepository,
                fileQueryService, refreshTokenService, notificationPublisher, regNoSuspensionService,
                new SameRegNoApplicationFinder(officeRepository, profileRepository, divisionRepository,
                        changeRequestRepository, userRepository),
                poolReturner, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
        lenient().when(userQueryService.getActiveUser("admin")).thenReturn(
                User.builder().id(ADMIN_ID).uuid("admin").role(UserRole.ADMIN).status(UserStatus.ACTIVE).build());
        lenient().when(userRepository.findById(PARTNER_ID)).thenReturn(Optional.of(partner()));
        lenient().when(profileRepository.findByUserIdIn(any())).thenReturn(List.of());
    }

    private static User partner() {
        return User.builder().id(PARTNER_ID).uuid("p-1").name("홍길동").role(UserRole.PARTNER)
                .status(UserStatus.ACTIVE).build();
    }

    private PartnerDivision given(PartnerDivisionStatus status, LocalDateTime submittedAt) {
        PartnerDivision d = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status).submittedAt(submittedAt)
                .version(SEEN).build();
        BDDMockito.given(divisionRepository.findByUuid("pd-1")).willReturn(Optional.of(d));
        return d;
    }

    private void givenAccountStatus(UserStatus status) {
        BDDMockito.given(userRepository.findAuthSnapshotForUpdate(PARTNER_ID))
                .willReturn(Optional.of(snapshot(status)));
    }

    private static UserRepository.AuthSnapshot snapshot(UserStatus status) {
        return new UserRepository.AuthSnapshot() {
            @Override public String getUuid() { return "p-1"; }
            @Override public String getRole() { return "PARTNER"; }
            @Override public String getStatus() { return status.name(); }
        };
    }

    // 대표자 연결로 찾히는 그 사람의 사무소. claimedRegNo 는 지금 잡고 있는 번호(없으면 null).
    private BrokerageOffice givenOffice(String regNo, String claimedRegNo) {
        BrokerageOffice office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo(regNo)
                .claimedRegNo(claimedRegNo).name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build();
        lenient().when(profileRepository.findByUserId(PARTNER_ID)).thenReturn(Optional.of(
                BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
        lenient().when(officeRepository.findById(OFFICE_ID)).thenReturn(Optional.of(office));
        return office;
    }

    private static PartnerErrorCode codeOf(Throwable e) {
        return (PartnerErrorCode) ((PartnerException) e).getErrorCode();
    }

    private static String listingCodeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("dup", new ConstraintViolationException("dup", null, constraint));
    }

    private static final LocalDateTime SUBMITTED = LocalDateTime.of(2026, 10, 5, 9, 0);

    @Nested
    @DisplayName("세션 끊기와 잠금 순서")
    class Sessions {

        /*
         * ★ 승인은 세션을 끊지 않는다 — 늘어나는 권한은 낡은 토큰이 들고 있어도 해가 없고 앱이 리프레시로 받는다.
         *   대신 users 행을 잠금 읽기로 **먼저** 본다: 그 순간 부문은 아직 대기여야 한다(부문을 먼저 고치면
         *   그 행을 쥔 채 users 를 기다리고, 발급 경로는 users 를 쥔 채 그 행을 기다린다).
         */
        @Test
        @DisplayName("승인은 세션을 끊지 않고, users 행을 잠금 읽기로 본 뒤에 부문 · 사무소를 고친다")
        void approve_locks_user_before_changing_division_and_keeps_sessions() {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);
            BrokerageOffice office = givenOffice(REG_NO, null);
            willAnswer(inv -> {
                assertThat(d.getStatus()).as("users 를 잠그는 순간의 부문 상태").isEqualTo(PartnerDivisionStatus.PENDING);
                assertThat(office.getClaimedRegNo()).as("users 를 잠그는 순간의 번호").isNull();
                return Optional.of(snapshot(UserStatus.ACTIVE));
            }).given(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);

            service.approve("admin", "pd-1", SEEN);

            then(userRepository).should().findAuthSnapshotForUpdate(PARTNER_ID);
            then(refreshTokenService).shouldHaveNoInteractions();
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(d.getReviewedBy()).isEqualTo(ADMIN_ID);
        }

        @Test
        @DisplayName("반려 · 복구도 세션을 끊지 않는다")
        void reject_and_reinstate_keep_sessions() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            service.reject("admin", "pd-1", "등록증이 흐립니다", SEEN);

            PartnerDivision suspended = given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);
            givenOffice(REG_NO, REG_NO);
            givenAccountStatus(UserStatus.ACTIVE);
            service.reinstate("admin", "pd-1");

            assertThat(suspended.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            then(refreshTokenService).shouldHaveNoInteractions();
        }

        /*
         * ★ 정지만 끊는다 — 줄어드는 권한이라 끊지 않으면 낡은 토큰이 수명만큼 기능을 열어 둔다.
         *   **먼저** 끊는다(users 잠금이 여기서 잡힌다) — 끊는 그 순간 부문은 아직 승인 상태여야 한다.
         */
        @Test
        @DisplayName("정지는 부문을 고치기 전에 그 사람의 세션을 끊는다")
        void suspend_cuts_sessions_before_changing_division() {
            PartnerDivision d = given(PartnerDivisionStatus.APPROVED, SUBMITTED);
            willAnswer(inv -> {
                assertThat(d.getStatus()).as("세션을 끊는 순간의 부문 상태").isEqualTo(PartnerDivisionStatus.APPROVED);
                return null;
            }).given(refreshTokenService).revokeAllFor(PARTNER_ID);

            service.suspend("admin", "pd-1", "허위 매물");

            then(refreshTokenService).should().revokeAllFor(PARTNER_ID);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        }

        // 두 번 누른 처분 · 이미 처리된 건이 그 사람의 세션 · 잠금을 괜히 건드리지 않게 — 판정을 먼저 한다.
        @Test
        @DisplayName("전이가 성립하지 않으면 세션도 users 잠금도 건드리지 않는다")
        void invalid_transition_touches_nothing() {
            given(PartnerDivisionStatus.APPROVED, SUBMITTED);
            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_REVIEWABLE);
            assertThatThrownBy(() -> service.reinstate("admin", "pd-1"))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_SUSPENDED);

            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            assertThatThrownBy(() -> service.suspend("admin", "pd-1", "사유"))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_APPROVED);

            then(refreshTokenService).shouldHaveNoInteractions();
            then(userRepository).should(never()).findAuthSnapshotForUpdate(any());
        }
    }

    /*
     * ★ 관리자가 상세를 본 뒤 신청자가 다시 냈으면(version 이 오른다) 관리자가 본 적 없는 서류가 승인된다.
     *   @Version 은 한 요청 안만 지키므로 요청에 실린 판과 대조한다.
     */
    @Nested
    @DisplayName("관리자가 본 판")
    class SeenVersion {

        @Test
        @DisplayName("본 판이 아니면 승인하지 않는다 — 잠금도 번호도 건드리지 않는다")
        void approve_with_stale_version_is_rejected() {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);

            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN - 1))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_CHANGED);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            then(userRepository).should(never()).findAuthSnapshotForUpdate(any());
            then(officeRepository).should(never()).existsByClaimedRegNoAndIdNot(any(), any());
        }

        @Test
        @DisplayName("본 판이 아니면 반려하지 않는다")
        void reject_with_stale_version_is_rejected() {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);

            assertThatThrownBy(() -> service.reject("admin", "pd-1", "사유", SEEN + 1))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_CHANGED);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(d.getRejectReason()).isNull();
        }

        @Test
        @DisplayName("대기열 한 줄 응답에 version 과 마지막 승인 시각이 실린다")
        void summary_carries_version_and_last_approved_at() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);

            AdminPartnerDivisionSummaryResponse response = service.approve("admin", "pd-1", SEEN);

            assertThat(response.version()).isEqualTo(SEEN);
            assertThat(response.lastApprovedAt()).isEqualTo(NOW_LOCAL);
        }
    }

    @Nested
    @DisplayName("등록번호 잡기")
    class RegNoClaim {

        // 승인 = 그 사무소가 번호를 잡는다. 잡은 뒤에 flush 해야 동시 승인의 유니크 위반이 이 요청 안에서 터진다.
        @Test
        @DisplayName("승인하면 사무소가 등록번호를 잡고 마지막 승인 시각이 찍힌 뒤 flush 한다")
        void approve_claims_reg_no() {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);
            BrokerageOffice office = givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);
            willAnswer(inv -> {
                assertThat(office.getClaimedRegNo()).as("flush 하는 순간 번호를 잡았다").isEqualTo(REG_NO);
                assertThat(d.getStatus()).as("flush 하는 순간 부문은 승인").isEqualTo(PartnerDivisionStatus.APPROVED);
                return null;
            }).given(officeRepository).flush();

            service.approve("admin", "pd-1", SEEN);

            then(officeRepository).should().flush();
            assertThat(office.getClaimedRegNo()).isEqualTo(REG_NO);
            assertThat(d.getLastApprovedAt()).isEqualTo(NOW_LOCAL);
        }

        // 같은 번호로 여러 명이 신청할 수 있지만 승인은 한 명만이다(PO 확정 2026-10-06).
        @Test
        @DisplayName("다른 사무소가 그 번호를 잡고 있으면 409 이고 부문 · 번호 모두 그대로다")
        void approve_is_blocked_when_another_office_claims() {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);
            BrokerageOffice office = givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);
            BDDMockito.given(officeRepository.existsByClaimedRegNoAndIdNot(REG_NO, OFFICE_ID))
                    .willReturn(true);

            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN))
                    .isInstanceOf(ListingException.class)
                    .extracting(AdminPartnerDivisionServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(office.getClaimedRegNo()).isNull();
            then(officeRepository).should(never()).flush();
        }

        // 동시에 두 신청을 승인하면 둘 다 사전 검사를 지난다 — uq_bo_claimed_reg_no 가 하나만 이기게 하고, 진 쪽은 순차 답과 같은 409.
        @Test
        @DisplayName("동시 승인의 유니크 위반(uq_bo_claimed_reg_no)은 OFFICE_REG_NO_DUPLICATED 로 번역한다")
        void claim_race_is_translated() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);
            willThrow(violationOf("brokerage_offices.uq_bo_claimed_reg_no")).given(officeRepository).flush();

            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN))
                    .isInstanceOf(ListingException.class)
                    .extracting(AdminPartnerDivisionServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
        }

        // 다른 제약의 위반까지 409 로 덮으면 진짜 결함이 "번호 중복"으로 숨는다.
        @Test
        @DisplayName("다른 제약의 위반은 번역하지 않고 그대로 올린다")
        void other_violation_is_not_translated() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);
            willThrow(violationOf("fk_bo_license_file")).given(officeRepository).flush();

            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /*
         * 사무소 행이 그사이 대표번호 변경 · 재제출로 바뀌었으면 flush 가 사무소의 낙관적 락 충돌로 진다.
         * 번호 중복으로 바꾸거나 삼키면 안 된다 — 그대로 올라가야 409 CONFLICT(상세를 다시 연다)가 되고, 승인 알림도 나가지 않는다.
         * 잡는 범위를 DataAccessException 으로 넓히면 이 테스트가 죽는다.
         */
        @Test
        @DisplayName("사무소 행의 낙관적 락 충돌은 번역하지 않고 그대로 올리며 알리지 않는다")
        void office_version_conflict_is_not_swallowed() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);
            willThrow(new ObjectOptimisticLockingFailureException(BrokerageOffice.class, OFFICE_ID))
                    .given(officeRepository).flush();

            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            then(notificationPublisher).shouldHaveNoInteractions();
        }

        // 이전 방식의 재심사로 번호를 고친 채 대기 중인 행(옛 데이터)은 승인될 때 새 번호를 잡는다 — 옛 번호는 그때 풀린다.
        @Test
        @DisplayName("번호가 고쳐진 채 대기 중인 옛 재심사 행은 승인될 때 고친 번호를 잡고 옛 번호를 놓는다")
        void legacy_re_review_approval_moves_the_claim() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            BrokerageOffice office = givenOffice("99999-2026-00009", REG_NO);
            givenAccountStatus(UserStatus.ACTIVE);

            service.approve("admin", "pd-1", SEEN);

            assertThat(office.getClaimedRegNo()).isEqualTo("99999-2026-00009");
            then(officeRepository).should().existsByClaimedRegNoAndIdNot("99999-2026-00009", OFFICE_ID);
        }

        @Test
        @DisplayName("복구도 번호를 잡고, 다른 사무소가 잡고 있으면 409 다")
        void reinstate_claims_reg_no() {
            PartnerDivision d = given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);
            BrokerageOffice office = givenOffice(REG_NO, REG_NO);
            givenAccountStatus(UserStatus.ACTIVE);

            service.reinstate("admin", "pd-1");

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(d.getLastApprovedAt()).isEqualTo(NOW_LOCAL);
            assertThat(office.getClaimedRegNo()).isEqualTo(REG_NO);
            then(officeRepository).should().flush();

            PartnerDivision again = given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);
            BDDMockito.given(officeRepository.existsByClaimedRegNoAndIdNot(REG_NO, OFFICE_ID))
                    .willReturn(true);
            assertThatThrownBy(() -> service.reinstate("admin", "pd-1"))
                    .extracting(AdminPartnerDivisionServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            assertThat(again.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        }

        /*
         * 정지는 번호를 놓지 않는다 — 놓으면 정지가 이어지는 동안 같은 번호의 다른 신청이 승인받는다. 반려는 잡지 않는다.
         * (정지는 이력에 남길 번호를 읽으려고 사무소를 찾는다 — 읽기만 하고 잡은 번호는 그대로다.)
         */
        @Test
        @DisplayName("정지 · 반려는 사무소의 번호를 건드리지 않는다")
        void suspend_and_reject_leave_claim_alone() {
            given(PartnerDivisionStatus.APPROVED, SUBMITTED);
            BrokerageOffice office = givenOffice(REG_NO, REG_NO);
            service.suspend("admin", "pd-1", "허위 매물");

            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            service.reject("admin", "pd-1", "등록증이 흐립니다", SEEN);

            assertThat(office.getClaimedRegNo()).isEqualTo(REG_NO);
            assertThat(office.getRegNo()).isEqualTo(REG_NO);
            then(officeRepository).should(never()).existsByClaimedRegNoAndIdNot(any(), any());
            then(officeRepository).should(never()).flush();
            // 반려는 사무소를 찾지도 않는다. 정지는 대표자 연결이 아니라 부문 행의 사무소 연결로 이력 번호를 찾는다.
            then(profileRepository).should(never()).findByUserId(PARTNER_ID);
        }
    }

    @Nested
    @DisplayName("능력을 여는 방향은 활성 계정에만")
    class ActiveOnly {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
        @DisplayName("정지 · 탈퇴 계정의 부문은 승인할 수 없다")
        void cannot_approve_inactive_account(UserStatus status) {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenAccountStatus(status);

            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_NOT_ACTIVE);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            then(officeRepository).should(never()).existsByClaimedRegNoAndIdNot(any(), any());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
        @DisplayName("정지 · 탈퇴 계정의 부문은 복구할 수 없다")
        void cannot_reinstate_inactive_account(UserStatus status) {
            PartnerDivision d = given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);
            givenAccountStatus(status);

            assertThatThrownBy(() -> service.reinstate("admin", "pd-1"))
                    .extracting(AdminPartnerDivisionServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_NOT_ACTIVE);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        }

        // 닫는 방향(정지)은 계정 상태와 무관하게 된다 — 정지된 계정이라도 부문까지 닫아 둘 수 있어야 한다.
        @Test
        @DisplayName("정지는 계정 상태를 보지 않는다")
        void suspend_does_not_require_active_account() {
            PartnerDivision d = given(PartnerDivisionStatus.APPROVED, SUBMITTED);

            service.suspend("admin", "pd-1", "사유");

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
            then(userRepository).should(never()).findAuthSnapshotForUpdate(any());
        }
    }

    /*
     * ★ 승인 · 복구는 세션을 끊지 않는다 — 앱이 새 토큰을 받으러 갈 신호가 이 알림이다. 빠지면 파트너는 승인된 줄 모른다.
     *   반려만 사유를 싣는다(무엇을 고쳐 낼지). 정지 사유는 관리자 내부 기록이라 싣지 않는다.
     *   같은 부문이 여러 번 오가므로 중복 키에 처분 시각이 들어가야 두 번째 처분부터 버려지지 않는다.
     */
    @Nested
    @DisplayName("심사 알림")
    class Notifications {

        private void thenNotified(NotiType type, Map<String, Object> params) {
            then(notificationPublisher).should().publish(PARTNER_ID, type,
                    type.name() + ":division:pd-1:" + NOW_LOCAL, params, "/partner/me");
        }

        @Test
        @DisplayName("승인하면 파트너에게 승인 알림을 보낸다")
        void approve_notifies() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);

            service.approve("admin", "pd-1", SEEN);

            thenNotified(NotiType.PARTNER_DIVISION_APPROVED, Map.of());
        }

        @Test
        @DisplayName("반려 알림은 사유를 싣는다")
        void reject_notifies_with_reason() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);

            service.reject("admin", "pd-1", "등록증이 흐립니다", SEEN);

            thenNotified(NotiType.PARTNER_DIVISION_REJECTED, Map.of("0", "등록증이 흐립니다"));
        }

        @Test
        @DisplayName("정지 알림은 사유를 싣지 않는다")
        void suspend_notifies_without_reason() {
            given(PartnerDivisionStatus.APPROVED, SUBMITTED);

            service.suspend("admin", "pd-1", "허위 매물");

            thenNotified(NotiType.PARTNER_DIVISION_SUSPENDED, Map.of());
        }

        @Test
        @DisplayName("복구하면 파트너에게 복구 알림을 보낸다")
        void reinstate_notifies() {
            given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);
            givenOffice(REG_NO, REG_NO);
            givenAccountStatus(UserStatus.ACTIVE);

            service.reinstate("admin", "pd-1");

            thenNotified(NotiType.PARTNER_DIVISION_REINSTATED, Map.of());
        }

        // 처분이 성립하지 않았는데 알림이 나가면 파트너는 일어나지 않은 승인 · 정지를 믿는다.
        @Test
        @DisplayName("처분이 성립하지 않으면 알리지 않는다 — 낡은 판 · 무효 전이 · 번호 중복")
        void failed_review_does_not_notify() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN - 1)).isInstanceOf(PartnerException.class);
            assertThatThrownBy(() -> service.reject("admin", "pd-1", "사유", SEEN + 1)).isInstanceOf(PartnerException.class);
            assertThatThrownBy(() -> service.suspend("admin", "pd-1", "사유")).isInstanceOf(PartnerException.class);

            givenOffice(REG_NO, null);
            givenAccountStatus(UserStatus.ACTIVE);
            BDDMockito.given(officeRepository.existsByClaimedRegNoAndIdNot(REG_NO, OFFICE_ID))
                    .willReturn(true);
            assertThatThrownBy(() -> service.approve("admin", "pd-1", SEEN)).isInstanceOf(ListingException.class);

            then(notificationPublisher).should(never()).publish(anyLong(), any(), anyString(), anyMap(), any());
        }
    }

    /*
     * ★ 부문을 정지하면 그 사무소가 맡은 진행 중 임대 요청은 대기로 돌아간다(PO 확정 2026-10-06 — 맡을 사람이 사라졌다).
     *   세션을 끊고(users 잠금) 사무소 행을 잠근 **뒤**, 부문을 고치기 **전**에 한다 — 전역 순서(users → 사무소 → … → 부문).
     *   복구해도 되찾지 않는다. 정지가 성립하지 않으면 건드리지 않는다.
     */
    @Nested
    @DisplayName("부문 정지와 임대 요청 대기 복귀")
    class RentalRequestsReturn {

        private PartnerDivision givenSuspendableWithOffice() {
            PartnerDivision d = given(PartnerDivisionStatus.APPROVED, SUBMITTED);
            ReflectionTestUtils.setField(d, "id", 11L);
            ReflectionTestUtils.setField(d, "officeId", OFFICE_ID);
            BDDMockito.given(officeRepository.findRegNoForSuspension(OFFICE_ID))
                    .willReturn(Optional.of(REG_NO));
            return d;
        }

        @Test
        @DisplayName("정지는 그 사무소의 진행 중 요청을 세션 · 사무소 잠금 뒤, 부문을 고치기 전에 대기로 되돌린다")
        void suspend_returns_held_requests_in_lock_order() {
            PartnerDivision d = givenSuspendableWithOffice();
            willAnswer(inv -> {
                assertThat(d.getStatus()).as("되돌리는 순간의 부문 상태").isEqualTo(PartnerDivisionStatus.APPROVED);
                return 2;
            }).given(poolReturner).returnHeldBy(OFFICE_ID, RentalRequestReturnCause.DIVISION_SUSPENDED, NOW_LOCAL);

            service.suspend("admin", "pd-1", "허위 매물");

            InOrder order = Mockito.inOrder(refreshTokenService, officeRepository, poolReturner);
            order.verify(refreshTokenService).revokeAllFor(PARTNER_ID);
            order.verify(officeRepository).findRegNoForSuspension(OFFICE_ID);
            order.verify(poolReturner).returnHeldBy(OFFICE_ID, RentalRequestReturnCause.DIVISION_SUSPENDED, NOW_LOCAL);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        }

        // 탈퇴한 파트너의 부문도 부문 행의 사무소 연결로 찾는다 — 그 사무소의 요청은 탈퇴 때 이미 돌아갔지만 다시 봐도 해가 없다.
        @Test
        @DisplayName("대표자 연결이 지워진 파트너의 부문 정지도 부문의 사무소 연결로 되돌린다")
        void suspend_uses_division_office_link() {
            givenSuspendableWithOffice();

            service.suspend("admin", "pd-1", "허위 매물");

            then(poolReturner).should().returnHeldBy(OFFICE_ID, RentalRequestReturnCause.DIVISION_SUSPENDED, NOW_LOCAL);
            then(profileRepository).should(never()).findByUserId(any());
        }

        @Test
        @DisplayName("부문에 사무소 연결이 없으면 되돌릴 요청도 없다")
        void suspend_without_office_returns_nothing() {
            PartnerDivision d = given(PartnerDivisionStatus.APPROVED, SUBMITTED);
            ReflectionTestUtils.setField(d, "officeId", null);

            service.suspend("admin", "pd-1", "허위 매물");

            then(poolReturner).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("정지가 성립하지 않으면(이미 정지) 요청을 건드리지 않는다")
        void failed_suspend_returns_nothing() {
            given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);

            assertThatThrownBy(() -> service.suspend("admin", "pd-1", "허위 매물"))
                    .isInstanceOf(PartnerException.class);
            then(poolReturner).shouldHaveNoInteractions();
        }

        // 복구 · 승인 · 반려는 맡을 사람을 늘리거나 그대로 두는 처분이다 — 대기로 돌아간 요청을 되찾지 않는다.
        @Test
        @DisplayName("복구는 대기로 돌아간 요청을 되찾지 않는다")
        void reinstate_does_not_reclaim() {
            given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);
            givenOffice(REG_NO, REG_NO);
            givenAccountStatus(UserStatus.ACTIVE);

            service.reinstate("admin", "pd-1");

            then(poolReturner).shouldHaveNoInteractions();
        }
    }

    /*
     * ★ 정지 이력(PO 확정 2026-10-07) — 정지할 때마다 그 사무소의 등록번호로 한 행. 나중에 같은 번호로 신청 · 변경 신청이 오면
     *   심사 화면이 경고한다(막지 않는다). 빠지면 정지 → 탈퇴 불가가 풀린 뒤(복구 · 탈퇴) 같은 번호의 새 신청이 아무 표시 없이 온다.
     */
    @Nested
    @DisplayName("정지 이력")
    class SuspensionHistory {

        // 가입이 부문 행에 남긴 사무소 연결(office_id)이 있는 승인 부문.
        private PartnerDivision givenSuspendable() {
            PartnerDivision d = given(PartnerDivisionStatus.APPROVED, SUBMITTED);
            ReflectionTestUtils.setField(d, "id", 11L);
            ReflectionTestUtils.setField(d, "officeId", OFFICE_ID);
            return d;
        }

        @Test
        @DisplayName("정지하면 그 사무소가 잡은 번호 · 사무소 · 부문 · 정지한 관리자 · 다듬은 사유 · 시각을 이력에 남긴다")
        void suspend_records_history() {
            givenSuspendable();
            givenOffice(REG_NO, REG_NO);
            BDDMockito.given(officeRepository.findRegNoForSuspension(OFFICE_ID))
                    .willReturn(Optional.of(REG_NO));

            service.suspend("admin", "pd-1", "  허위 매물  ");

            then(regNoSuspensionService).should()
                    .record(REG_NO, OFFICE_ID, 11L, ADMIN_ID, "허위 매물", NOW_LOCAL);
        }

        /*
         * ★ 번호는 사무소 행을 잠그고 읽는다(잠금 읽기) — 세션을 끊은 **뒤**, 부문을 고치기 **전**이다.
         *   부문을 먼저 고치면 그 갱신이 잠금 읽기 앞에서 flush 되어 부문 → 사무소 순으로 잠그게 되고, 사무소 → 부문 순으로
         *   잠그는 변경 승인과 교착한다.
         */
        @Test
        @DisplayName("이력의 번호는 세션을 끊은 뒤, 부문을 고치기 전에 사무소 행을 잠그고 읽는다")
        void reads_number_after_sessions_before_division_change() {
            PartnerDivision d = givenSuspendable();
            givenOffice(REG_NO, REG_NO);
            willAnswer(inv -> {
                assertThat(d.getStatus()).as("번호를 읽는 순간의 부문 상태").isEqualTo(PartnerDivisionStatus.APPROVED);
                return Optional.of(REG_NO);
            }).given(officeRepository).findRegNoForSuspension(OFFICE_ID);

            service.suspend("admin", "pd-1", "허위 매물");

            InOrder order = Mockito.inOrder(refreshTokenService, officeRepository,
                    regNoSuspensionService);
            order.verify(refreshTokenService).revokeAllFor(PARTNER_ID);
            order.verify(officeRepository).findRegNoForSuspension(OFFICE_ID);
            order.verify(regNoSuspensionService).record(any(), any(), any(), any(), any(), any());
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        }

        /*
         * ★ 탈퇴한 파트너의 승인 부문을 정지해도 이력이 남는다 — 탈퇴는 대표자 연결(broker_profiles)을 지우지만 부문 행의
         *   사무소 연결은 남는다. 대표자 연결로 찾던 때는 여기서 이력이 조용히 빠졌다. 번호는 탈퇴로 풀렸으므로 신청 번호가 남는다
         *   (잠금 읽기의 COALESCE(claimed_reg_no, reg_no) — 여기서는 그 결과를 돌려받는다).
         */
        @Test
        @DisplayName("대표자 연결이 지워진(탈퇴한) 파트너의 부문도 부문의 사무소 연결로 정지 이력을 남긴다")
        void suspend_of_withdrawn_partner_still_records_history() {
            PartnerDivision d = givenSuspendable();
            BDDMockito.given(officeRepository.findRegNoForSuspension(OFFICE_ID))
                    .willReturn(Optional.of(REG_NO));

            service.suspend("admin", "pd-1", "허위 매물");

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
            then(regNoSuspensionService).should().record(REG_NO, OFFICE_ID, 11L, ADMIN_ID, "허위 매물", NOW_LOCAL);
            then(profileRepository).should(never()).findByUserId(any());
        }

        // 부문에 사무소 연결이 없으면(V27 전에 탈퇴해 백필할 근거가 없던 부문) 남길 번호가 없다 — 정지 자체는 된다.
        @Test
        @DisplayName("부문에 사무소 연결이 없으면 이력을 남기지 않고 정지만 한다")
        void suspend_without_office_records_nothing() {
            PartnerDivision d = givenSuspendable();
            ReflectionTestUtils.setField(d, "officeId", null);

            service.suspend("admin", "pd-1", "허위 매물");

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
            then(officeRepository).should(never()).findRegNoForSuspension(any());
            then(regNoSuspensionService).should(never()).record(any(), any(), any(), any(), any(), any());
        }

        // 두 번 누른 정지는 성립하지 않는다 — 이력이 두 줄 쌓이지 않는다.
        @Test
        @DisplayName("정지가 성립하지 않으면 이력을 남기지 않는다")
        void failed_suspend_records_nothing() {
            given(PartnerDivisionStatus.SUSPENDED, SUBMITTED);

            assertThatThrownBy(() -> service.suspend("admin", "pd-1", "허위 매물"))
                    .isInstanceOf(PartnerException.class);
            then(regNoSuspensionService).shouldHaveNoInteractions();
            then(officeRepository).should(never()).findRegNoForSuspension(any());
        }

        @Test
        @DisplayName("심사 상세는 신청 번호의 정지 이력을 싣는다 — 경고만, 승인 가능 여부와 무관하다")
        void detail_carries_history_of_the_applied_number() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            BDDMockito.given(profileRepository.findByUserIdIn(List.of(PARTNER_ID))).willReturn(
                    List.of(BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
            BDDMockito.given(officeRepository.findAllById(any())).willReturn(List.of(
                    BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo(REG_NO).name("테스트공인중개사사무소")
                            .ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build()));
            RegNoSuspensionView past = new RegNoSuspensionView(SUBMITTED.minusDays(90), REG_NO, "o-old",
                    "떠난공인중개사사무소", "허위 매물");
            // 신청 번호 · 이 사무소로 묻는다 — 이 사무소가 다른 번호로 정지됐던 기록도 함께 실린다.
            BDDMockito.given(regNoSuspensionService.historyOf(List.of(REG_NO), OFFICE_ID))
                    .willReturn(List.of(past));

            AdminPartnerDivisionDetailResponse detail = service.getDetail("pd-1",
                    new AuthenticatedUser("admin", UserRole.ADMIN, "jti", NOW.plusSeconds(600)));

            assertThat(detail.regNoSuspensionHistory()).containsExactly(past);
        }

        /*
         * 대기열은 행마다 경고를 한 번에 표시한다 — 그 사무소의 신청 번호가 정지된 적이 있거나, 그 사무소 자신이 정지된 적이
         * 있으면(번호를 바꾼 뒤라 번호로는 안 보여도) 켠다. 한 페이지의 번호 · 사무소는 한 번에 묻는다.
         */
        @Test
        @DisplayName("대기열은 행마다 그 사무소 번호 또는 그 사무소 자신의 정지 이력 여부를 한 번에 표시한다")
        void queue_flags_suspended_numbers() {
            PartnerDivision first = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING)
                    .submittedAt(SUBMITTED).build();
            PartnerDivision second = PartnerDivision.builder().uuid("pd-2").userId(8L)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING)
                    .submittedAt(SUBMITTED.plusHours(1)).build();
            PartnerDivision third = PartnerDivision.builder().uuid("pd-3").userId(9L)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING)
                    .submittedAt(SUBMITTED.plusHours(2)).build();
            BDDMockito.given(divisionRepository.findSubmitted(any(), any(), any()))
                    .willReturn(new PageImpl<>(List.of(first, second, third), PageRequest.of(0, 20), 3));
            BDDMockito.given(userRepository.findAllById(any())).willReturn(List.of(partner()));
            BDDMockito.given(profileRepository.findByUserIdIn(any())).willReturn(List.of(
                    BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build(),
                    BrokerProfile.builder().userId(8L).officeId(80L).build(),
                    BrokerProfile.builder().userId(9L).officeId(90L).build()));
            BDDMockito.given(officeRepository.findAllById(any())).willReturn(List.of(
                    BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo(REG_NO).name("가")
                            .ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build(),
                    BrokerageOffice.builder().id(80L).uuid("o-8").regNo("99999-2026-00003").name("나")
                            .ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build(),
                    BrokerageOffice.builder().id(90L).uuid("o-9").regNo("99999-2026-00004").name("다")
                            .ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build()));
            // 정지된 적이 있는 번호는 REG_NO, 정지된 적이 있는 사무소는 90(지금 번호는 깨끗하다 — 번호를 바꿨다).
            BDDMockito.given(regNoSuspensionService.marksAmong(any(), any()))
                    .willReturn(new RegNoSuspensionService.SuspensionMarks(Set.of(REG_NO), Set.of(90L)));

            var page = service.findSubmitted(PartnerDivisionStatus.PENDING, null, PageRequest.of(0, 20));

            assertThat(page.content()).extracting(AdminPartnerDivisionSummaryResponse::divisionUuid,
                            AdminPartnerDivisionSummaryResponse::regNoSuspendedBefore)
                    .containsExactly(Tuple.tuple("pd-1", true),
                            Tuple.tuple("pd-2", false),
                            Tuple.tuple("pd-3", true));
            then(regNoSuspensionService).should(Mockito.times(1)).marksAmong(any(), any());
        }
    }

    @Test
    @DisplayName("없는 부문은 404 다")
    void missing_division() {
        BDDMockito.given(divisionRepository.findByUuid("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve("admin", "nope", SEEN))
                .extracting(AdminPartnerDivisionServiceTest::codeOf)
                .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_FOUND);
    }

    @Nested
    @DisplayName("심사 상세")
    class Detail {

        private final AuthenticatedUser viewer =
                new AuthenticatedUser("admin", UserRole.ADMIN, "jti", NOW.plusSeconds(600));

        private void givenOffice(Long licenseFileId) {
            BDDMockito.given(profileRepository.findByUserIdIn(List.of(PARTNER_ID))).willReturn(
                    List.of(BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
            BDDMockito.given(officeRepository.findAllById(any())).willReturn(List.of(
                    BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo(REG_NO)
                            .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구")
                            .tel("02-000-0000").licenseFileId(licenseFileId).build()));
        }

        private StoredFile license(LocalDateTime deletedAt) {
            StoredFile file = StoredFile.builder().id(55L).uuid("f-1").storageKey("k").contentType("application/pdf")
                    .byteSize(1L).purpose(FilePurpose.BROKER_LICENSE).uploaderId(PARTNER_ID).build();
            if (deletedAt != null) {
                ReflectionTestUtils.setField(file, "deletedAt", deletedAt);
            }
            return file;
        }

        // 등록증 URL 은 파일 열람 규칙(FileAccessPolicy)을 그대로 지나 발급된다 — 심사 화면이라고 우회하지 않는다.
        @Test
        @DisplayName("등록증 단기 URL · 정지 영향 범위 · 승인에 실을 version · 마지막 승인 시각을 함께 준다")
        void detail_carries_license_url_impact_and_version() {
            PartnerDivision d = given(PartnerDivisionStatus.PENDING, SUBMITTED);
            ReflectionTestUtils.setField(d, "lastApprovedAt", SUBMITTED.minusDays(30));
            givenOffice(55L);
            BDDMockito.given(storedFileRepository.findById(55L)).willReturn(Optional.of(license(null)));
            BDDMockito.given(fileQueryService.issueReadUrl("f-1", viewer))
                    .willReturn(new FileReadUrlResponse("https://r2.example.test/presigned", 300));
            BDDMockito.given(listingRepository.countByOfficeIdAndStatusIn(any(), any())).willReturn(3L);

            AdminPartnerDivisionDetailResponse detail = service.getDetail("pd-1", viewer);

            assertThat(detail.license().url()).isEqualTo("https://r2.example.test/presigned");
            assertThat(detail.office().licenseFileUuid()).isEqualTo("f-1");
            assertThat(detail.publicListingCount()).isEqualTo(3L);
            assertThat(detail.partner().userUuid()).isEqualTo("p-1");
            assertThat(detail.version()).isEqualTo(SEEN);
            assertThat(detail.lastApprovedAt()).isEqualTo(SUBMITTED.minusDays(30));
            assertThat(detail.sameRegNoApplications()).isEmpty();
        }

        /*
         * ★ 폐기된 파일이면 등록증 칸만 비운다. 열람 서비스를 불러 예외를 잡는 방식은 쓰지 않는다 —
         *   같은 트랜잭션에서 던진 예외가 트랜잭션을 롤백 전용으로 만들어, 잡아도 응답 직전에 500 이 난다.
         */
        @Test
        @DisplayName("폐기된 등록증이면 열람 서비스를 부르지 않고 URL 만 비운다")
        void deleted_license_leaves_url_empty() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(55L);
            BDDMockito.given(storedFileRepository.findById(55L))
                    .willReturn(Optional.of(license(LocalDateTime.of(2026, 10, 6, 0, 0))));

            AdminPartnerDivisionDetailResponse detail = service.getDetail("pd-1", viewer);

            assertThat(detail.license()).isNull();
            then(fileQueryService).shouldHaveNoInteractions();
        }

        /*
         * ★ 같은 번호로 여러 명이 신청할 수 있고 한 명만 승인한다 — 심사자가 그 판단을 하려면 다른 신청이 보여야 한다.
         *   · 번호를 잡고 있는 사무소가 먼저 나온다 — 그 줄이 있으면 이 신청의 승인은 409 다.
         *     승인된 사무소는 잡은 번호와 reg_no 가 같지만, 승인 뒤 번호를 바꾼 변경 신청은 승인될 때까지 사무소에 들어가지 않으므로
         *     잡은 번호로 찾는 것이 기준이다(아래 holder 는 두 칸이 갈린 옛 행 — 그런 행도 잡은 번호로 나와야 한다).
         *   · 대표자가 떠난 사무소(연결 없음) · 공인중개사 부문이 없는 연결은 싣지 않는다 — 승인할 신청이 아니다.
         */
        @Test
        @DisplayName("같은 등록번호의 다른 신청을 번호를 잡은 줄부터 싣고, 떠난 사무소는 뺀다")
        void detail_lists_other_applications_with_same_reg_no() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(null);
            BrokerageOffice applying = BrokerageOffice.builder().id(71L).uuid("o-71").regNo(REG_NO)
                    .name("가나공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build();
            BrokerageOffice holder = BrokerageOffice.builder().id(72L).uuid("o-72").regNo("99999-2026-00009")
                    .claimedRegNo(REG_NO)
                    .name("다라공인중개사사무소").ceoName("김철수").address("서울특별시 동작구").tel("02-000-0001").build();
            BrokerageOffice orphan = BrokerageOffice.builder().id(73L).uuid("o-73").regNo(REG_NO)
                    .name("떠난공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0002").build();
            BrokerageOffice noDivision = BrokerageOffice.builder().id(74L).uuid("o-74").regNo(REG_NO)
                    .name("연결만공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0003").build();
            BDDMockito.given(officeRepository.findOthersWithRegNo(REG_NO, OFFICE_ID))
                    .willReturn(List.of(applying, holder, orphan, noDivision));
            BDDMockito.given(profileRepository.findByOfficeIdIn(List.of(71L, 72L, 73L, 74L))).willReturn(List.of(
                    BrokerProfile.builder().userId(8L).officeId(71L).build(),
                    BrokerProfile.builder().userId(9L).officeId(72L).build(),
                    BrokerProfile.builder().userId(10L).officeId(74L).build()));
            BDDMockito.given(divisionRepository.findByUserIdInAndDivision(any(),
                    eq(PartnerDivisionType.REAL_ESTATE_BROKER))).willReturn(List.of(
                    PartnerDivision.builder().uuid("pd-8").userId(8L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.PENDING).submittedAt(SUBMITTED.minusDays(1)).build(),
                    PartnerDivision.builder().uuid("pd-9").userId(9L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.PENDING).submittedAt(SUBMITTED.plusDays(1)).build()));
            BDDMockito.given(userRepository.findAllById(any())).willReturn(List.of(
                    User.builder().id(8L).uuid("u-8").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build(),
                    User.builder().id(9L).uuid("u-9").status(UserStatus.SUSPENDED).role(UserRole.PARTNER).build()));

            AdminPartnerDivisionDetailResponse detail = service.getDetail("pd-1", viewer);

            assertThat(detail.sameRegNoApplications()).extracting(
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::kind,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::divisionUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::officeUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::claimed,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::officeName,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::accountStatus)
                    .containsExactly(
                            Tuple.tuple(SameRegNoApplicationKind.DIVISION_APPLICATION, "pd-9",
                                    "o-72", true, "다라공인중개사사무소", UserStatus.SUSPENDED),
                            Tuple.tuple(SameRegNoApplicationKind.DIVISION_APPLICATION, "pd-8",
                                    "o-71", false, "가나공인중개사사무소", UserStatus.ACTIVE));
            assertThat(detail.sameRegNoApplications().get(1).submittedAt()).isEqualTo(SUBMITTED.minusDays(1));
            assertThat(detail.sameRegNoApplications().get(1).ceoName()).isEqualTo("홍길동");
            assertThat(detail.sameRegNoApplications().get(1).status()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(detail.sameRegNoApplications()).allSatisfy(row -> assertThat(row.changeRequestUuid()).isNull());
        }

        /*
         * ★ 승인된 다른 사무소가 이 번호로 **옮기려는** 대기 중 변경 신청도 같은 번호의 다른 신청이다 — 그쪽이 먼저 승인되면 번호를
         *   잡아 가서 이 신청의 승인이 409 가 된다. 부문 신청 줄 뒤에, 먼저 낸 것부터 싣는다.
         *   · 상호 · 대표자는 그 번호와 함께 낸 값(바꿀 값)이고, 부문 · 계정 상태는 신청자의 것이다. 번호는 아직 잡지 않았다.
         *   · 이 사무소 자신의 변경 신청은 싣지 않는다. 번호를 옮기지 않는 변경 신청(이미 이 번호를 쓰는 사무소의 상호 변경)도
         *     싣지 않는다 — 그 사무소는 부문 신청 줄로 이미 나온다.
         */
        @Test
        @DisplayName("이 번호로 옮기려는 다른 사무소의 대기 중 변경 신청을 부문 신청 줄 뒤에 싣는다")
        void detail_lists_pending_office_changes_moving_to_the_number() {
            given(PartnerDivisionStatus.PENDING, SUBMITTED);
            givenOffice(null);
            BrokerageOffice holder = BrokerageOffice.builder().id(72L).uuid("o-72").regNo(REG_NO).claimedRegNo(REG_NO)
                    .name("다라공인중개사사무소").ceoName("김철수").address("서울특별시 동작구").tel("02-000-0001").build();
            BrokerageOffice mover = BrokerageOffice.builder().id(80L).uuid("o-80").regNo("99999-2026-00008")
                    .claimedRegNo("99999-2026-00008")
                    .name("마바공인중개사사무소").ceoName("이영희").address("서울특별시 서초구").tel("02-000-0004").build();
            BDDMockito.given(officeRepository.findOthersWithRegNo(REG_NO, OFFICE_ID)).willReturn(List.of(holder));
            BDDMockito.given(changeRequestRepository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(
                    REG_NO, OfficeChangeRequestStatus.PENDING)).willReturn(List.of(
                    changeRequest("cr-own", OFFICE_ID, PARTNER_ID, SUBMITTED.minusDays(3)),
                    changeRequest("cr-80", 80L, 12L, SUBMITTED.minusDays(2)),
                    changeRequest("cr-72", 72L, 9L, SUBMITTED.minusDays(1))));
            BDDMockito.given(officeRepository.findAllById(List.of(80L, 72L))).willReturn(List.of(mover, holder));
            BDDMockito.given(profileRepository.findByOfficeIdIn(List.of(72L))).willReturn(
                    List.of(BrokerProfile.builder().userId(9L).officeId(72L).build()));
            BDDMockito.given(divisionRepository.findByUserIdInAndDivision(Set.of(9L, 12L),
                    PartnerDivisionType.REAL_ESTATE_BROKER)).willReturn(List.of(
                    PartnerDivision.builder().uuid("pd-9").userId(9L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.APPROVED).submittedAt(SUBMITTED.minusDays(40)).build(),
                    PartnerDivision.builder().uuid("pd-12").userId(12L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.APPROVED).submittedAt(SUBMITTED.minusDays(50)).build()));
            BDDMockito.given(userRepository.findAllById(Set.of(9L, 12L))).willReturn(List.of(
                    User.builder().id(9L).uuid("u-9").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build(),
                    User.builder().id(12L).uuid("u-12").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build()));

            AdminPartnerDivisionDetailResponse detail = service.getDetail("pd-1", viewer);

            assertThat(detail.sameRegNoApplications()).extracting(
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::kind,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::divisionUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::changeRequestUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::officeUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::claimed)
                    .containsExactly(
                            Tuple.tuple(SameRegNoApplicationKind.DIVISION_APPLICATION, "pd-9",
                                    null, "o-72", true),
                            Tuple.tuple(SameRegNoApplicationKind.OFFICE_CHANGE_REQUEST, "pd-12",
                                    "cr-80", "o-80", false));
            AdminPartnerDivisionDetailResponse.SameRegNoApplication change = detail.sameRegNoApplications().get(1);
            assertThat(change.officeName()).as("바꿀 상호").isEqualTo("바꿀상호-cr-80");
            assertThat(change.ceoName()).as("바꿀 대표자").isEqualTo("박대표");
            assertThat(change.submittedAt()).as("변경 신청을 낸 시각").isEqualTo(SUBMITTED.minusDays(2));
            assertThat(change.status()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(change.accountStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        private BrokerageOfficeChangeRequest changeRequest(String uuid, long officeId, long requestedBy,
                                                           LocalDateTime submittedAt) {
            return BrokerageOfficeChangeRequest.builder().uuid(uuid).officeId(officeId).requestedBy(requestedBy)
                    .regNo(REG_NO).name("바꿀상호-" + uuid).ceoName("박대표").address("서울특별시 강남구")
                    .licenseFileId(66L).status(OfficeChangeRequestStatus.PENDING).submittedAt(submittedAt).build();
        }
    }
}
