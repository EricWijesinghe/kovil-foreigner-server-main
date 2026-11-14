package com.kovil.domain.partner.service;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.file.service.FileQueryService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.partner.dto.response.AdminOfficeChangeRequestDetailResponse;
import com.kovil.domain.partner.dto.response.AdminOfficeChangeRequestSummaryResponse;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionDetailResponse;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.assertj.core.groups.Tuple;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/*
 * 사무소 정보 변경 심사. 결과 격자와 함께 고정하는 것:
 *   · 승인 = 바꿀 값 적용 + 번호 이동(새 번호 잡기 · 옛 번호 놓기) + 옛 등록증 폐기 + 알림. 부문이 승인 상태일 때만.
 *   · 반려 = 사무소 그대로(옛 번호 유지) + 변경 신청의 등록증만 폐기(사무소가 쓰는 파일이면 둔다) + 사유 알림.
 *   · 관리자가 본 판(version)이 아니면 둘 다 하지 않는다. 판정이 성립하지 않으면 아무것도 바꾸지도 알리지도 않는다.
 *   · 잠금 순서 — 신청자의 users 행을 먼저, 사무소 행을 잠근 뒤에 연결 · 부문 상태를 잠금 읽기로 본다(발급 · 정지와 줄을 선다).
 */
@ExtendWith(MockitoExtension.class)
class AdminOfficeChangeRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T01:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
    private static final long ADMIN_ID = 1L;
    private static final long PARTNER_ID = 7L;
    private static final long OFFICE_ID = 70L;
    private static final long SEEN = 3L;
    private static final long OFFICE_LICENSE_ID = 55L;
    private static final long NEW_LICENSE_ID = 66L;
    private static final String OLD_REG_NO = "99999-2026-00002";
    private static final String NEW_REG_NO = "99999-2026-00009";
    private static final LocalDateTime SUBMITTED = LocalDateTime.of(2026, 10, 6, 9, 0);

    @Mock BrokerageOfficeChangeRequestRepository changeRequestRepository;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock BrokerProfileRepository profileRepository;
    @Mock PartnerDivisionRepository divisionRepository;
    @Mock UserRepository userRepository;
    @Mock UserQueryService userQueryService;
    @Mock StoredFileRepository storedFileRepository;
    @Mock FileQueryService fileQueryService;
    @Mock FileDeletionService fileDeletionService;
    @Mock RegNoSuspensionService regNoSuspensionService;
    @Mock NotificationPublisher notificationPublisher;

    private AdminOfficeChangeRequestService service;
    private BrokerageOffice office;

    @BeforeEach
    void setUp() {
        service = new AdminOfficeChangeRequestService(changeRequestRepository, officeRepository, profileRepository,
                divisionRepository, userRepository, userQueryService, storedFileRepository, fileQueryService,
                fileDeletionService, regNoSuspensionService,
                new SameRegNoApplicationFinder(officeRepository, profileRepository, divisionRepository,
                        changeRequestRepository, userRepository),
                notificationPublisher, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
        lenient().when(userQueryService.getActiveUser("admin")).thenReturn(
                User.builder().id(ADMIN_ID).uuid("admin").role(UserRole.ADMIN).status(UserStatus.ACTIVE).build());
        lenient().when(userRepository.findById(PARTNER_ID)).thenReturn(Optional.of(partner()));
        office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo(OLD_REG_NO).claimedRegNo(OLD_REG_NO)
                .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000")
                .licenseFileId(OFFICE_LICENSE_ID).build();
        lenient().when(officeRepository.findById(OFFICE_ID)).thenAnswer(inv -> Optional.of(office));
        // 정지 이력 표시 — 기본은 "이력 없음"이다.
        lenient().when(regNoSuspensionService.marksAmong(any(), any()))
                .thenReturn(new RegNoSuspensionService.SuspensionMarks(Set.of(), Set.of()));
    }

    private static User partner() {
        return User.builder().id(PARTNER_ID).uuid("p-1").name("홍길동").email("partner@example.test")
                .role(UserRole.PARTNER).status(UserStatus.ACTIVE).build();
    }

    private BrokerageOfficeChangeRequest givenRequest(OfficeChangeRequestStatus status, long licenseFileId) {
        BrokerageOfficeChangeRequest r = BrokerageOfficeChangeRequest.builder().id(500L).uuid("cr-1")
                .officeId(OFFICE_ID).requestedBy(PARTNER_ID).regNo(NEW_REG_NO).name("새상호공인중개사사무소")
                .ceoName("김철수").address("서울특별시 동작구").bizNo("000-00-00000").licenseFileId(licenseFileId)
                .status(status).submittedAt(SUBMITTED).version(SEEN).build();
        given(changeRequestRepository.findByUuid("cr-1")).willReturn(Optional.of(r));
        return r;
    }

    private BrokerageOfficeChangeRequest givenPending() {
        return givenRequest(OfficeChangeRequestStatus.PENDING, NEW_LICENSE_ID);
    }

    // 신청자가 그 사무소의 대표자이고, 그 사람의 공인중개사 부문이 지금 이 상태다.
    private void givenRepresentativeWithDivision(PartnerDivisionStatus status) {
        lenient().when(profileRepository.findOfficeIdForUpdate(PARTNER_ID)).thenReturn(Optional.of(OFFICE_ID));
        lenient().when(divisionRepository.findStatusForUpdate(PARTNER_ID, "REAL_ESTATE_BROKER"))
                .thenReturn(Optional.of(status.name()));
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

    private void assertOfficeUntouched() {
        assertThat(office.getRegNo()).isEqualTo(OLD_REG_NO);
        assertThat(office.getClaimedRegNo()).isEqualTo(OLD_REG_NO);
        assertThat(office.getName()).isEqualTo("테스트공인중개사사무소");
        assertThat(office.getCeoName()).isEqualTo("홍길동");
        assertThat(office.getAddress()).isEqualTo("서울특별시 관악구");
        assertThat(office.getBizNo()).isNull();
        assertThat(office.getLicenseFileId()).isEqualTo(OFFICE_LICENSE_ID);
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        /*
         * ★ 승인 = 바꿀 값 한 벌이 사무소에 들어가고, 번호가 바뀌면 새 번호를 잡는다(옛 번호는 같은 칸을 덮으며 놓인다).
         *   대표번호는 변경 신청 대상이 아니라 그대로다. 옛 등록증은 폐기 표시하고 파트너에게 알린다.
         */
        @Test
        @DisplayName("바꿀 값을 사무소에 적용하고 번호를 옮기고 옛 등록증을 폐기하고 알린다")
        void approve_applies_change_moves_claim_and_notifies() {
            BrokerageOfficeChangeRequest request = givenPending();
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);
            willAnswer(inv -> {
                assertThat(office.getClaimedRegNo()).as("flush 하는 순간 새 번호를 잡았다").isEqualTo(NEW_REG_NO);
                assertThat(request.getStatus()).as("flush 하는 순간 신청은 승인").isEqualTo(OfficeChangeRequestStatus.APPROVED);
                return null;
            }).given(officeRepository).flush();

            AdminOfficeChangeRequestSummaryResponse response = service.approve("admin", "cr-1", SEEN);

            assertThat(office.getRegNo()).isEqualTo(NEW_REG_NO);
            assertThat(office.getClaimedRegNo()).isEqualTo(NEW_REG_NO);
            assertThat(office.getName()).isEqualTo("새상호공인중개사사무소");
            assertThat(office.getCeoName()).isEqualTo("김철수");
            assertThat(office.getAddress()).isEqualTo("서울특별시 동작구");
            assertThat(office.getBizNo()).isEqualTo("000-00-00000");
            assertThat(office.getLicenseFileId()).isEqualTo(NEW_LICENSE_ID);
            assertThat(office.getTel()).isEqualTo("02-000-0000");

            assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.APPROVED);
            assertThat(request.getReviewedBy()).isEqualTo(ADMIN_ID);
            assertThat(request.getReviewedAt()).isEqualTo(NOW_LOCAL);

            then(fileDeletionService).should().discardByIds(List.of(OFFICE_LICENSE_ID));
            then(notificationPublisher).should().publish(PARTNER_ID, NotiType.OFFICE_CHANGE_APPROVED,
                    "OFFICE_CHANGE_APPROVED:office-change:cr-1:" + NOW_LOCAL, Map.of(), "/partner/me");
            then(officeRepository).should().existsByClaimedRegNoAndIdNot(NEW_REG_NO, OFFICE_ID);
            assertThat(response.status()).isEqualTo(OfficeChangeRequestStatus.APPROVED);
            assertThat(response.office().regNo()).isEqualTo(NEW_REG_NO);
        }

        /*
         * ★★ 잠금 순서 — 신청자의 users 행 → 사무소 행 → (대표자 연결 · 부문 상태를 잠금 읽기로) → 사무소 고치기.
         *    users 를 먼저 잡지 않으면 알림 INSERT 의 FK 검사가 사무소 · 부문을 쥔 채 users 를 기다리고, users → 부문 순으로
         *    잡는 토큰 발급과 교착한다(MySQL 재현). 정지도 사무소를 먼저 잠그므로 같은 순간의 정지와 줄을 서고, 정지가 먼저
         *    커밋됐으면 이 승인은 정지를 본다(정지된 사무소의 번호가 옮겨지지 않는다).
         */
        @Test
        @DisplayName("신청자 users 행 → 사무소 행을 잠근 뒤에 연결 · 부문 상태를 잠금 읽기로 보고, 그 뒤에 사무소를 고친다")
        void locks_office_then_reads_division_then_applies() {
            givenPending();
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);
            willAnswer(inv -> {
                assertThat(office.getRegNo()).as("부문을 읽는 순간의 사무소").isEqualTo(OLD_REG_NO);
                return Optional.of("APPROVED");
            }).given(divisionRepository).findStatusForUpdate(PARTNER_ID, "REAL_ESTATE_BROKER");

            service.approve("admin", "cr-1", SEEN);

            InOrder order = Mockito.inOrder(userRepository, officeRepository, profileRepository, divisionRepository,
                    notificationPublisher);
            order.verify(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);
            order.verify(officeRepository).lockById(OFFICE_ID);
            order.verify(profileRepository).findOfficeIdForUpdate(PARTNER_ID);
            order.verify(divisionRepository).findStatusForUpdate(PARTNER_ID, "REAL_ESTATE_BROKER");
            order.verify(officeRepository).flush();
            order.verify(notificationPublisher).publish(anyLong(), any(), anyString(), anyMap(), any());
        }

        // 지금 등록증을 그대로 내고 상호만 바꾼 신청 — 같은 파일이라 버리면 사무소의 서류가 사라진다.
        @Test
        @DisplayName("같은 등록증으로 낸 신청이면 아무것도 폐기하지 않는다")
        void same_license_is_kept() {
            givenRequest(OfficeChangeRequestStatus.PENDING, OFFICE_LICENSE_ID);
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);

            service.approve("admin", "cr-1", SEEN);

            assertThat(office.getLicenseFileId()).isEqualTo(OFFICE_LICENSE_ID);
            then(fileDeletionService).shouldHaveNoInteractions();
        }

        // 관리자가 상세를 본 뒤 신청자가 덮어썼으면 본 적 없는 값이 승인된다.
        @Test
        @DisplayName("본 판이 아니면 OFFICE_CHANGE_REQUEST_CHANGED 이고 사무소 · 잠금 · 알림 모두 그대로다")
        void stale_version_is_rejected() {
            BrokerageOfficeChangeRequest request = givenPending();

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN - 1))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_CHANGED);
            assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
            assertOfficeUntouched();
            then(officeRepository).should(never()).lockById(any());
            then(notificationPublisher).shouldHaveNoInteractions();
            then(fileDeletionService).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OfficeChangeRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("대기 건이 아니면 OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE 이다")
        void decided_request_is_not_reviewable(OfficeChangeRequestStatus status) {
            givenRequest(status, NEW_LICENSE_ID);

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE);
            assertOfficeUntouched();
            then(notificationPublisher).shouldHaveNoInteractions();
        }

        /*
         * ★ 부문이 승인 상태가 아니면 승인하지 않는다 — 정지된 사무소의 번호를 옮기면 정지가 다른 번호로 샌다.
         *   반려는 막지 않는다(아래 반려 쪽). 복구한 뒤 다시 승인하면 된다.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("공인중개사 부문이 승인 상태가 아니면 409 이고 사무소 · 신청 모두 그대로다")
        void division_must_be_approved(PartnerDivisionStatus status) {
            BrokerageOfficeChangeRequest request = givenPending();
            givenRepresentativeWithDivision(status);

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_DIVISION_NOT_APPROVED);
            assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
            assertOfficeUntouched();
            then(officeRepository).should(never()).flush();
            then(notificationPublisher).shouldHaveNoInteractions();
        }

        /*
         * 신청자가 더는 그 사무소의 대표자가 아니다(탈퇴로 떠났다) — 승인할 사무소가 아니다. 부문을 묻지도 않는다.
         * 연결은 사무소를 잠근 뒤에 잠금 읽기로 본다 — 일반 읽기는 같은 순간 커밋된 탈퇴(연결 삭제)를 못 본다.
         */
        @Test
        @DisplayName("사무소를 잠근 뒤 본 대표자 연결이 이 사무소가 아니면 409 다")
        void requester_must_still_represent_the_office() {
            givenPending();
            given(profileRepository.findOfficeIdForUpdate(PARTNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_DIVISION_NOT_APPROVED);
            then(divisionRepository).should(never()).findStatusForUpdate(anyLong(), anyString());
            assertOfficeUntouched();
        }

        // 바꿀 번호를 다른 사무소가 승인받아 쓰고 있다 — 한 번호는 한 사무소만 잡는다.
        @Test
        @DisplayName("바꿀 번호를 다른 사무소가 잡고 있으면 OFFICE_REG_NO_DUPLICATED 이고 아무것도 바꾸지 않는다")
        void claimed_number_blocks_approval() {
            BrokerageOfficeChangeRequest request = givenPending();
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);
            given(officeRepository.existsByClaimedRegNoAndIdNot(NEW_REG_NO, OFFICE_ID)).willReturn(true);

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .isInstanceOf(ListingException.class)
                    .extracting(AdminOfficeChangeRequestServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
            assertOfficeUntouched();
            then(officeRepository).should(never()).flush();
            then(notificationPublisher).shouldHaveNoInteractions();
        }

        // 같은 순간 그 번호를 잡는 다른 승인과 겹치면 둘 다 사전 검사를 지난다 — 유니크가 하나만 이기게 하고 진 쪽은 같은 409.
        @Test
        @DisplayName("번호 경합의 유니크 위반(uq_bo_claimed_reg_no)은 OFFICE_REG_NO_DUPLICATED 로 번역하고 알리지 않는다")
        void claim_race_is_translated() {
            givenPending();
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);
            willThrow(violationOf("brokerage_offices.uq_bo_claimed_reg_no")).given(officeRepository).flush();

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .isInstanceOf(ListingException.class)
                    .extracting(AdminOfficeChangeRequestServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            then(notificationPublisher).shouldHaveNoInteractions();
            then(fileDeletionService).shouldHaveNoInteractions();
        }

        // 다른 제약의 위반까지 409 로 덮으면 진짜 결함이 "번호 중복"으로 숨는다.
        @Test
        @DisplayName("다른 제약의 위반은 번역하지 않고 그대로 올린다")
        void other_violation_is_not_translated() {
            givenPending();
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);
            willThrow(violationOf("fk_bo_license_file")).given(officeRepository).flush();

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /*
         * 같은 순간 대표번호 변경이 사무소 행을 바꿨으면 flush 가 사무소의 낙관적 락 충돌로 진다 — 그대로 올라가 409 CONFLICT 가
         * 되고(바뀐 대표번호를 옛 값으로 덮지 않는다) 알림 · 폐기도 하지 않는다. 번호 중복으로 바꾸면 안 된다.
         */
        @Test
        @DisplayName("사무소 행의 낙관적 락 충돌은 번역하지 않고 올리며 알리지도 버리지도 않는다")
        void office_version_conflict_is_not_swallowed() {
            givenPending();
            givenRepresentativeWithDivision(PartnerDivisionStatus.APPROVED);
            willThrow(new ObjectOptimisticLockingFailureException(BrokerageOffice.class, OFFICE_ID))
                    .given(officeRepository).flush();

            assertThatThrownBy(() -> service.approve("admin", "cr-1", SEEN))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            then(notificationPublisher).shouldHaveNoInteractions();
            then(fileDeletionService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("없는 변경 신청은 404 다")
        void missing_request() {
            given(changeRequestRepository.findByUuid("nope")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve("admin", "nope", SEEN))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("반려")
    class Reject {

        /*
         * ★ 반려 = 사무소 그대로 — **옛 번호도 그대로 잡는다**(PO 확정 2026-10-07). 그 사무소는 옛 번호로 승인받아 영업 중이다.
         *   변경 신청의 새 등록증만 폐기하고, 사유를 다듬어 알린다.
         */
        @Test
        @DisplayName("사무소 · 잡은 번호는 그대로, 새 등록증만 폐기하고 다듬은 사유로 알린다")
        void reject_keeps_office_and_discards_new_license() {
            BrokerageOfficeChangeRequest request = givenPending();

            AdminOfficeChangeRequestSummaryResponse response =
                    service.reject("admin", "cr-1", "  새 등록증의 상호가 다릅니다  ", SEEN);

            assertOfficeUntouched();
            assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.REJECTED);
            assertThat(request.getRejectReason()).isEqualTo("새 등록증의 상호가 다릅니다");
            assertThat(request.getReviewedBy()).isEqualTo(ADMIN_ID);
            then(fileDeletionService).should().discardByIds(List.of(NEW_LICENSE_ID));
            then(notificationPublisher).should().publish(PARTNER_ID, NotiType.OFFICE_CHANGE_REJECTED,
                    "OFFICE_CHANGE_REJECTED:office-change:cr-1:" + NOW_LOCAL,
                    Map.of("0", "새 등록증의 상호가 다릅니다"), "/partner/me");
            then(officeRepository).should(never()).flush();
            assertThat(response.status()).isEqualTo(OfficeChangeRequestStatus.REJECTED);
        }

        // 지금 등록증을 그대로 낸 신청이면 그 파일이 사무소의 서류다 — 버리지 않는다.
        @Test
        @DisplayName("사무소가 쓰는 등록증이면 폐기하지 않는다")
        void office_license_is_kept() {
            givenRequest(OfficeChangeRequestStatus.PENDING, OFFICE_LICENSE_ID);

            service.reject("admin", "cr-1", "상호가 다릅니다", SEEN);

            then(fileDeletionService).shouldHaveNoInteractions();
            assertThat(office.getLicenseFileId()).isEqualTo(OFFICE_LICENSE_ID);
        }

        /*
         * ★ 반려도 신청자의 users 행부터 잡는다 — 변경 신청을 고친 뒤 알림 INSERT 의 FK 검사로 users 를 처음 기다리면,
         *   users → 사무소 → 변경 신청 순으로 잡는 제출 · 탈퇴와 순서가 어긋난다. 상태는 묻지 않는다(닫는 방향이다).
         */
        @Test
        @DisplayName("반려는 신청자의 users 행을 먼저 잡고, 그 뒤에 등록증을 버리고 알린다")
        void reject_locks_requester_first() {
            givenPending();
            given(userRepository.findAuthSnapshotForUpdate(PARTNER_ID)).willReturn(Optional.empty());

            service.reject("admin", "cr-1", "사유", SEEN);

            InOrder order = Mockito.inOrder(userRepository, fileDeletionService, notificationPublisher);
            order.verify(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);
            order.verify(fileDeletionService).discardByIds(List.of(NEW_LICENSE_ID));
            order.verify(notificationPublisher).publish(anyLong(), any(), anyString(), anyMap(), any());
        }

        // 닫는 방향이라 부문 상태와 관계없이 된다 — 정지된 사무소의 대기 건도 반려로 정리할 수 있어야 한다.
        @Test
        @DisplayName("반려는 부문 상태를 보지 않는다")
        void reject_does_not_check_division() {
            givenPending();

            service.reject("admin", "cr-1", "사유", SEEN);

            then(divisionRepository).shouldHaveNoInteractions();
            then(officeRepository).should(never()).lockById(any());
        }

        @Test
        @DisplayName("본 판이 아니면 반려하지 않고 버리지도 알리지도 않는다")
        void stale_version_is_rejected() {
            BrokerageOfficeChangeRequest request = givenPending();

            assertThatThrownBy(() -> service.reject("admin", "cr-1", "사유", SEEN + 1))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_CHANGED);
            assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
            assertThat(request.getRejectReason()).isNull();
            then(fileDeletionService).shouldHaveNoInteractions();
            then(notificationPublisher).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OfficeChangeRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("대기 건이 아니면 반려할 수 없다")
        void decided_request_cannot_be_rejected(OfficeChangeRequestStatus status) {
            givenRequest(status, NEW_LICENSE_ID);

            assertThatThrownBy(() -> service.reject("admin", "cr-1", "사유", SEEN))
                    .extracting(AdminOfficeChangeRequestServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE);
            then(fileDeletionService).shouldHaveNoInteractions();
            then(notificationPublisher).should(never()).publish(anyLong(), any(), anyString(), anyMap(), any());
        }
    }

    @Nested
    @DisplayName("상세 · 대기열")
    class Read {

        private final AuthenticatedUser viewer =
                new AuthenticatedUser("admin", UserRole.ADMIN, "jti", NOW.plusSeconds(600));

        private StoredFile file(long id, String uuid, LocalDateTime deletedAt) {
            StoredFile f = StoredFile.builder().id(id).uuid(uuid).storageKey("k").contentType("application/pdf")
                    .byteSize(1L).purpose(FilePurpose.BROKER_LICENSE).uploaderId(PARTNER_ID).build();
            if (deletedAt != null) {
                ReflectionTestUtils.setField(f, "deletedAt", deletedAt);
            }
            return f;
        }

        /*
         * ★ 지금 값과 바꿀 값을 나란히 — 심사자는 그 차이를 새 등록증과 맞춰 본다. 정지 이력은 **바꿀 번호 · 지금 번호 · 이 사무소**
         *   모두로 찾는다(승인되면 바꿀 번호를 이 사무소가 쓰고, 지금 번호와 이 사무소 자신의 정지는 번호를 바꿔도 따라간다).
         *   승인 가능 여부를 가르는 부문 상태도 함께 준다.
         */
        @Test
        @DisplayName("지금 값 · 바꿀 값 · 새 등록증 URL · 부문 상태 · 바꿀 번호 · 지금 번호 · 이 사무소의 정지 이력 · version 을 준다")
        void detail_shows_current_and_requested_side_by_side() {
            givenPending();
            given(divisionRepository.findByUserIdAndDivision(PARTNER_ID, PartnerDivisionType.REAL_ESTATE_BROKER))
                    .willReturn(Optional.of(PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                            .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                            .build()));
            given(storedFileRepository.findById(NEW_LICENSE_ID)).willReturn(Optional.of(file(NEW_LICENSE_ID, "f-new", null)));
            given(storedFileRepository.findById(OFFICE_LICENSE_ID))
                    .willReturn(Optional.of(file(OFFICE_LICENSE_ID, "f-old", null)));
            given(fileQueryService.issueReadUrl("f-new", viewer))
                    .willReturn(new FileReadUrlResponse("https://r2.example.test/presigned", 300));
            RegNoSuspensionView past = new RegNoSuspensionView(SUBMITTED.minusDays(100), NEW_REG_NO, "o-old",
                    "떠난공인중개사사무소", "허위 매물");
            RegNoSuspensionView own = new RegNoSuspensionView(SUBMITTED.minusDays(300), "99999-2026-00001", "o-1",
                    "테스트공인중개사사무소", "옛 번호 시절 정지");
            // 바꿀 번호 · 지금 번호(신청 = 잡은 번호라 한 번) · 이 사무소로 묻는다.
            given(regNoSuspensionService.historyOf(List.of(NEW_REG_NO, OLD_REG_NO), OFFICE_ID))
                    .willReturn(List.of(past, own));

            AdminOfficeChangeRequestDetailResponse detail = service.getDetail("cr-1", viewer);

            assertThat(detail.current().regNo()).isEqualTo(OLD_REG_NO);
            assertThat(detail.current().name()).isEqualTo("테스트공인중개사사무소");
            assertThat(detail.current().licenseFileUuid()).isEqualTo("f-old");
            assertThat(detail.requested().regNo()).isEqualTo(NEW_REG_NO);
            assertThat(detail.requested().name()).isEqualTo("새상호공인중개사사무소");
            assertThat(detail.requested().ceoName()).isEqualTo("김철수");
            assertThat(detail.requested().licenseFileUuid()).isEqualTo("f-new");
            assertThat(detail.license().url()).isEqualTo("https://r2.example.test/presigned");
            assertThat(detail.divisionStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(detail.regNoSuspensionHistory()).containsExactly(past, own);
            assertThat(detail.sameRegNoApplications()).isEmpty();
            assertThat(detail.version()).isEqualTo(SEEN);
            assertThat(detail.partner().userUuid()).isEqualTo("p-1");
        }

        /*
         * ★ 변경 심사에도 "같은 번호의 다른 신청"이 있어야 한다 — 바꿀 번호를 이미 잡은 사무소가 있으면 승인이 409 이고, 그 번호로 낸
         *   첫 심사 신청 · 다른 사무소의 변경 신청은 누구에게 번호를 줄지의 판단 근거다. **바꿀 번호**로 찾고 이 사무소 자신은 뺀다
         *   (이 사무소의 지금 번호로 찾으면 자기 번호의 경쟁자가 보여 판단을 흐린다). 부문 심사 상세와 같은 모양 · 같은 순서다.
         */
        @Test
        @DisplayName("바꿀 번호의 다른 신청(번호를 잡은 사무소 · 첫 심사 신청 · 다른 변경 신청)을 싣고 이 사무소는 뺀다")
        void detail_lists_other_applications_for_the_requested_number() {
            givenPending();
            BrokerageOffice applying = BrokerageOffice.builder().id(71L).uuid("o-71").regNo(NEW_REG_NO)
                    .name("가나공인중개사사무소").ceoName("최신청").address("서울특별시 관악구").tel("02-000-0001").build();
            BrokerageOffice holder = BrokerageOffice.builder().id(72L).uuid("o-72").regNo(NEW_REG_NO)
                    .claimedRegNo(NEW_REG_NO).name("다라공인중개사사무소").ceoName("김보유").address("서울특별시 동작구")
                    .tel("02-000-0002").build();
            BrokerageOffice mover = BrokerageOffice.builder().id(80L).uuid("o-80").regNo("99999-2026-00008")
                    .claimedRegNo("99999-2026-00008").name("마바공인중개사사무소").ceoName("이영희")
                    .address("서울특별시 서초구").tel("02-000-0003").build();
            given(officeRepository.findOthersWithRegNo(NEW_REG_NO, OFFICE_ID)).willReturn(List.of(applying, holder));
            given(changeRequestRepository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(NEW_REG_NO,
                    OfficeChangeRequestStatus.PENDING)).willReturn(List.of(
                    BrokerageOfficeChangeRequest.builder().uuid("cr-1").officeId(OFFICE_ID).requestedBy(PARTNER_ID)
                            .regNo(NEW_REG_NO).name("새상호공인중개사사무소").ceoName("김철수").address("서울특별시 동작구")
                            .licenseFileId(NEW_LICENSE_ID).submittedAt(SUBMITTED).build(),
                    BrokerageOfficeChangeRequest.builder().uuid("cr-80").officeId(80L).requestedBy(12L)
                            .regNo(NEW_REG_NO).name("마바새상호공인중개사사무소").ceoName("이영희").address("서울특별시 서초구")
                            .licenseFileId(67L).submittedAt(SUBMITTED.plusHours(2)).build()));
            given(officeRepository.findAllById(List.of(80L))).willReturn(List.of(mover));
            given(profileRepository.findByOfficeIdIn(List.of(71L, 72L))).willReturn(List.of(
                    BrokerProfile.builder().userId(8L).officeId(71L).build(),
                    BrokerProfile.builder().userId(9L).officeId(72L).build()));
            given(divisionRepository.findByUserIdInAndDivision(Set.of(8L, 9L, 12L),
                    PartnerDivisionType.REAL_ESTATE_BROKER)).willReturn(List.of(
                    PartnerDivision.builder().uuid("pd-8").userId(8L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.PENDING).submittedAt(SUBMITTED.minusDays(1)).build(),
                    PartnerDivision.builder().uuid("pd-9").userId(9L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.SUSPENDED).submittedAt(SUBMITTED.minusDays(90)).build(),
                    PartnerDivision.builder().uuid("pd-12").userId(12L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                            .status(PartnerDivisionStatus.APPROVED).submittedAt(SUBMITTED.minusDays(60)).build()));
            given(userRepository.findAllById(Set.of(8L, 9L, 12L))).willReturn(List.of(
                    User.builder().id(8L).uuid("u-8").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build(),
                    User.builder().id(9L).uuid("u-9").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build(),
                    User.builder().id(12L).uuid("u-12").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build()));

            AdminOfficeChangeRequestDetailResponse detail = service.getDetail("cr-1", viewer);

            assertThat(detail.sameRegNoApplications()).extracting(
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::kind,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::divisionUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::changeRequestUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::officeUuid,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::status,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::officeName,
                            AdminPartnerDivisionDetailResponse.SameRegNoApplication::claimed)
                    .containsExactly(
                            Tuple.tuple(SameRegNoApplicationKind.DIVISION_APPLICATION, "pd-9",
                                    null, "o-72", PartnerDivisionStatus.SUSPENDED, "다라공인중개사사무소", true),
                            Tuple.tuple(SameRegNoApplicationKind.DIVISION_APPLICATION, "pd-8",
                                    null, "o-71", PartnerDivisionStatus.PENDING, "가나공인중개사사무소", false),
                            Tuple.tuple(SameRegNoApplicationKind.OFFICE_CHANGE_REQUEST, "pd-12",
                                    "cr-80", "o-80", PartnerDivisionStatus.APPROVED, "마바새상호공인중개사사무소", false));
            assertThat(detail.sameRegNoApplications().get(2).submittedAt()).isEqualTo(SUBMITTED.plusHours(2));
            then(officeRepository).should(never()).findOthersWithRegNo(OLD_REG_NO, OFFICE_ID);
        }

        // 반려 · 탈퇴로 닫힌 건은 새 등록증이 폐기됐다 — 칸만 비우고 열람 서비스는 부르지 않는다(롤백 전용 함정).
        @Test
        @DisplayName("새 등록증이 폐기됐으면 열람 서비스를 부르지 않고 URL 만 비운다")
        void deleted_license_leaves_url_empty() {
            givenRequest(OfficeChangeRequestStatus.REJECTED, NEW_LICENSE_ID);
            given(storedFileRepository.findById(NEW_LICENSE_ID))
                    .willReturn(Optional.of(file(NEW_LICENSE_ID, "f-new", NOW_LOCAL)));

            AdminOfficeChangeRequestDetailResponse detail = service.getDetail("cr-1", viewer);

            assertThat(detail.license()).isNull();
            then(fileQueryService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("대기열은 행마다 바꿀 번호의 정지 이력 여부와 번호 변경 여부를 표시한다")
        void queue_flags_rows() {
            BrokerageOfficeChangeRequest numberChange = BrokerageOfficeChangeRequest.builder().uuid("cr-1")
                    .officeId(OFFICE_ID).requestedBy(PARTNER_ID).regNo(NEW_REG_NO).name("새상호공인중개사사무소")
                    .ceoName("홍길동").address("서울특별시 관악구").licenseFileId(NEW_LICENSE_ID)
                    .submittedAt(SUBMITTED).version(SEEN).build();
            BrokerageOfficeChangeRequest nameOnly = BrokerageOfficeChangeRequest.builder().uuid("cr-2")
                    .officeId(OFFICE_ID).requestedBy(PARTNER_ID).regNo(OLD_REG_NO).name("다른상호공인중개사사무소")
                    .ceoName("홍길동").address("서울특별시 관악구").licenseFileId(NEW_LICENSE_ID)
                    .submittedAt(SUBMITTED.plusHours(1)).version(0L).build();
            given(changeRequestRepository.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20)))
                    .willReturn(new PageImpl<>(List.of(numberChange, nameOnly), PageRequest.of(0, 20), 2));
            given(userRepository.findAllById(any())).willReturn(List.of(partner()));
            given(officeRepository.findAllById(any())).willReturn(List.of(office));
            given(regNoSuspensionService.marksAmong(any(), any()))
                    .willReturn(new RegNoSuspensionService.SuspensionMarks(Set.of(NEW_REG_NO), Set.of()));

            var page = service.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20));

            assertThat(page.content()).extracting(AdminOfficeChangeRequestSummaryResponse::requestUuid,
                            AdminOfficeChangeRequestSummaryResponse::regNoChanged,
                            AdminOfficeChangeRequestSummaryResponse::regNoSuspendedBefore)
                    .containsExactly(Tuple.tuple("cr-1", true, true),
                            Tuple.tuple("cr-2", false, false));
            assertThat(page.content().get(0).office().regNo()).isEqualTo(OLD_REG_NO);
            assertThat(page.content().get(0).partner().email()).isEqualTo("partner@example.test");
        }

        /*
         * ★ 목록의 경고도 바꿀 번호만이 아니라 **지금 번호 · 이 사무소**로 계산한다. 정지됐던 사무소가 번호를 바꾼 뒤 낸 신청은
         *   바꿀 번호에도 지금 번호에도 이력이 없지만, 그 사무소 자신의 정지가 있다 — 번호로만 보면 경고가 사라진다.
         *   한 페이지의 번호 · 사무소는 한 번에 묻는다(행마다 묻지 않는다).
         */
        @Test
        @DisplayName("대기열 경고는 지금 번호 · 이 사무소의 정지로도 켜지고, 한 페이지를 한 번에 묻는다")
        void queue_flags_by_current_number_and_own_office() {
            BrokerageOfficeChangeRequest change = BrokerageOfficeChangeRequest.builder().uuid("cr-1")
                    .officeId(OFFICE_ID).requestedBy(PARTNER_ID).regNo(NEW_REG_NO).name("새상호공인중개사사무소")
                    .ceoName("홍길동").address("서울특별시 관악구").licenseFileId(NEW_LICENSE_ID)
                    .submittedAt(SUBMITTED).version(SEEN).build();
            given(changeRequestRepository.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20)))
                    .willReturn(new PageImpl<>(List.of(change), PageRequest.of(0, 20), 1));
            given(userRepository.findAllById(any())).willReturn(List.of(partner()));
            given(officeRepository.findAllById(any())).willReturn(List.of(office));
            given(regNoSuspensionService.marksAmong(any(), any()))
                    .willReturn(new RegNoSuspensionService.SuspensionMarks(Set.of(OLD_REG_NO), Set.of()))
                    .willReturn(new RegNoSuspensionService.SuspensionMarks(Set.of(), Set.of(OFFICE_ID)))
                    .willReturn(new RegNoSuspensionService.SuspensionMarks(Set.of(), Set.of()));

            assertThat(service.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20)).content())
                    .as("지금 번호가 정지된 적이 있다").singleElement()
                    .satisfies(row -> assertThat(row.regNoSuspendedBefore()).isTrue());
            assertThat(service.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20)).content())
                    .as("이 사무소 자신이 정지된 적이 있다(번호는 깨끗하다)").singleElement()
                    .satisfies(row -> assertThat(row.regNoSuspendedBefore()).isTrue());
            assertThat(service.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20)).content())
                    .as("어디에도 이력이 없다").singleElement()
                    .satisfies(row -> assertThat(row.regNoSuspendedBefore()).isFalse());

            ArgumentCaptor<Collection<String>> numbers =
                    ArgumentCaptor.forClass(Collection.class);
            ArgumentCaptor<Collection<Long>> offices =
                    ArgumentCaptor.forClass(Collection.class);
            then(regNoSuspensionService).should(Mockito.times(3)).marksAmong(numbers.capture(), offices.capture());
            assertThat(numbers.getValue()).containsExactlyInAnyOrder(NEW_REG_NO, OLD_REG_NO);
            assertThat(offices.getValue()).containsExactly(OFFICE_ID);
        }
    }
}
