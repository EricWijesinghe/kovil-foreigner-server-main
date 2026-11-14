package com.kovil.domain.rentalrequest.service;

import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.rentalrequest.config.RentalRequestProperties;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/*
 * 파트너(공인중개사 사무소)의 임대 요청 처리 — 결과 격자.
 *   · 잡기: 성공 / 다른 사무소가 먼저(409) / @Version 경합(409) / 상한(409) / 낡은 토큰(부문 정지 · 계정 정지 · 탈퇴 · 대표자 아님) /
 *     신청자 정지 · 탈퇴(404) / 취소된 요청(409) / 없는 요청(404) / 이미 내 것(멱등 200 — 상한 · 신청자 정지여도).
 *   · 놓기 · 종료: 성공 / 남의 사무소(404) / 진행 중 아님(409) / 결과 없음(400) / 낡은 토큰.
 *   · 상세: 내 사무소 건은 전체, 대기 목록의 건은 요약, 나머지는 404.
 *   · 잠금 순서: 쓰기는 일반 읽기 전에 파트너 users → 대표자 연결 → 사무소 → 부문을 잠금 읽기로 잡고, 신청자 users 를 요청 행보다 먼저 잡는다.
 *   · 잡기 이력: 잡기 · 놓기 · 종료가 각각 한 행(행위자 = 파트너, 그 사무소). 멱등 잡기 · 실패는 남기지 않는다.
 * ★ 실패한 처리는 요청을 바꾸지 않고 알림도 이력도 넣지 않는다 — 매번 같이 본다.
 */
@ExtendWith(MockitoExtension.class)
class PartnerRentalRequestServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, SEOUL);
    private static final String BROKER = "broker-uuid";
    private static final long PARTNER_ID = 7L;
    private static final long OFFICE = 70L;
    private static final long OTHER_OFFICE = 80L;
    private static final long STUDENT = 10L;
    private static final String REQUEST = "r-1";
    private static final int LIMIT = 2;
    private static final String OFFICE_NAME = "행복공인중개사사무소";

    @Mock UserQueryService userQueryService;
    @Mock UserRepository userRepository;
    @Mock BrokerProfileRepository profileRepository;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock PartnerDivisionRepository divisionRepository;
    @Mock RentalRequestRepository requestRepository;
    @Mock RentalRequestSelectionResolver selectionResolver;
    @Mock PartnerRentalRequestAssembler assembler;
    @Mock NotificationPublisher notificationPublisher;
    @Mock RentalRequestClaimEventRepository eventRepository;

    private PartnerRentalRequestService service;

    @BeforeEach
    void setUp() {
        service = new PartnerRentalRequestService(userQueryService, userRepository, profileRepository,
                officeRepository, divisionRepository, requestRepository, selectionResolver, assembler,
                new RentalRequestNotifier(notificationPublisher),
                new RentalRequestClaimHistory(eventRepository, officeRepository),
                new RentalRequestProperties(1, LIMIT), Clock.fixed(NOW, SEOUL));
    }

    // ───────────────────────── 픽스처

    private static UserRepository.AuthSnapshot snapshot(UserStatus status) {
        return new UserRepository.AuthSnapshot() {
            @Override public String getUuid() { return BROKER; }
            @Override public String getRole() { return "PARTNER"; }
            @Override public String getStatus() { return status.name(); }
        };
    }

    // DB 로 다시 본 파트너 — 계정 상태 · 대표자 연결 · 부문 상태. null 이면 그 단계에서 없음.
    private void givenHandler(UserStatus account, Long officeId, String division) {
        given(userRepository.findIdByUuidForUpdate(BROKER)).willReturn(Optional.of(PARTNER_ID));
        given(userRepository.findAuthSnapshotForUpdate(PARTNER_ID)).willReturn(Optional.of(snapshot(account)));
        lenient().when(profileRepository.findOfficeIdForUpdate(PARTNER_ID)).thenReturn(Optional.ofNullable(officeId));
        lenient().when(divisionRepository.findStatusForUpdate(PARTNER_ID, "REAL_ESTATE_BROKER"))
                .thenReturn(Optional.ofNullable(division));
    }

    private void givenApprovedHandler() {
        givenHandler(UserStatus.ACTIVE, OFFICE, "APPROVED");
    }

    private static RentalRequest request(RentalRequestStatus status, Long officeId) {
        RentalRequest request = RentalRequest.builder()
                .uuid(REQUEST).requestNumber("RQ-261007-AAAAAA").userId(STUDENT).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 11, 1)).version(3L).build();
        ReflectionTestUtils.setField(request, "id", 500L);
        if (officeId != null) {
            request.claim(officeId, NOW_LOCAL.minusDays(1));
        }
        if (status == RentalRequestStatus.CLOSED) {
            request.close(officeId, RentalRequestOutcome.NOT_PROCEEDED, NOW_LOCAL.minusHours(1));
        } else if (status == RentalRequestStatus.CANCELED) {
            request.cancel("직접 구했습니다", NOW_LOCAL.minusHours(1));
        }
        return request;
    }

    // 잡기 · 놓기 · 종료가 잠그는 요청 — 신청자 상태는 공유 잠금으로 읽힌다.
    private RentalRequest givenLocked(RentalRequestStatus status, Long officeId, UserStatus applicant) {
        RentalRequest request = request(status, officeId);
        given(requestRepository.findUserIdByUuid(REQUEST)).willReturn(Optional.of(STUDENT));
        given(userRepository.findStatusByIdForShare(STUDENT)).willReturn(Optional.ofNullable(applicant));
        lenient().when(requestRepository.findByUuidForUpdate(REQUEST)).thenReturn(Optional.of(request));
        return request;
    }

    private void givenInProgressCount(long count) {
        given(requestRepository.countByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS)).willReturn(count);
    }

    private void givenOfficeName() {
        given(officeRepository.findById(OFFICE)).willReturn(Optional.of(BrokerageOffice.builder().id(OFFICE)
                .uuid("o-1").regNo("99999-2026-00001").name(OFFICE_NAME).ceoName("홍길동")
                .address("서울특별시 광진구 능동로 000").tel("02-000-0000").build()));
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    // 실패한 처리는 요청을 그대로 둔다 — 상태 · 맡은 사무소 · 잡은 시각 · 결과.
    private static Consumer<RentalRequest> unchanged(RentalRequest request) {
        RentalRequestStatus status = request.getStatus();
        Long officeId = request.getOfficeId();
        LocalDateTime claimedAt = request.getClaimedAt();
        RentalRequestOutcome outcome = request.getOutcome();
        return after -> {
            assertThat(after.getStatus()).isEqualTo(status);
            assertThat(after.getOfficeId()).isEqualTo(officeId);
            assertThat(after.getClaimedAt()).isEqualTo(claimedAt);
            assertThat(after.getOutcome()).isEqualTo(outcome);
        };
    }

    private void thenNotified(NotiType type, Map<String, Object> params, LocalDateTime at) {
        then(notificationPublisher).should().publish(STUDENT, type,
                type.name() + ":request:" + REQUEST + ":" + at, params, "/rental-requests/" + REQUEST);
    }

    private void thenNotNotified() {
        then(notificationPublisher).should(never()).publish(any(), any(), anyString(), anyMap(), anyString());
        then(eventRepository).should(never()).save(any());
    }

    // 잡기 이력에 남은 행 — 정확히 한 행이어야 한다.
    private RentalRequestClaimEvent onlyHistoryRow() {
        ArgumentCaptor<RentalRequestClaimEvent> saved = ArgumentCaptor.forClass(RentalRequestClaimEvent.class);
        then(eventRepository).should().save(saved.capture());
        assertThat(saved.getAllValues()).hasSize(1);
        return saved.getValue();
    }

    private static void assertRow(RentalRequestClaimEvent row, RentalRequestClaimEventType type,
                                  RentalRequestOutcome outcome) {
        assertThat(row.getRentalRequestId()).isEqualTo(500L);
        assertThat(row.getOfficeId()).isEqualTo(OFFICE);
        assertThat(row.getActorId()).as("행위자는 그 사무소의 대표자").isEqualTo(PARTNER_ID);
        assertThat(row.getEventType()).isEqualTo(type);
        assertThat(row.getReturnCause()).isNull();
        assertThat(row.getOutcome()).isEqualTo(outcome);
        assertThat(row.getOccurredAt()).isEqualTo(NOW_LOCAL);
    }

    // ───────────────────────── 잡기

    @Nested
    @DisplayName("잡기")
    class Claim {

        @Test
        @DisplayName("대기 요청을 잡으면 진행 중이 되고 사무소 · 잡은 시각이 붙으며, 유학생에게 사무소 상호를 실은 알림이 간다")
        void claims_waiting_request() {
            givenApprovedHandler();
            givenInProgressCount(LIMIT - 1);
            RentalRequest request = givenLocked(RentalRequestStatus.SUBMITTED, null, UserStatus.ACTIVE);
            givenOfficeName();

            service.claim(BROKER, REQUEST);

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.IN_PROGRESS);
            assertThat(request.getOfficeId()).isEqualTo(OFFICE);
            assertThat(request.getClaimedAt()).isEqualTo(NOW_LOCAL);
            thenNotified(NotiType.RENTAL_REQUEST_IN_PROGRESS,
                    Map.of("0", "RQ-261007-AAAAAA", "1", OFFICE_NAME), NOW_LOCAL);
            then(assembler).should().full(request);
            assertRow(onlyHistoryRow(), RentalRequestClaimEventType.CLAIMED, null);
        }

        /*
         * ★ 순서가 곧 정합성이다. 사무소 행 잠금 **앞**은 전부 잠금 읽기다 — 그 뒤의 일반 읽기(신청자 조회 · 진행 중 개수)가 만드는
         *   읽기 뷰가 앞사람의 잡기 커밋을 본다. 신청자 users 행은 요청 행보다 먼저 잡는다(알림 FK 검사와 유학생 탈퇴의 엇갈림을 막는다).
         *   개수는 요청 행을 잠근 뒤에 센다 — 이미 내 것인 요청의 두 번째 누름이 상한에 막히지 않게(멱등). 이력 · 알림은 UPDATE 를 내보낸 뒤다.
         */
        @Test
        @DisplayName("잠금 순서: 파트너 users → 대표자 연결 → 사무소 → 부문 → 신청자 users → 요청 행 → 개수 → UPDATE → 이력 → 알림")
        void lock_order() {
            givenApprovedHandler();
            givenInProgressCount(0);
            givenLocked(RentalRequestStatus.SUBMITTED, null, UserStatus.ACTIVE);
            givenOfficeName();

            service.claim(BROKER, REQUEST);

            InOrder order = inOrder(userRepository, profileRepository, officeRepository, divisionRepository,
                    requestRepository, eventRepository, notificationPublisher);
            order.verify(userRepository).findIdByUuidForUpdate(BROKER);
            order.verify(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);
            order.verify(profileRepository).findOfficeIdForUpdate(PARTNER_ID);
            order.verify(officeRepository).lockById(OFFICE);
            order.verify(divisionRepository).findStatusForUpdate(PARTNER_ID, "REAL_ESTATE_BROKER");
            order.verify(requestRepository).findUserIdByUuid(REQUEST);
            order.verify(userRepository).findStatusByIdForShare(STUDENT);
            order.verify(requestRepository).findByUuidForUpdate(REQUEST);
            order.verify(requestRepository).countByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS);
            order.verify(requestRepository).flush();
            order.verify(eventRepository).save(any());
            order.verify(notificationPublisher).publish(any(), any(), anyString(), anyMap(), anyString());
            // 잠금 전의 일반 읽기가 없다 — 관문 조회(getActiveUser)도 대표자 연결의 일반 조회도 쓰지 않는다.
            then(userQueryService).shouldHaveNoInteractions();
            then(profileRepository).should(never()).findByUserId(any());
            then(requestRepository).should(never()).findByUuid(any());
        }

        @Test
        @DisplayName("다른 사무소가 먼저 잡았으면 409 ALREADY_CLAIMED — 요청 · 알림 그대로")
        void loses_to_another_office() {
            givenApprovedHandler();
            givenInProgressCount(0);
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_ALREADY_CLAIMED"));
            same.accept(request);
            thenNotNotified();
        }

        // 요청 행을 잠그고 읽어 보통은 생기지 않지만, @Version 이 막았다면 일반 409 가 아니라 선착순의 답이어야 한다.
        @Test
        @DisplayName("UPDATE 가 @Version 에 걸리면 일반 409 가 아니라 ALREADY_CLAIMED 로 번역하고 알림을 넣지 않는다")
        void version_conflict_is_translated() {
            givenApprovedHandler();
            givenInProgressCount(0);
            givenLocked(RentalRequestStatus.SUBMITTED, null, UserStatus.ACTIVE);
            willThrow(new ObjectOptimisticLockingFailureException(RentalRequest.class, 500L))
                    .given(requestRepository).flush();

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_ALREADY_CLAIMED"));
            thenNotNotified();
        }

        // 상한은 "이미 내 것인가"를 본 뒤에 센다 — 그래서 요청 행은 잠그지만(멱등 판정) 바꾸지 않는다.
        @Test
        @DisplayName("내 사무소의 진행 중이 상한이면 409 CLAIM_LIMIT — 요청 · 알림 · 이력 그대로")
        void limit_reached() {
            givenApprovedHandler();
            givenInProgressCount(LIMIT);
            RentalRequest request = givenLocked(RentalRequestStatus.SUBMITTED, null, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_CLAIM_LIMIT"));
            same.accept(request);
            then(requestRepository).should(never()).flush();
            thenNotNotified();
        }

        /*
         * ★ 두 번 누른 잡기 — 이미 내 사무소가 잡아 진행 중이면 멱등 성공이다. "다른 사무소가 맡았다"(409)는 거짓 안내다.
         *   전체 응답을 그대로 주고, 요청 · 이력 · 알림을 바꾸지 않는다. 개수도 세지 않는다(상한에 닿아 있어도 내 것이다).
         */
        @Test
        @DisplayName("이미 내 사무소가 잡은 진행 중 요청을 다시 잡으면 멱등 성공 — 전체 응답, 이력 · 알림 · 개수 변화 없음")
        void double_tap_is_idempotent() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            service.claim(BROKER, REQUEST);

            same.accept(request);
            then(assembler).should().full(request);
            then(requestRepository).should(never()).countByOfficeIdAndStatus(any(), any());
            then(requestRepository).should(never()).flush();
            thenNotNotified();
        }

        // 상한에 닿게 만든 바로 그 요청의 두 번째 누름 · 그사이 신청자가 정지된 내 요청 — 둘 다 내 것이라 멱등 성공이다(상세 조회와 같은 답).
        @ParameterizedTest(name = "신청자 {0}")
        @EnumSource(value = UserStatus.class, names = {"ACTIVE", "SUSPENDED"})
        @DisplayName("멱등 잡기는 상한 · 신청자 정지보다 먼저 본다")
        void idempotent_before_limit_and_applicant(UserStatus applicant) {
            givenApprovedHandler();
            lenient().when(requestRepository.countByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS))
                    .thenReturn((long) LIMIT);
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OFFICE, applicant);
            Consumer<RentalRequest> same = unchanged(request);

            service.claim(BROKER, REQUEST);

            same.accept(request);
            then(assembler).should().full(request);
            thenNotNotified();
        }

        // 내 사무소가 맡았다가 이미 종료한 요청은 "내 진행 중"이 아니다 — 다시 잡을 수 있는 대기 건도 아니다.
        @Test
        @DisplayName("내 사무소가 종료한 요청을 다시 잡으면 409 REQUEST_NOT_OPEN — 멱등이 아니다")
        void closed_by_me_is_not_idempotent() {
            givenApprovedHandler();
            givenInProgressCount(0);
            RentalRequest request = givenLocked(RentalRequestStatus.CLOSED, OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("REQUEST_NOT_OPEN"));
            same.accept(request);
            thenNotNotified();
        }

        @Test
        @DisplayName("그사이 유학생이 취소했으면 409 REQUEST_NOT_OPEN")
        void canceled_request() {
            givenApprovedHandler();
            givenInProgressCount(0);
            RentalRequest request = givenLocked(RentalRequestStatus.CANCELED, null, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("REQUEST_NOT_OPEN"));
            same.accept(request);
            thenNotNotified();
        }

        // 대기 목록이 정지된 유학생의 요청을 숨긴다 — 목록에 없는 요청을 잡을 수 없다(연락이 닿지 않는 사람이다).
        @ParameterizedTest(name = "신청자 {0}")
        @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
        @DisplayName("신청자 계정이 정지 · 탈퇴면 404 — 요청 · 알림 · 이력 그대로")
        void inactive_applicant(UserStatus applicant) {
            givenApprovedHandler();
            givenInProgressCount(0);
            RentalRequest request = givenLocked(RentalRequestStatus.SUBMITTED, null,
                    applicant == UserStatus.WITHDRAWN ? null : applicant);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
            same.accept(request);
            then(requestRepository).should(never()).flush();
            thenNotNotified();
        }

        @Test
        @DisplayName("없는 요청이면 404")
        void unknown_request() {
            givenApprovedHandler();
            given(requestRepository.findUserIdByUuid(REQUEST)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
            then(requestRepository).should(never()).findByUuidForUpdate(any());
            thenNotNotified();
        }
    }

    // ───────────────────────── 낡은 토큰

    /*
     * ★ 토큰의 RENTAL_REQUEST_HANDLE 만 믿지 않는다 — 정지는 세션을 끊지만 이미 나간 요청 하나를 막지 못하고, 계정 정지는 부문을
     *   건드리지 않는다. 잡기 · 놓기 · 종료 모두 DB 로 다시 보고, 걸리면 사무소의 개수 · 요청에 닿기 전에 멈춘다.
     */
    @Nested
    @DisplayName("낡은 토큰 — DB 재판정")
    class StaleToken {

        private void thenNothingTouched() {
            then(requestRepository).should(never()).countByOfficeIdAndStatus(any(), any());
            then(requestRepository).should(never()).findUserIdByUuid(any());
            then(requestRepository).should(never()).findByUuidForUpdate(any());
            thenNotNotified();
        }

        @ParameterizedTest(name = "부문 {0}")
        @ValueSource(strings = {"SUSPENDED", "PENDING", "REJECTED"})
        @DisplayName("공인중개사 부문이 승인 상태가 아니면 403 OFFICE_NOT_ACTIVE — 사무소 행을 잠근 뒤에 본다")
        void division_not_approved(String division) {
            givenHandler(UserStatus.ACTIVE, OFFICE, division);

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_OFFICE_NOT_ACTIVE"));
            then(officeRepository).should().lockById(OFFICE);
            thenNothingTouched();
        }

        @Test
        @DisplayName("부문 행이 없어도 403 OFFICE_NOT_ACTIVE")
        void no_division() {
            givenHandler(UserStatus.ACTIVE, OFFICE, null);

            assertThatThrownBy(() -> service.release(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_OFFICE_NOT_ACTIVE"));
            thenNothingTouched();
        }

        @Test
        @DisplayName("계정이 정지됐으면 403 USER_SUSPENDED — 사무소도 잠그지 않는다")
        void account_suspended() {
            givenHandler(UserStatus.SUSPENDED, OFFICE, "APPROVED");

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("USER_SUSPENDED"));
            then(officeRepository).should(never()).lockById(any());
            thenNothingTouched();
        }

        @Test
        @DisplayName("탈퇴한 계정이면 USER_NOT_FOUND")
        void account_withdrawn() {
            given(userRepository.findIdByUuidForUpdate(BROKER)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(BROKER, REQUEST, RentalRequestOutcome.FOUND))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("USER_NOT_FOUND"));
            then(officeRepository).should(never()).lockById(any());
            thenNothingTouched();
        }

        // 잠금 읽기로 다시 본 상태가 ACTIVE · SUSPENDED 가 아니면(탈퇴 표시만 먼저 보이는 경우) 통과시키지 않는다 —
        // 위 경우는 첫 잠금 읽기가 비어 나오는 길이고, 이것은 상태 칸으로 거르는 길이다.
        @Test
        @DisplayName("다시 읽은 계정 상태가 WITHDRAWN 이면 USER_NOT_FOUND — 사무소도 잠그지 않는다")
        void account_status_withdrawn() {
            givenHandler(UserStatus.WITHDRAWN, OFFICE, "APPROVED");

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("USER_NOT_FOUND"));
            then(officeRepository).should(never()).lockById(any());
            thenNothingTouched();
        }

        @Test
        @DisplayName("사무소 대표자 연결이 없으면 403 BROKER_PROFILE_NOT_FOUND")
        void not_a_representative() {
            givenHandler(UserStatus.ACTIVE, null, "APPROVED");

            assertThatThrownBy(() -> service.claim(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("BROKER_PROFILE_NOT_FOUND"));
            then(officeRepository).should(never()).lockById(any());
            thenNothingTouched();
        }
    }

    // ───────────────────────── 놓기 · 종료

    @Nested
    @DisplayName("놓기")
    class Release {

        @Test
        @DisplayName("맡은 요청을 놓으면 대기로 돌아가 사무소 · 잡은 시각이 비고, 유학생에게 다시 대기 알림이 간다 — 응답은 요약")
        void releases() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OFFICE, UserStatus.ACTIVE);

            service.release(BROKER, REQUEST);

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
            assertThat(request.getOfficeId()).isNull();
            assertThat(request.getClaimedAt()).isNull();
            thenNotified(NotiType.RENTAL_REQUEST_RELEASED, Map.of("0", "RQ-261007-AAAAAA"), NOW_LOCAL);
            then(assembler).should().summary(request);
            then(assembler).should(never()).full(any());
            InOrder order = inOrder(officeRepository, userRepository, requestRepository, eventRepository);
            order.verify(officeRepository).lockById(OFFICE);
            order.verify(userRepository).findStatusByIdForShare(STUDENT);
            order.verify(requestRepository).findByUuidForUpdate(REQUEST);
            order.verify(eventRepository).save(any());
            assertRow(onlyHistoryRow(), RentalRequestClaimEventType.RELEASED, null);
        }

        @Test
        @DisplayName("다른 사무소가 맡은 요청은 404 — 그대로 둔다")
        void other_office() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.release(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
            same.accept(request);
            thenNotNotified();
        }

        @Test
        @DisplayName("내 사무소가 맡았지만 이미 종료 · 취소됐으면 409 NOT_IN_PROGRESS")
        void not_in_progress() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.CANCELED, OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.release(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_IN_PROGRESS"));
            same.accept(request);
            thenNotNotified();
        }
    }

    @Nested
    @DisplayName("종료")
    class Close {

        @Test
        @DisplayName("맡은 요청을 결과와 함께 접으면 종료 · 결과 · 종료 시각이 남고 사무소는 그대로, 알림에는 결과가 없다")
        void closes_with_outcome() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OFFICE, UserStatus.ACTIVE);

            service.close(BROKER, REQUEST, RentalRequestOutcome.FOUND);

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.CLOSED);
            assertThat(request.getOutcome()).isEqualTo(RentalRequestOutcome.FOUND);
            assertThat(request.getClosedAt()).isEqualTo(NOW_LOCAL);
            assertThat(request.getOfficeId()).isEqualTo(OFFICE);
            thenNotified(NotiType.RENTAL_REQUEST_CLOSED, Map.of("0", "RQ-261007-AAAAAA"), NOW_LOCAL);
            then(assembler).should().full(request);
            assertRow(onlyHistoryRow(), RentalRequestClaimEventType.CLOSED, RentalRequestOutcome.FOUND);
        }

        @Test
        @DisplayName("결과를 고르지 않으면 400 OUTCOME_REQUIRED")
        void outcome_required() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.close(BROKER, REQUEST, null))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_OUTCOME_REQUIRED"));
            same.accept(request);
            thenNotNotified();
        }

        @Test
        @DisplayName("다른 사무소가 맡은 요청은 404")
        void other_office() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.close(BROKER, REQUEST, RentalRequestOutcome.NOT_PROCEEDED))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
            same.accept(request);
            thenNotNotified();
        }

        @Test
        @DisplayName("이미 종료된 요청을 다시 접으면 409 NOT_IN_PROGRESS — 결과가 바뀌지 않는다")
        void already_closed() {
            givenApprovedHandler();
            RentalRequest request = givenLocked(RentalRequestStatus.CLOSED, OFFICE, UserStatus.ACTIVE);
            Consumer<RentalRequest> same = unchanged(request);

            assertThatThrownBy(() -> service.close(BROKER, REQUEST, RentalRequestOutcome.FOUND))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_IN_PROGRESS"));
            same.accept(request);
            thenNotNotified();
        }
    }

    // ───────────────────────── 조회

    @Nested
    @DisplayName("조회 — 어느 모양을 주나")
    class Reads {

        private User partner() {
            User partner = User.builder().uuid(BROKER).role(UserRole.PARTNER).status(UserStatus.ACTIVE).build();
            ReflectionTestUtils.setField(partner, "id", PARTNER_ID);
            return partner;
        }

        private void givenRepresentative() {
            given(userQueryService.getActiveUser(BROKER)).willReturn(partner());
            given(profileRepository.findByUserId(PARTNER_ID))
                    .willReturn(Optional.of(BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE).build()));
        }

        private RentalRequest givenFound(RentalRequestStatus status, Long officeId) {
            RentalRequest request = request(status, officeId);
            given(requestRepository.findByUuid(REQUEST)).willReturn(Optional.of(request));
            return request;
        }

        private void givenApplicant(UserStatus status) {
            User student = User.builder().uuid("s-1").role(UserRole.USER).status(status).build();
            ReflectionTestUtils.setField(student, "id", STUDENT);
            given(userRepository.findById(STUDENT)).willReturn(Optional.of(student));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RentalRequestStatus.class, names = {"IN_PROGRESS", "CLOSED", "CANCELED"})
        @DisplayName("내 사무소가 맡은(맡았던) 요청은 전체 모양이다")
        void mine_is_full(RentalRequestStatus status) {
            givenRepresentative();
            RentalRequest request = givenFound(status, OFFICE);

            service.get(BROKER, REQUEST);

            then(assembler).should().full(request);
            then(assembler).should(never()).summary(any());
        }

        @Test
        @DisplayName("대기 목록에 있는 요청(대기 · 신청자 ACTIVE)은 요약 모양이다")
        void waiting_is_summary() {
            givenRepresentative();
            RentalRequest request = givenFound(RentalRequestStatus.SUBMITTED, null);
            givenApplicant(UserStatus.ACTIVE);

            service.get(BROKER, REQUEST);

            then(assembler).should().summary(request);
            then(assembler).should(never()).full(any());
        }

        @Test
        @DisplayName("다른 사무소가 맡은 요청은 404 — 요약도 주지 않는다")
        void other_office_is_not_found() {
            givenRepresentative();
            givenFound(RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE);

            assertThatThrownBy(() -> service.get(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
            then(assembler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("신청자가 정지된 대기 요청 · 대기 중에 취소된 요청은 404 — 대기 목록과 같은 조건")
        void hidden_from_pool_is_not_found() {
            givenRepresentative();
            givenFound(RentalRequestStatus.SUBMITTED, null);
            givenApplicant(UserStatus.SUSPENDED);
            assertThatThrownBy(() -> service.get(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));

            givenFound(RentalRequestStatus.CANCELED, null);
            assertThatThrownBy(() -> service.get(BROKER, REQUEST))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
            then(assembler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("대기 목록 — 지역을 고르지 않으면 전국, 고르면 그 시군구 id 로 좁힌다(요약)")
        void pool_by_regions() {
            givenRepresentative();
            given(selectionResolver.resolveRegions(null)).willReturn(List.of());
            given(requestRepository.findPool(any())).willReturn(new PageImpl<>(List.of()));

            service.findPool(BROKER, null, PageRequest.of(0, 20));

            then(requestRepository).should().findPool(PageRequest.of(0, 20));

            Region gwangjin = Region.builder().code("11215").build();
            ReflectionTestUtils.setField(gwangjin, "id", 20L);
            given(selectionResolver.resolveRegions(List.of("11215"))).willReturn(List.of(gwangjin));
            given(requestRepository.findPoolInRegions(eq(List.of(20L)), any())).willReturn(new PageImpl<>(List.of()));

            service.findPool(BROKER, List.of("11215"), PageRequest.of(0, 20));

            then(requestRepository).should().findPoolInRegions(List.of(20L), PageRequest.of(0, 20));
            then(assembler).should(never()).fulls(any());
        }

        @Test
        @DisplayName("맡은 요청 — 내 사무소 · 상태 필터로 찾고 전체 모양이다")
        void mine_list() {
            givenRepresentative();
            given(requestRepository.findHandledByOffice(eq(OFFICE), eq(RentalRequestStatus.IN_PROGRESS), any()))
                    .willReturn(new PageImpl<>(List.of()));

            service.findMine(BROKER, RentalRequestStatus.IN_PROGRESS, PageRequest.of(0, 20));

            then(assembler).should().fulls(List.of());
            then(assembler).should(never()).summaries(any());
        }

        @Test
        @DisplayName("대표자 연결이 없으면 조회도 403 BROKER_PROFILE_NOT_FOUND")
        void reads_need_representative() {
            given(userQueryService.getActiveUser(BROKER)).willReturn(partner());
            given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.findPool(BROKER, null, PageRequest.of(0, 20)))
                    .satisfies(e -> assertThat(codeOf(e).getCode()).isEqualTo("BROKER_PROFILE_NOT_FOUND"));
        }
    }
}
