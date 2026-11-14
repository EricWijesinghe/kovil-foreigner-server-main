package com.kovil.domain.rentalrequest.service;

import com.kovil.common.util.BusinessNumberGenerator;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.rentalrequest.config.RentalRequestProperties;
import com.kovil.domain.rentalrequest.dto.request.RentalRequestSaveRequest;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRegionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.service.VerificationQueryService;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RentalRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, SEOUL);
    private static final String USER_UUID = "user-uuid";
    private static final String REQUEST_UUID = "request-uuid";

    @Mock UserQueryService userQueryService;
    @Mock VerificationQueryService verificationQueryService;
    @Mock RentalRequestSelectionResolver selectionResolver;
    @Mock RentalRequestAssembler assembler;
    @Mock RentalRequestRepository requestRepository;
    @Mock RentalRequestRegionRepository requestRegionRepository;
    @Mock RentalRequestOptionRepository requestOptionRepository;
    @Mock BusinessNumberGenerator businessNumberGenerator;
    @Mock RequestAnswerResolver answerResolver;
    @Mock RequestOptionQueryService requestOptionQueryService;
    @Mock RegionRepository regionRepository;

    @Mock UserRepository userRepository;

    private RentalRequestService service;

    @BeforeEach
    void setUp() {
        service = new RentalRequestService(userQueryService, userRepository, verificationQueryService, selectionResolver,
                answerResolver, requestOptionQueryService, regionRepository,
                assembler, requestRepository, requestRegionRepository, requestOptionRepository,
                businessNumberGenerator, new RentalRequestProperties(1, 50), Clock.fixed(NOW, SEOUL));
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

    private static User user(Long id) {
        return withId(User.builder().uuid(USER_UUID).name("holder")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build(), id);
    }

    private static ForeignerVerification verification(Long id) {
        return withId(ForeignerVerification.builder()
                .uuid("v-uuid").userId(10L).residentStatusId(1L).residentStatusCode("STUDENT")
                .requiredDocumentCodes("[]").nationalityCountryId(7L).fullName("NGUYEN VAN A")
                .birthDate(LocalDate.of(1998, 3, 15)).build(), id);
    }

    private static RentalRequest request(RentalRequestStatus status) {
        return withId(RentalRequest.builder()
                .uuid(REQUEST_UUID).requestNumber("RQ-260811-ABC123").userId(10L).verificationId(99L)
                .status(status).moveInDate(TODAY.plusMonths(1)).build(), 500L);
    }

    private static RentalRequestSaveRequest saveRequest() {
        return new RentalRequestSaveRequest(List.of("11215"), List.of("ONE_ROOM"), null,
                TODAY.plusMonths(1), true, (short) 12,
                5_000_000L, 20_000_000L, 500_000L, 900_000L, (byte) 2, "건대입구역 도보 10분", "note");
    }

    private static String codeOf(Throwable e) {
        return ((RentalRequestException) e).getErrorCode().getCode();
    }

    private void givenVerifiedUser() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
        given(verificationQueryService.findEffective(10L)).willReturn(Optional.of(verification(99L)));
    }

    private void givenSelectionsResolve() {
        given(selectionResolver.resolveRegions(any())).willReturn(List.of(
                withId(Region.builder().parentId(1L).code("11215").nameKo("광진구").nameEn("Gwangjin").build(), 20L)));
        given(selectionResolver.resolveOptions(any())).willReturn(List.of(
                withId(RequestOption.builder().parentId(1L).code("ONE_ROOM").build(), 11L)));
    }

    @Nested
    @DisplayName("인증 게이트 (FR-301)")
    class Gate {

        // ★ 이 프로젝트에서 인증이 처음으로 무언가를 막는 지점이다.
        @Test
        @DisplayName("승인된 인증이 없으면 VERIFICATION_REQUIRED이고 아무것도 저장되지 않는다")
        void unverified_is_blocked() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            given(verificationQueryService.findEffective(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(USER_UUID, saveRequest()))
                    .isInstanceOf(RentalRequestException.class)
                    .extracting(RentalRequestServiceTest::codeOf)
                    .isEqualTo("VERIFICATION_REQUIRED");

            then(requestRepository).should(never()).save(any());
            verifyNoInteractions(selectionResolver);
        }

        // FR-302 — 통과 근거를 FK로 고정한다. 이후 인증이 만료돼도 이력은 근거를 잃지 않는다.
        @Test
        @DisplayName("생성된 요청은 통과 근거가 된 인증 건을 고정한다")
        void verification_is_pinned() {
            givenVerifiedUser();
            givenSelectionsResolve();
            given(requestRepository.save(any())).willAnswer(call -> call.getArgument(0));
            given(businessNumberGenerator.generate(any(), any())).willReturn("RQ-260811-ABC123");

            service.create(USER_UUID, saveRequest());

            ArgumentCaptor<RentalRequest> captor = ArgumentCaptor.forClass(RentalRequest.class);
            then(requestRepository).should().save(captor.capture());
            assertThat(captor.getValue().getVerificationId()).isEqualTo(99L);
            assertThat(captor.getValue().getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
            assertThat(captor.getValue().getRequestNumber()).startsWith("RQ-");
        }
    }

    @Nested
    @DisplayName("동시 진행 제한 (FR-309)")
    class ActiveLimit {

        @Test
        @DisplayName("진행 중인 요청이 한도에 차 있으면 새로 만들 수 없다")
        void limit_blocks_creation() {
            givenVerifiedUser();
            given(requestRepository.countByUserIdAndStatusIn(anyLong(), any())).willReturn(1L);

            assertThatThrownBy(() -> service.create(USER_UUID, saveRequest()))
                    .extracting(RentalRequestServiceTest::codeOf)
                    .isEqualTo("RENTAL_REQUEST_LIMIT_EXCEEDED");

            then(requestRepository).should(never()).save(any());
        }

        /*
         * ★ 잠금이 **트랜잭션의 첫 읽기**여야 한다. 잠금 읽기는 InnoDB 읽기 뷰를 만들지 않아,
         *   첫 문장이면 이후 일반 count 가 경쟁자 커밋 이후의 스냅샷을 받는다. 일반 조회가
         *   앞서면 스냅샷이 잠금 이전에 고정되어 상한이 다시 뚫린다(적대적 리뷰 2026-09-02).
         *   잠금을 getActiveUser 뒤로 되돌리면 이 테스트가 죽는다.
         */
        @Test
        @DisplayName("유저 잠금이 다른 어떤 조회보다 먼저다 — 순서가 곧 정합성이다")
        void user_lock_is_the_first_read() {
            givenVerifiedUser();
            givenSelectionsResolve();
            given(requestRepository.save(any())).willAnswer(call -> call.getArgument(0));
            given(businessNumberGenerator.generate(any(), any())).willReturn("RQ-260811-ABC123");

            service.create(USER_UUID, saveRequest());

            InOrder inOrder = Mockito.inOrder(
                    userRepository, userQueryService, requestRepository);
            inOrder.verify(userRepository).findIdByUuidForUpdate(USER_UUID);
            inOrder.verify(userQueryService).getActiveUser(USER_UUID);
            inOrder.verify(requestRepository).countByUserIdAndStatusIn(anyLong(), any());
        }

        // 취소·종료된 건은 열린 건이 아니다 — 판정 축이 SUBMITTED·IN_PROGRESS뿐이다.
        @Test
        @DisplayName("한도 판정은 SUBMITTED·IN_PROGRESS만 센다")
        void only_open_statuses_count() {
            givenVerifiedUser();
            givenSelectionsResolve();
            given(requestRepository.save(any())).willAnswer(call -> call.getArgument(0));
            given(businessNumberGenerator.generate(any(), any())).willReturn("RQ-260811-ABC123");

            service.create(USER_UUID, saveRequest());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<RentalRequestStatus>> captor =
                    ArgumentCaptor.forClass(Collection.class);
            then(requestRepository).should().countByUserIdAndStatusIn(anyLong(), captor.capture());
            assertThat(captor.getValue())
                    .containsExactlyInAnyOrder(RentalRequestStatus.SUBMITTED, RentalRequestStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        @Test
        @DisplayName("입주 희망일이 과거면 거절하고 지역·옵션은 조회조차 하지 않는다")
        void past_move_in_date() {
            givenVerifiedUser();

            RentalRequestSaveRequest past = new RentalRequestSaveRequest(List.of("11215"), List.of(), null,
                TODAY.minusDays(1), false, null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.create(USER_UUID, past))
                    .extracting(RentalRequestServiceTest::codeOf)
                    .isEqualTo("MOVE_IN_DATE_IN_PAST");

            verifyNoInteractions(selectionResolver);
        }

        @Test
        @DisplayName("오늘 입주는 허용된다")
        void today_move_in_date_ok() {
            givenVerifiedUser();
            givenSelectionsResolve();
            given(requestRepository.save(any())).willAnswer(call -> call.getArgument(0));
            given(businessNumberGenerator.generate(any(), any())).willReturn("RQ-260811-ABC123");

            RentalRequestSaveRequest today = new RentalRequestSaveRequest(List.of("11215"), List.of(), null,
                TODAY, false, null, null, null, null, null, null, null, null);

            service.create(USER_UUID, today);

            then(requestRepository).should().save(any());
        }

        // 하한 > 상한이면 어떤 매물도 걸리지 않는다 — 사무소가 손도 못 대는 요청이 된다.
        @Test
        @DisplayName("보증금·월세 하한이 상한보다 크면 BUDGET_RANGE_INVALID다")
        void inverted_budget_range() {
            givenVerifiedUser();

            RentalRequestSaveRequest depositInverted = new RentalRequestSaveRequest(List.of("11215"), List.of(), null,
                TODAY.plusDays(1), false, null, 20_000_000L, 5_000_000L, null, null, null, null, null);
            assertThatThrownBy(() -> service.create(USER_UUID, depositInverted))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("BUDGET_RANGE_INVALID");

            RentalRequestSaveRequest rentInverted = new RentalRequestSaveRequest(List.of("11215"), List.of(), null,
                TODAY.plusDays(1), false, null, null, null, 900_000L, 500_000L, null, null, null);
            assertThatThrownBy(() -> service.create(USER_UUID, rentInverted))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("BUDGET_RANGE_INVALID");
        }

        // 한쪽만 준 것은 "하한 없음"·"상한 없음"이지 오류가 아니다.
        @Test
        @DisplayName("하한만 또는 상한만 주는 것은 허용된다")
        void one_sided_range_ok() {
            givenVerifiedUser();
            givenSelectionsResolve();
            given(requestRepository.save(any())).willAnswer(call -> call.getArgument(0));
            given(businessNumberGenerator.generate(any(), any())).willReturn("RQ-260811-ABC123");

            RentalRequestSaveRequest onlyMax = new RentalRequestSaveRequest(List.of("11215"), List.of(), null,
                TODAY.plusDays(1), false, null, null, 20_000_000L, null, 900_000L, null, null, null);

            service.create(USER_UUID, onlyMax);

            then(requestRepository).should().save(any());
        }
    }

    @Nested
    @DisplayName("수정·취소 (FR-308)")
    class Edit {

        @Test
        @DisplayName("SUBMITTED 요청은 통째로 덮어쓴다 — 지역·옵션도 갈아끼운다")
        void update_replaces_selections() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest target = request(RentalRequestStatus.SUBMITTED);
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(target));
            givenSelectionsResolve();

            service.update(USER_UUID, REQUEST_UUID, saveRequest());

            then(requestRegionRepository).should().deleteByRentalRequestId(500L);
            then(requestOptionRepository).should().deleteByRentalRequestId(500L);
            then(requestRegionRepository).should().saveAll(any());
            then(requestOptionRepository).should().saveAll(any());
            assertThat(target.getPreferredAreaNote()).isEqualTo("건대입구역 도보 10분");
            assertThat(target.getHouseholdSize()).isEqualTo((byte) 2);
        }

        @Test
        @DisplayName("사무소가 맡은 뒤에도(IN_PROGRESS) 유학생은 아직 고칠 수 있다")
        void in_progress_is_editable() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            given(requestRepository.findByUuid(REQUEST_UUID))
                    .willReturn(Optional.of(request(RentalRequestStatus.IN_PROGRESS)));
            givenSelectionsResolve();

            service.update(USER_UUID, REQUEST_UUID, saveRequest());

            then(requestRegionRepository).should().saveAll(any());
        }

        @Test
        @DisplayName("입주일이 지난 구 요청도 수정할 수 있다 — 저장값 그대로의 이월은 과거 검사를 면제한다")
        void past_move_in_date_carried_over_is_editable() {
            // pre-V14 요청: 입주 희망일이 이미 지났다. FE는 통째 덮어쓰기 계약 때문에 저장값을
            // 그대로 되보낸다 — 여기 과거 검사를 물리면 전화번호 하나 못 고치는 요청이 된다(AUDIT).
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest target = withId(RentalRequest.builder()
                    .uuid(REQUEST_UUID).requestNumber("RQ-250811-OLD001").userId(10L).verificationId(99L)
                    .status(RentalRequestStatus.SUBMITTED).moveInDate(TODAY.minusMonths(2)).build(), 500L);
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(target));
            givenSelectionsResolve();

            RentalRequestSaveRequest carriedOver = new RentalRequestSaveRequest(
                    List.of("11215"), List.of("ONE_ROOM"), null,
                    TODAY.minusMonths(2), true, (short) 12,
                    5_000_000L, 20_000_000L, 500_000L, 900_000L, (byte) 2, "새 메모", "note");

            service.update(USER_UUID, REQUEST_UUID, carriedOver);

            assertThat(target.getPreferredAreaNote()).isEqualTo("새 메모");
        }

        @Test
        @DisplayName("입주일을 **다른** 과거 날짜로 바꾸는 것은 여전히 거절된다 — 면제는 이월에만")
        void changing_to_past_move_in_date_is_still_rejected() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest target = request(RentalRequestStatus.SUBMITTED); // 저장값: 미래
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(target));

            RentalRequestSaveRequest toPast = new RentalRequestSaveRequest(
                    List.of("11215"), List.of("ONE_ROOM"), null,
                    TODAY.minusDays(1), true, (short) 12,
                    5_000_000L, 20_000_000L, 500_000L, 900_000L, (byte) 2, "메모", "note");

            assertThatThrownBy(() -> service.update(USER_UUID, REQUEST_UUID, toPast))
                    .extracting(RentalRequestServiceTest::codeOf)
                    .isEqualTo("MOVE_IN_DATE_IN_PAST");
        }

        @Test
        @DisplayName("사무소가 종료한 요청은 수정도 취소도 되지 않는다")
        void closed_is_frozen() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest closed = request(RentalRequestStatus.SUBMITTED);
            closed.claim(77L, LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            closed.close(77L, RentalRequestOutcome.FOUND,
                    LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.update(USER_UUID, REQUEST_UUID, saveRequest()))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("REQUEST_NOT_EDITABLE");
            assertThatThrownBy(() -> service.cancel(USER_UUID, REQUEST_UUID, "이제 와서"))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("REQUEST_NOT_EDITABLE");

            assertThat(closed.getStatus()).isEqualTo(RentalRequestStatus.CLOSED);
            then(requestRegionRepository).should(never()).deleteByRentalRequestId(anyLong());
        }

        @Test
        @DisplayName("이미 취소된 요청은 다시 취소되지 않는다")
        void cancel_twice_fails() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest canceled = request(RentalRequestStatus.SUBMITTED);
            canceled.cancel("첫 취소", LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(canceled));

            assertThatThrownBy(() -> service.cancel(USER_UUID, REQUEST_UUID, "두 번째"))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("REQUEST_NOT_EDITABLE");
            assertThat(canceled.getClosedReason()).isEqualTo("첫 취소");
        }

        @Test
        @DisplayName("취소하면 사유와 종료 시각이 남는다")
        void cancel_records_reason() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest target = request(RentalRequestStatus.SUBMITTED);
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(target));

            service.cancel(USER_UUID, REQUEST_UUID, "  Found a place on my own  ");

            assertThat(target.getStatus()).isEqualTo(RentalRequestStatus.CANCELED);
            assertThat(target.getClosedReason()).isEqualTo("Found a place on my own");
            assertThat(target.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("사유 없는 취소도 가능하다")
        void cancel_without_reason() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            RentalRequest target = request(RentalRequestStatus.SUBMITTED);
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.of(target));

            service.cancel(USER_UUID, REQUEST_UUID, null);

            assertThat(target.getStatus()).isEqualTo(RentalRequestStatus.CANCELED);
            assertThat(target.getClosedReason()).isNull();
        }
    }

    @Nested
    @DisplayName("소유권")
    class Ownership {

        // 403으로 답하면 그 uuid가 존재한다는 사실을 알려주는 셈이 된다.
        @Test
        @DisplayName("남의 요청은 403이 아니라 404다")
        void other_users_request_is_404() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(77L));
            given(requestRepository.findByUuid(REQUEST_UUID))
                    .willReturn(Optional.of(request(RentalRequestStatus.SUBMITTED)));

            assertThatThrownBy(() -> service.getMine(USER_UUID, REQUEST_UUID))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("RENTAL_REQUEST_NOT_FOUND");
            assertThatThrownBy(() -> service.cancel(USER_UUID, REQUEST_UUID, null))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("RENTAL_REQUEST_NOT_FOUND");
        }

        @Test
        @DisplayName("없는 uuid도 같은 404다")
        void unknown_uuid_is_404() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            given(requestRepository.findByUuid(REQUEST_UUID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMine(USER_UUID, REQUEST_UUID))
                    .extracting(RentalRequestServiceTest::codeOf).isEqualTo("RENTAL_REQUEST_NOT_FOUND");
        }
    }

    // 충돌이 나도 500으로 죽는 것보다 다시 뽑는 편이 낫다.
    @Test
    @DisplayName("업무번호가 충돌하면 다시 뽑는다")
    void request_number_retries_on_collision() {
        givenVerifiedUser();
        givenSelectionsResolve();
        given(requestRepository.save(any())).willAnswer(call -> call.getArgument(0));
        given(businessNumberGenerator.generate(any(), any()))
                .willReturn("RQ-260811-TAKEN1", "RQ-260811-FREE01");
        given(requestRepository.existsByRequestNumber("RQ-260811-TAKEN1")).willReturn(true);
        given(requestRepository.existsByRequestNumber("RQ-260811-FREE01")).willReturn(false);

        service.create(USER_UUID, saveRequest());

        ArgumentCaptor<RentalRequest> captor = ArgumentCaptor.forClass(RentalRequest.class);
        then(requestRepository).should().save(captor.capture());
        assertThat(captor.getValue().getRequestNumber()).isEqualTo("RQ-260811-FREE01");
    }

    @Nested
    @DisplayName("탈퇴 정리")
    class WithdrawalCleanup {

        /*
         * 열린 문의는 전부 닫는다 — 두면 사무소들의 대기 목록에 연락 닿지 않는 행이 남는다.
         *
         * ★ 예전에는 계약이 붙은 문의를 남겨 두었다(닫으면 그 계약 초안을 활성화할 수 없었다).
         *   계약 도메인이 사라져 그 예외의 근거도 사라졌다 — 상담 중인 건도 그대로 닫힌다.
         */
        @Test
        @DisplayName("접수·상담 중인 문의를 모두 사유와 함께 닫는다")
        void cancels_every_open_request() {
            RentalRequest submitted = request(RentalRequestStatus.SUBMITTED);
            RentalRequest inProgress = request(RentalRequestStatus.SUBMITTED);
            inProgress.claim(77L, LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            given(requestRepository.findByUserIdAndStatusIn(ArgumentMatchers.eq(10L), any()))
                    .willReturn(List.of(submitted, inProgress));

            service.cancelOpenOnWithdrawal(10L);

            assertThat(List.of(submitted, inProgress)).allSatisfy(request -> {
                assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.CANCELED);
                assertThat(request.getClosedReason()).isEqualTo("회원이 탈퇴했습니다.");
            });
            // 맡았던 사무소는 남는다 — 그 사무소의 맡은 요청 목록에 "취소됨"으로 보인다.
            assertThat(inProgress.getOfficeId()).isEqualTo(77L);
        }
    }
}
