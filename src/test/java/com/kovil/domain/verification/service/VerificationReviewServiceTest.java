package com.kovil.domain.verification.service;

import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.dto.response.AdminVerificationResponse;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.user.exception.UserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Nested;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class VerificationReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, SEOUL);

    private static final String REVIEWER_UUID = "reviewer-uuid";
    private static final String VERIFICATION_UUID = "verification-uuid";

    @Mock UserRepository userRepository;
    @Mock UserQueryService userQueryService;
    @Mock CountryRepository countryRepository;
    @Mock StoredFileRepository storedFileRepository;
    @Mock ForeignerVerificationRepository verificationRepository;
    @Mock ForeignerVerificationDocumentRepository documentRepository;
    @Mock NotificationPublisher notificationPublisher;

    private VerificationReviewService service;

    @BeforeEach
    void setUp() {
        service = new VerificationReviewService(userRepository, userQueryService,
                countryRepository, storedFileRepository, verificationRepository, documentRepository,
                JsonMapper.builder().build(), notificationPublisher, Clock.fixed(NOW, SEOUL));
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

    private static User user(Long id, String uuid, UserRole role) {
        return withId(User.builder()
                .uuid(uuid)
                .name("holder")
                .status(UserStatus.ACTIVE)
                .role(role)
                .build(), id);
    }

    private ForeignerVerification pending() {
        return withId(ForeignerVerification.builder()
                .uuid(VERIFICATION_UUID)
                .userId(10L)
                .residentStatusId(1L)
                .residentStatusCode("STUDENT")
                .requiredDocumentCodes("[\"PASSPORT\",\"ARC\"]")
                .nationalityCountryId(7L)
                .fullName("NGUYEN VAN A")
                .birthDate(LocalDate.of(1998, 3, 15))
                .status(VerificationStatus.PENDING_REVIEW)
                .build(), 100L);
    }

    private void givenReviewerAndVerification(ForeignerVerification verification) {
        given(userQueryService.getActiveUser(REVIEWER_UUID)).willReturn(user(1L, REVIEWER_UUID, UserRole.ADMIN));
        given(verificationRepository.findByUuid(VERIFICATION_UUID)).willReturn(Optional.of(verification));
        given(documentRepository.findByVerificationIdOrderBySortOrderAscIdAsc(100L)).willReturn(List.of());
    }

    @Test
    @DisplayName("승인하면 APPROVED로 바뀌고 심사자·시각이 남는다")
    void approve() {
        ForeignerVerification verification = pending();
        givenReviewerAndVerification(verification);
        given(userRepository.findById(10L)).willReturn(Optional.of(user(10L, "applicant", UserRole.USER)));

        AdminVerificationResponse response =
                service.approve(REVIEWER_UUID, VERIFICATION_UUID, TODAY.plusYears(1));

        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(verification.getReviewedBy()).isEqualTo(1L);
        assertThat(verification.getReviewedAt()).isNotNull();
        assertThat(verification.getExpiresAt()).isEqualTo(TODAY.plusYears(1));
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    // FR-210 — 자기 신고 국적이 아니라 사람이 확인한 값이 계정의 정본이 된다.
    @Test
    @DisplayName("승인 시 신청자의 국적이 심사값으로 확정된다")
    void approveFixesNationality() {
        ForeignerVerification verification = pending();
        givenReviewerAndVerification(verification);
        User applicant = user(10L, "applicant", UserRole.USER);
        given(userRepository.findById(10L)).willReturn(Optional.of(applicant));

        service.approve(REVIEWER_UUID, VERIFICATION_UUID, null);

        assertThat(applicant.getNationalityCountryId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("만료일이 없으면 무기한 승인이다")
    void approveWithoutExpiry() {
        ForeignerVerification verification = pending();
        givenReviewerAndVerification(verification);
        given(userRepository.findById(10L)).willReturn(Optional.of(user(10L, "applicant", UserRole.USER)));

        service.approve(REVIEWER_UUID, VERIFICATION_UUID, null);

        assertThat(verification.getExpiresAt()).isNull();
        assertThat(verification.isEffectiveOn(TODAY.plusYears(50))).isTrue();
    }

    // 지난 날짜로 승인하면 승인과 동시에 만료된 인증이 된다 — 관리자는 승인했다고 믿는다.
    @Test
    @DisplayName("지난 만료일로는 승인할 수 없고, 검증 전에 아무것도 바뀌지 않는다")
    void approveRejectsPastExpiry() {
        assertThatThrownBy(() -> service.approve(REVIEWER_UUID, VERIFICATION_UUID, TODAY.minusDays(1)))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_EXPIRY_IN_PAST");

        verifyNoInteractions(verificationRepository, userRepository);
    }

    @Test
    @DisplayName("오늘로 만료일을 잡는 것은 허용된다 — 오늘까지는 유효하다")
    void approveAllowsTodayExpiry() {
        ForeignerVerification verification = pending();
        givenReviewerAndVerification(verification);
        given(userRepository.findById(10L)).willReturn(Optional.of(user(10L, "applicant", UserRole.USER)));

        service.approve(REVIEWER_UUID, VERIFICATION_UUID, TODAY);

        assertThat(verification.isEffectiveOn(TODAY)).isTrue();
        assertThat(verification.isEffectiveOn(TODAY.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("반려하면 사유가 남고 만료일은 비워진다")
    void reject() {
        ForeignerVerification verification = pending();
        givenReviewerAndVerification(verification);

        AdminVerificationResponse response =
                service.reject(REVIEWER_UUID, VERIFICATION_UUID, "  여권 사진이 흐립니다  ");

        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(verification.getRejectReason()).isEqualTo("여권 사진이 흐립니다");
        assertThat(verification.getExpiresAt()).isNull();
        assertThat(response.rejectReason()).isEqualTo("여권 사진이 흐립니다");
    }

    // 반려는 국적을 확정하지 않는다 — 확인되지 않은 값이다.
    @Test
    @DisplayName("반려는 신청자의 국적을 건드리지 않는다")
    void rejectDoesNotTouchNationality() {
        givenReviewerAndVerification(pending());
        User applicant = user(10L, "applicant", UserRole.USER);
        given(userRepository.findById(10L)).willReturn(Optional.of(applicant));

        service.reject(REVIEWER_UUID, VERIFICATION_UUID, "서류 불명확");

        assertThat(applicant.getNationalityCountryId()).isNull();
    }

    @Test
    @DisplayName("이미 승인된 건은 다시 심사할 수 없다")
    void approveTwiceFails() {
        ForeignerVerification verification = pending();
        verification.approve(1L, null, LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
        given(userQueryService.getActiveUser(REVIEWER_UUID)).willReturn(user(1L, REVIEWER_UUID, UserRole.ADMIN));
        given(verificationRepository.findByUuid(VERIFICATION_UUID)).willReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.reject(REVIEWER_UUID, VERIFICATION_UUID, "뒤늦은 반려"))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_NOT_PENDING");

        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(verification.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("이미 반려된 건은 승인으로 뒤집을 수 없다")
    void rejectThenApproveFails() {
        ForeignerVerification verification = pending();
        verification.reject(1L, "사유", LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
        given(userQueryService.getActiveUser(REVIEWER_UUID)).willReturn(user(1L, REVIEWER_UUID, UserRole.ADMIN));
        given(verificationRepository.findByUuid(VERIFICATION_UUID)).willReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.approve(REVIEWER_UUID, VERIFICATION_UUID, null))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_NOT_PENDING");

        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("없는 신청은 찾을 수 없다")
    void notFound() {
        given(userQueryService.getActiveUser(REVIEWER_UUID)).willReturn(user(1L, REVIEWER_UUID, UserRole.ADMIN));
        given(verificationRepository.findByUuid(VERIFICATION_UUID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(REVIEWER_UUID, VERIFICATION_UUID, null))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_NOT_FOUND");
    }

    @Test
    @DisplayName("대기열은 제출 시점 스냅샷과 국적 코드를 함께 준다")
    void queue() {
        ForeignerVerification verification = pending();
        given(verificationRepository.findQueue(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(verification)));
        given(documentRepository.findByVerificationIdOrderBySortOrderAscIdAsc(100L)).willReturn(List.of());
        given(userRepository.findById(10L)).willReturn(Optional.of(user(10L, "applicant", UserRole.USER)));
        given(countryRepository.findById(7L)).willReturn(Optional.of(Country.builder()
                .code("vn").nameEn("Vietnam").build()));

        var page = service.findByStatus(VerificationStatus.PENDING_REVIEW, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        AdminVerificationResponse first = page.content().get(0);
        assertThat(first.userUuid()).isEqualTo("applicant");
        assertThat(first.nationalityCountryCode()).isEqualTo("vn");
        assertThat(first.residentStatusCode()).isEqualTo("STUDENT");
        assertThat(first.requiredDocumentCodes()).containsExactly("PASSPORT", "ARC");
    }

    // 스냅샷 한 줄이 깨졌다고 심사 화면 전체가 죽으면 대기열을 아무도 못 본다.
    @Test
    @DisplayName("깨진 스냅샷은 그 항목만 비우고 나머지는 그대로 응답한다")
    void queueSurvivesBrokenSnapshot() {
        ForeignerVerification broken = withId(ForeignerVerification.builder()
                .uuid(VERIFICATION_UUID).userId(10L).residentStatusId(1L)
                .residentStatusCode("STUDENT").requiredDocumentCodes("not-json")
                .nationalityCountryId(7L).fullName("NGUYEN VAN A")
                .birthDate(LocalDate.of(1998, 3, 15))
                .status(VerificationStatus.PENDING_REVIEW).build(), 100L);
        given(verificationRepository.findQueue(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(broken)));
        given(documentRepository.findByVerificationIdOrderBySortOrderAscIdAsc(100L)).willReturn(List.of());
        given(userRepository.findById(10L)).willReturn(Optional.of(user(10L, "applicant", UserRole.USER)));
        given(countryRepository.findById(7L)).willReturn(Optional.empty());

        var page = service.findByStatus(VerificationStatus.PENDING_REVIEW, null, PageRequest.of(0, 20));

        assertThat(page.content().get(0).requiredDocumentCodes()).isEmpty();
        assertThat(page.content().get(0).fullName()).isEqualTo("NGUYEN VAN A");
    }

    // ★ 오승인을 되돌릴 수단이 없으면 잘못 승인된 사람이 임대 요청 게이트를 계속 통과한다.
    @Nested
    @DisplayName("승인 무효화")
    class Revoke {

        private ForeignerVerification approved() {
            ForeignerVerification verification = pending();
            verification.approve(1L, null, LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            return verification;
        }

        @Test
        @DisplayName("무효화하면 REVOKED가 되고 그 즉시 미인증으로 취급된다")
        void revoke_makes_it_ineffective() {
            ForeignerVerification target = approved();
            givenReviewerAndVerification(target);
            given(userRepository.findById(any())).willReturn(Optional.empty());

            service.revoke(REVIEWER_UUID, VERIFICATION_UUID, " 재직증명서 위조 확인 ");

            assertThat(target.getStatus()).isEqualTo(VerificationStatus.REVOKED);
            assertThat(target.isEffectiveOn(TODAY)).isFalse();
            assertThat(target.getRevokeReason()).isEqualTo("재직증명서 위조 확인");
            assertThat(target.getRevokedBy()).isEqualTo(1L);
        }

        // 덮어쓰면 원래 누가 승인했는지가 사라져 무효화 자체를 감사할 수 없다.
        @Test
        @DisplayName("승인 이력은 지워지지 않는다")
        void approval_trace_survives() {
            ForeignerVerification target = approved();
            givenReviewerAndVerification(target);
            given(userRepository.findById(any())).willReturn(Optional.empty());

            service.revoke(REVIEWER_UUID, VERIFICATION_UUID, "사유");

            assertThat(target.getReviewedBy()).isEqualTo(1L);
            assertThat(target.getReviewedAt()).isNotNull();
        }

        @Test
        @DisplayName("승인되지 않은 건은 무효화 대상이 아니다")
        void only_approved_can_be_revoked() {
            ForeignerVerification target = pending();
            given(userQueryService.getActiveUser(REVIEWER_UUID))
                    .willReturn(user(1L, REVIEWER_UUID, UserRole.ADMIN));
            given(verificationRepository.findByUuid(VERIFICATION_UUID)).willReturn(Optional.of(target));

            assertThatThrownBy(() -> service.revoke(REVIEWER_UUID, VERIFICATION_UUID, "사유"))
                    .isInstanceOf(VerificationException.class)
                    .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_NOT_APPROVED");
        }
    }

    /*
     * ★ 이력 조회는 읽기 경로다 — 탈퇴자도 찾아야 한다. 회원 목록이 status=WITHDRAWN 을
     *   보여주는데 같은 상세 화면의 인증 이력만 404 면 화면이 반쪽으로 깨지고, 그 404 가
     *   VERIFICATION_NOT_FOUND 면 관리자가 "신청 이력 없음"으로 오판한다(적대적 리뷰 2026-09-02).
     *   deletedAt 필터 조회로 되돌리면 이 테스트가 죽는다.
     */
    @Nested
    @DisplayName("인증 이력 조회")
    class FindByUser {

        @Test
        @DisplayName("탈퇴자의 이력도 조회된다 — deletedAt 을 거르지 않는다")
        void withdrawn_user_history_is_readable() {
            User gone = user(77L, "gone-uuid", UserRole.USER);
            gone.withdraw(LocalDateTime.of(2026, 9, 2, 10, 0));
            given(userRepository.findByUuid("gone-uuid")).willReturn(Optional.of(gone));
            given(verificationRepository.findByUserIdOrderByIdDesc(77L)).willReturn(List.of());

            assertThat(service.findByUser("gone-uuid")).isEmpty();
        }

        @Test
        @DisplayName("유저 부재는 USER_NOT_FOUND 다 — 신청 이력 없음으로 오판하게 하지 않는다")
        void missing_user_is_user_not_found() {
            given(userRepository.findByUuid("nobody")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.findByUser("nobody"))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e)
                            .getErrorCode().getCode())
                    .isEqualTo("USER_NOT_FOUND");
        }
    }
}
