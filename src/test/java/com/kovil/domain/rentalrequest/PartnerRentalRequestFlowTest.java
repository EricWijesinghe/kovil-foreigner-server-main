package com.kovil.domain.rentalrequest;

import com.kovil.common.exception.CustomException;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.partner.service.AdminPartnerDivisionService;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestSummaryResponse;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.rentalrequest.service.PartnerRentalRequestService;
import com.kovil.domain.rentalrequest.service.RentalRequestService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.AccountBlockService;
import com.kovil.domain.user.service.AdminUserService;
import com.kovil.domain.user.service.UserService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.assertj.core.groups.Tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/*
 * 파트너 임대 요청 처리를 진짜 컨텍스트 · JPA 에 붙여 끝까지 돈다 — 서비스 테스트는 리포지토리를 목킹해 잠금 조회(JPQL)와
 * 서비스 사이의 배선, 유학생 화면과의 짝(휴대폰이 양쪽에서 함께 열리고 함께 닫히는가)을 보지 못한다.
 *   · 잡기 → 양쪽 휴대폰이 열린다 · 다른 사무소는 404 · 409 / 종료 → 양쪽 다 닫힌다 · 알림.
 *   · 놓기 → 다른 사무소가 잡을 수 있다. 상한 → 409.
 *   · 대기 복귀 세 경로(부문 정지 · 대표자 계정 정지 · 대표자 탈퇴) → 대기 + 다시 대기 알림, 복구 · 해제는 되찾지 않는다.
 *   · 잡기 이력: 잡기 · 놓기 · 대기 복귀(원인) · 종료가 한 행씩, 두 번 누른 잡기는 멱등(행 · 알림 없음 · 상한에 막히지 않음).
 * ★ 상한을 1 로 내려 둔다(설정값). 동시 잡기 · 실 MySQL 은 PartnerRentalRequestClaimRaceMySqlTest 가 본다.
 */
@SpringBootTest(properties = "rental-request.max-claims-per-office=1")
@Transactional
class PartnerRentalRequestFlowTest {

    private static final String STUDENT_PHONE = "01000000001";
    private static final String CEO_A_PHONE = "01000000002";

    @Autowired PartnerRentalRequestService partnerService;
    @Autowired RentalRequestService studentService;
    @Autowired AdminPartnerDivisionService divisionService;
    @Autowired AdminUserService adminUserService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired ForeignerVerificationRepository verificationRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired RentalRequestRepository requestRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired RentalRequestClaimEventRepository eventRepository;
    @Autowired Encryptor encryptor;
    @Autowired EntityManager entityManager;

    // 세션 끊기의 액세스 토큰 취소선(Redis)과 정지의 신원 차단 원장(MySQL 전용 INSERT IGNORE)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;
    @MockitoBean AccountBlockService accountBlockService;

    private User student;
    private User admin;
    private Partner a;
    private Partner b;
    private Long verificationId;

    private record Partner(User user, BrokerageOffice office, PartnerDivision division) {
    }

    @BeforeEach
    void setUp() {
        student = user(UserRole.USER, STUDENT_PHONE, "vi");
        admin = user(UserRole.ADMIN, null, "ko");
        verificationId = verificationRepository.save(ForeignerVerification.builder()
                .uuid(UUID.randomUUID().toString()).userId(student.getId())
                .residentStatusId(1L).residentStatusCode("STUDENT").requiredDocumentCodes("[]")
                .nationalityCountryId(7L).fullName("HONG GILDONG").birthDate(LocalDate.of(1998, 3, 15))
                .status(VerificationStatus.APPROVED).build()).getId();
        a = partner("행복공인중개사사무소", CEO_A_PHONE);
        b = partner("소망공인중개사사무소", "01000000003");
    }

    private User user(UserRole role, String phone, String language) {
        String uuid = UUID.randomUUID().toString();
        return userRepository.save(User.builder().uuid(uuid).name("홍길동")
                .nickname(uuid.substring(0, 8)).nicknameNormalized(uuid.substring(0, 8))
                .phone(phone == null ? null : encryptor.encrypt(phone)).phoneHash(UUID.randomUUID().toString())
                .email(uuid.substring(0, 8) + "@example.test").preferredLanguage(language)
                .status(UserStatus.ACTIVE).role(role).build());
    }

    private Partner partner(String officeName, String ceoPhone) {
        User user = user(UserRole.PARTNER, ceoPhone, "ko");
        String regNo = "99999-2026-" + UUID.randomUUID().toString().substring(0, 5);
        BrokerageOffice office = officeRepository.save(BrokerageOffice.builder().uuid(UUID.randomUUID().toString())
                .regNo(regNo).claimedRegNo(regNo).name(officeName).ceoName("홍길동")
                .address("서울특별시 광진구 능동로 000").tel("02-000-0000").build());
        profileRepository.save(BrokerProfile.builder().userId(user.getId()).officeId(office.getId()).build());
        PartnerDivision division = divisionRepository.save(PartnerDivision.builder().uuid(UUID.randomUUID().toString())
                .userId(user.getId()).division(PartnerDivisionType.REAL_ESTATE_BROKER).officeId(office.getId())
                .status(PartnerDivisionStatus.APPROVED).build());
        return new Partner(user, office, division);
    }

    private RentalRequest waiting() {
        return requestRepository.save(RentalRequest.builder()
                .requestNumber("RQ-261007-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                .userId(student.getId()).verificationId(verificationId).status(RentalRequestStatus.SUBMITTED)
                .moveInDate(LocalDate.of(2026, 11, 1)).requestNote("조용한 방이면 좋겠습니다").build());
    }

    // 영속성 컨텍스트를 비우고 DB 에서 다시 읽는다 — 서비스가 커밋할 UPDATE 를 실제로 내보냈는지 본다.
    private RentalRequest reload(RentalRequest request) {
        entityManager.flush();
        entityManager.clear();
        return requestRepository.findById(request.getId()).orElseThrow();
    }

    private List<NotiType> studentNotifications() {
        entityManager.flush();
        return outboxRepository.findAll().stream()
                .filter(row -> row.getUserId().equals(student.getId()))
                .map(NotificationOutbox::getNotiType)
                .toList();
    }

    // 잡기 이력 — (사건, 사무소, 행위자, 원인, 결과).
    // 그 요청의 마지막 놓기 · 대기 복귀 사건 시각(이력에 남은 값 그대로).
    private LocalDateTime releasedAt(RentalRequest request) {
        entityManager.flush();
        return eventRepository.findByRentalRequestIdOrderByIdAsc(request.getId()).stream()
                .filter(e -> e.getEventType() == RentalRequestClaimEventType.RELEASED
                        || e.getEventType() == RentalRequestClaimEventType.RETURNED)
                .reduce((x, y) -> y).orElseThrow().getOccurredAt();
    }

    private List<Tuple> history(RentalRequest request) {
        entityManager.flush();
        return eventRepository.findByRentalRequestIdOrderByIdAsc(request.getId()).stream()
                .map(e -> tuple(e.getEventType(), e.getOfficeId(), e.getActorId(), e.getReturnCause(), e.getOutcome()))
                .toList();
    }

    private static String codeOf(Throwable e) {
        return ((CustomException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("잡으면 양쪽 휴대폰이 열리고, 다른 사무소는 404 · 409, 종료하면 양쪽 다 닫힌다")
    void claim_and_close() {
        RentalRequest request = waiting();

        PartnerRentalRequestSummaryResponse beforeClaim =
                (PartnerRentalRequestSummaryResponse) partnerService.get(b.user().getUuid(), request.getUuid());
        assertThat(beforeClaim.view()).isEqualTo("SUMMARY");
        assertThat(beforeClaim.verified()).isTrue();
        assertThat(beforeClaim.preferredLanguage()).isEqualTo("vi");
        assertThat(partnerService.findPool(b.user().getUuid(), null, PageRequest.of(0, 20)).content())
                .extracting(PartnerRentalRequestSummaryResponse::requestUuid).contains(request.getUuid());

        PartnerRentalRequestResponse claimed = partnerService.claim(a.user().getUuid(), request.getUuid());

        assertThat(claimed.status()).isEqualTo("IN_PROGRESS");
        assertThat(claimed.applicant().phone()).isEqualTo(STUDENT_PHONE);
        assertThat(claimed.requestNote()).isEqualTo("조용한 방이면 좋겠습니다");
        RentalRequestResponse studentView = studentService.getMine(student.getUuid(), request.getUuid());
        assertThat(studentView.office().name()).isEqualTo("행복공인중개사사무소");
        assertThat(studentView.office().ceoPhone()).isEqualTo(CEO_A_PHONE);
        assertThat(studentView.lastReleasedAt()).as("놓인 적 없으면 대기 복귀 시각이 없다").isNull();

        assertThatThrownBy(() -> partnerService.get(b.user().getUuid(), request.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
        assertThatThrownBy(() -> partnerService.claim(b.user().getUuid(), request.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_ALREADY_CLAIMED"));
        assertThat(partnerService.findPool(b.user().getUuid(), null, PageRequest.of(0, 20)).content())
                .extracting(PartnerRentalRequestSummaryResponse::requestUuid).doesNotContain(request.getUuid());
        assertThat(partnerService.findMine(a.user().getUuid(), RentalRequestStatus.IN_PROGRESS, PageRequest.of(0, 20))
                .content()).extracting(PartnerRentalRequestResponse::requestUuid).containsExactly(request.getUuid());

        PartnerRentalRequestResponse closed =
                partnerService.close(a.user().getUuid(), request.getUuid(), RentalRequestOutcome.FOUND);

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.outcome()).isEqualTo("FOUND");
        assertThat(closed.applicant().phone()).as("종료되면 유학생 번호를 가린다").isNull();
        RentalRequestResponse afterClose = studentService.getMine(student.getUuid(), request.getUuid());
        assertThat(afterClose.status()).isEqualTo("CLOSED");
        assertThat(afterClose.office().ceoPhone()).as("종료되면 대표자 번호도 가린다").isNull();
        assertThat(afterClose.lastReleasedAt()).as("종료는 대기 복귀가 아니다").isNull();
        assertThat(reload(request).getOutcome()).isEqualTo(RentalRequestOutcome.FOUND);
        assertThat(studentNotifications())
                .containsExactly(NotiType.RENTAL_REQUEST_IN_PROGRESS, NotiType.RENTAL_REQUEST_CLOSED);
        assertThat(history(request)).as("진 쪽(B 의 409)은 이력을 남기지 않는다").containsExactly(
                tuple(RentalRequestClaimEventType.CLAIMED, a.office().getId(), a.user().getId(), null, null),
                tuple(RentalRequestClaimEventType.CLOSED, a.office().getId(), a.user().getId(), null,
                        RentalRequestOutcome.FOUND));
    }

    /*
     * ★ 두 번 누른 잡기(앱의 재시도 · 손가락) — 이미 내 사무소가 잡은 진행 중 요청이면 멱등 성공이다. 상한을 1 로 내려 둔 이 테스트에서
     *   첫 잡기가 상한을 채우므로, 상한 검사가 먼저였다면 두 번째 누름이 CLAIM_LIMIT 로 거짓 안내를 받는다.
     */
    @Test
    @DisplayName("이미 잡은 요청을 다시 잡으면 같은 전체 응답 — 알림 · 이력 · 개수 변화 없음, 상한에 닿아 있어도 막히지 않는다")
    void double_tap_claim_is_idempotent() {
        RentalRequest request = waiting();
        PartnerRentalRequestResponse first = partnerService.claim(a.user().getUuid(), request.getUuid());
        entityManager.flush();
        entityManager.clear();

        PartnerRentalRequestResponse again = partnerService.claim(a.user().getUuid(), request.getUuid());

        assertThat(again.status()).isEqualTo("IN_PROGRESS");
        assertThat(again.applicant().phone()).isEqualTo(STUDENT_PHONE);
        assertThat(again.claimedAt()).as("잡은 시각이 다시 찍히지 않는다").isEqualTo(reload(request).getClaimedAt())
                .isNotNull();
        assertThat(first.status()).isEqualTo("IN_PROGRESS");
        assertThat(studentNotifications()).containsExactly(NotiType.RENTAL_REQUEST_IN_PROGRESS);
        assertThat(history(request)).hasSize(1);
        assertThat(requestRepository.countByOfficeIdAndStatus(a.office().getId(), RentalRequestStatus.IN_PROGRESS))
                .isEqualTo(1);
        assertThatThrownBy(() -> partnerService.claim(b.user().getUuid(), request.getUuid()))
                .as("다른 사무소에는 여전히 409").satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_ALREADY_CLAIMED"));
    }

    @Test
    @DisplayName("놓으면 다시 대기가 되어 다른 사무소가 잡을 수 있고, 상한(1)을 넘는 잡기는 409 다")
    void release_then_another_office_claims_and_limit_applies() {
        RentalRequest first = waiting();
        RentalRequest second = waiting();

        partnerService.claim(a.user().getUuid(), first.getUuid());
        assertThatThrownBy(() -> partnerService.claim(a.user().getUuid(), second.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_CLAIM_LIMIT"));

        PartnerRentalRequestSummaryResponse released = partnerService.release(a.user().getUuid(), first.getUuid());
        assertThat(released.status()).isEqualTo("SUBMITTED");
        assertThat(reload(first).getOfficeId()).isNull();
        // 유학생 화면 — 다시 대기이고 "맡았던 사무소가 놓았어요" 를 그릴 시각이 있다(상세 · 목록 모두). 사무소는 비어 있다.
        RentalRequestResponse waitingAgain = studentService.getMine(student.getUuid(), first.getUuid());
        assertThat(waitingAgain.status()).isEqualTo("SUBMITTED");
        assertThat(waitingAgain.office()).isNull();
        assertThat(waitingAgain.lastReleasedAt()).isEqualTo(releasedAt(first));
        assertThat(studentService.findMine(student.getUuid(), PageRequest.of(0, 20)).content())
                .filteredOn(r -> r.requestUuid().equals(first.getUuid()))
                .singleElement().extracting(RentalRequestResponse::lastReleasedAt).isEqualTo(releasedAt(first));
        assertThat(studentService.getMine(student.getUuid(), second.getUuid()).lastReleasedAt()).isNull();

        PartnerRentalRequestResponse retaken = partnerService.claim(b.user().getUuid(), first.getUuid());
        assertThat(retaken.status()).isEqualTo("IN_PROGRESS");
        assertThat(studentService.getMine(student.getUuid(), first.getUuid()).lastReleasedAt())
                .as("다시 맡아도 값은 남는다 — 앱은 SUBMITTED 일 때만 그린다").isEqualTo(releasedAt(first));
        assertThat(reload(first).getOfficeId()).isEqualTo(b.office().getId());
        assertThat(studentNotifications()).containsExactly(NotiType.RENTAL_REQUEST_IN_PROGRESS,
                NotiType.RENTAL_REQUEST_RELEASED, NotiType.RENTAL_REQUEST_IN_PROGRESS);
        assertThat(history(first)).as("잡았다 놓은 사무소가 이력에 남는다").containsExactly(
                tuple(RentalRequestClaimEventType.CLAIMED, a.office().getId(), a.user().getId(), null, null),
                tuple(RentalRequestClaimEventType.RELEASED, a.office().getId(), a.user().getId(), null, null),
                tuple(RentalRequestClaimEventType.CLAIMED, b.office().getId(), b.user().getId(), null, null));
        assertThat(history(second)).as("상한(409)에 막힌 잡기는 남지 않는다").isEmpty();
    }

    @Test
    @DisplayName("정지된 신청자의 요청은 대기 목록에도 상세에도 없고 잡을 수도 없다")
    void suspended_applicant_is_out_of_reach() {
        RentalRequest request = waiting();
        adminUserService.suspend(admin.getUuid(), student.getUuid(), "센티널 사유");

        assertThat(partnerService.findPool(a.user().getUuid(), null, PageRequest.of(0, 20)).content()).isEmpty();
        assertThatThrownBy(() -> partnerService.get(a.user().getUuid(), request.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
        assertThatThrownBy(() -> partnerService.claim(a.user().getUuid(), request.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
        assertThat(reload(request).getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
    }

    @Test
    @DisplayName("부문 정지 → 맡은 요청이 대기로 돌아가고 다시 대기 알림이 간다 · 복구해도 되찾지 않는다 · 낡은 토큰의 잡기는 403")
    void division_suspension_returns_requests() {
        RentalRequest request = waiting();
        partnerService.claim(a.user().getUuid(), request.getUuid());

        divisionService.suspend(admin.getUuid(), a.division().getUuid(), "허위 매물");

        RentalRequest returned = reload(request);
        assertThat(returned.getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
        assertThat(studentService.getMine(student.getUuid(), request.getUuid()).lastReleasedAt())
                .as("정지로 대기로 돌아간 것도 대기 복귀다").isEqualTo(releasedAt(request));
        assertThat(returned.getOfficeId()).isNull();
        assertThat(returned.getClaimedAt()).isNull();
        assertThat(studentNotifications())
                .containsExactly(NotiType.RENTAL_REQUEST_IN_PROGRESS, NotiType.RENTAL_REQUEST_RELEASED);
        assertThat(history(request)).containsExactly(
                tuple(RentalRequestClaimEventType.CLAIMED, a.office().getId(), a.user().getId(), null, null),
                tuple(RentalRequestClaimEventType.RETURNED, a.office().getId(), null,
                        RentalRequestReturnCause.DIVISION_SUSPENDED, null));
        assertThatThrownBy(() -> partnerService.claim(a.user().getUuid(), request.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("RENTAL_REQUEST_OFFICE_NOT_ACTIVE"));

        divisionService.reinstate(admin.getUuid(), a.division().getUuid());

        assertThat(reload(request).getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
        assertThat(reload(request).getOfficeId()).isNull();
    }

    @Test
    @DisplayName("대표자 계정 정지 → 맡은 요청이 대기로 돌아간다 · 정지를 풀어도 되찾지 않는다 · 낡은 토큰의 잡기는 403")
    void account_suspension_returns_requests() {
        RentalRequest request = waiting();
        partnerService.claim(a.user().getUuid(), request.getUuid());

        adminUserService.suspend(admin.getUuid(), a.user().getUuid(), "센티널 사유");

        assertThat(reload(request).getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
        assertThat(studentNotifications())
                .containsExactly(NotiType.RENTAL_REQUEST_IN_PROGRESS, NotiType.RENTAL_REQUEST_RELEASED);
        assertThat(history(request)).containsExactly(
                tuple(RentalRequestClaimEventType.CLAIMED, a.office().getId(), a.user().getId(), null, null),
                tuple(RentalRequestClaimEventType.RETURNED, a.office().getId(), null,
                        RentalRequestReturnCause.ACCOUNT_SUSPENDED, null));
        assertThatThrownBy(() -> partnerService.release(a.user().getUuid(), request.getUuid()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("USER_SUSPENDED"));

        adminUserService.unsuspend(admin.getUuid(), a.user().getUuid());

        assertThat(reload(request).getOfficeId()).isNull();
    }

    @Test
    @DisplayName("대표자 탈퇴 → 맡은 요청이 대기로 돌아가 다른 사무소가 잡을 수 있다")
    void withdrawal_returns_requests() {
        RentalRequest request = waiting();
        partnerService.claim(a.user().getUuid(), request.getUuid());

        userService.withdraw(a.user().getUuid());

        assertThat(reload(request).getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
        assertThat(studentNotifications())
                .containsExactly(NotiType.RENTAL_REQUEST_IN_PROGRESS, NotiType.RENTAL_REQUEST_RELEASED);
        assertThat(partnerService.claim(b.user().getUuid(), request.getUuid()).status()).isEqualTo("IN_PROGRESS");
        assertThat(history(request)).containsExactly(
                tuple(RentalRequestClaimEventType.CLAIMED, a.office().getId(), a.user().getId(), null, null),
                tuple(RentalRequestClaimEventType.RETURNED, a.office().getId(), null,
                        RentalRequestReturnCause.REPRESENTATIVE_WITHDRAWN, null),
                tuple(RentalRequestClaimEventType.CLAIMED, b.office().getId(), b.user().getId(), null, null));
    }

    // 대기 복귀는 그 사무소의 **진행 중**만이다 — 종료 · 취소된 건은 기록으로 그 사무소에 남는다.
    @Test
    @DisplayName("대기 복귀는 진행 중만 — 이미 종료한 요청은 그 사무소에 남는다")
    void closed_requests_stay_with_the_office() {
        RentalRequest request = waiting();
        partnerService.claim(a.user().getUuid(), request.getUuid());
        partnerService.close(a.user().getUuid(), request.getUuid(), RentalRequestOutcome.NOT_PROCEEDED);

        divisionService.suspend(admin.getUuid(), a.division().getUuid(), "허위 매물");

        RentalRequest after = reload(request);
        assertThat(after.getStatus()).isEqualTo(RentalRequestStatus.CLOSED);
        assertThat(after.getOfficeId()).isEqualTo(a.office().getId());
        assertThat(history(request)).as("종료된 요청에는 대기 복귀가 붙지 않는다").extracting(t -> t.toList().get(0))
                .containsExactly(RentalRequestClaimEventType.CLAIMED, RentalRequestClaimEventType.CLOSED);
    }
}
