package com.kovil.domain.rentalrequest;

import com.kovil.common.exception.CustomException;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.partner.service.AdminPartnerDivisionService;
import com.kovil.domain.rentalrequest.dto.response.AdminRentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.rentalrequest.service.AdminRentalRequestService;
import com.kovil.domain.rentalrequest.service.PartnerRentalRequestService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/*
 * 관리자 열람(읽기 전용)을 진짜 컨텍스트 · JPA 에 붙여 사무소의 잡기 · 놓기 · 종료와 짝지어 본다 — 목 리포지토리로는
 * 사무소 필터 · 검색 JPQL 과 응답 조립 사이의 배선, "놓으면 사무소가 비워진다" 같은 상태 반영을 보지 못한다.
 * ★ 전화번호는 직렬화된 JSON 어디에도 없어야 한다 — 칸으로도 값으로도(신청자 · 대표자 둘 다).
 * ★ 잡기 이력(claimHistory)은 상세에만 있다 — 잡기 · 놓기 · 대기 복귀 · 종료가 일어난 순서대로, 목록 줄에는 칸째 없다.
 */
@SpringBootTest
@Transactional
class AdminRentalRequestFlowTest {

    private static final String STUDENT_PHONE = "01000000011";
    private static final String CEO_A_PHONE = "01000000012";
    private static final String CEO_B_PHONE = "01000000013";
    private static final String PASSPORT_NAME = "NGUYEN VAN A";

    @Autowired AdminRentalRequestService adminService;
    @Autowired PartnerRentalRequestService partnerService;
    @Autowired AdminPartnerDivisionService divisionService;
    @Autowired AdminUserService adminUserService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired ForeignerVerificationRepository verificationRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired RentalRequestRepository requestRepository;
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
        student = user(UserRole.USER, "Andy", STUDENT_PHONE);
        admin = user(UserRole.ADMIN, "관리자", null);
        verificationId = verificationRepository.save(ForeignerVerification.builder()
                .uuid(UUID.randomUUID().toString()).userId(student.getId())
                .residentStatusId(1L).residentStatusCode("STUDENT").requiredDocumentCodes("[]")
                .nationalityCountryId(7L).fullName(PASSPORT_NAME).birthDate(LocalDate.of(1998, 3, 15))
                .status(VerificationStatus.APPROVED).build()).getId();
        a = partner("행복공인중개사사무소", CEO_A_PHONE);
        b = partner("소망공인중개사사무소", CEO_B_PHONE);
    }

    private User user(UserRole role, String name, String phone) {
        String uuid = UUID.randomUUID().toString();
        return userRepository.save(User.builder().uuid(uuid).name(name)
                .nickname(uuid.substring(0, 8)).nicknameNormalized(uuid.substring(0, 8))
                .phone(phone == null ? null : encryptor.encrypt(phone)).phoneHash(UUID.randomUUID().toString())
                .email(uuid.substring(0, 8) + "@example.test").preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(role).build());
    }

    private Partner partner(String officeName, String ceoPhone) {
        User user = user(UserRole.PARTNER, "홍길동", ceoPhone);
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

    private RentalRequest waiting(String number) {
        return requestRepository.save(RentalRequest.builder()
                .requestNumber(number)
                .userId(student.getId()).verificationId(verificationId).status(RentalRequestStatus.SUBMITTED)
                .moveInDate(LocalDate.of(2026, 11, 1)).requestNote("조용한 방이면 좋겠습니다").build());
    }

    // 서비스가 커밋할 UPDATE 를 내보낸 뒤 영속성 컨텍스트를 비운다 — 관리자 조회가 DB 에서 다시 읽게 한다.
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> numbers(RentalRequestStatus status, String officeUuid, String query) {
        flushAndClear();
        return adminService.search(status, officeUuid, query, PageRequest.of(0, 20)).content().stream()
                .map(AdminRentalRequestResponse::requestNumber).toList();
    }

    private AdminRentalRequestResponse detail(RentalRequest request) {
        flushAndClear();
        return adminService.get(request.getUuid());
    }

    @Test
    @DisplayName("대기 → 잡기 → 종료를 따라 사무소 · 잡은 시각 · 결과가 붙고, 신청자는 uuid · 이름 · 계정 상태다")
    void follows_claim_and_close() {
        RentalRequest request = waiting("RQ-261007-AAAAA1");

        AdminRentalRequestResponse submitted = detail(request);
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.office()).isNull();
        assertThat(submitted.claimedAt()).isNull();
        assertThat(submitted.outcome()).isNull();
        assertThat(submitted.requestNote()).isEqualTo("조용한 방이면 좋겠습니다");
        assertThat(submitted.applicant().userUuid()).isEqualTo(student.getUuid());
        assertThat(submitted.applicant().name()).isEqualTo("Andy");
        assertThat(submitted.applicant().accountStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(submitted.claimHistory()).as("잡힌 적이 없으면 빈 이력").isEmpty();

        partnerService.claim(a.user().getUuid(), request.getUuid());

        AdminRentalRequestResponse claimed = detail(request);
        assertThat(claimed.status()).isEqualTo("IN_PROGRESS");
        assertThat(claimed.office().officeUuid()).isEqualTo(a.office().getUuid());
        assertThat(claimed.office().name()).isEqualTo("행복공인중개사사무소");
        assertThat(claimed.office().regNo()).isEqualTo(a.office().getRegNo());
        assertThat(claimed.claimedAt()).isNotNull();
        assertThat(claimed.closedAt()).isNull();

        partnerService.close(a.user().getUuid(), request.getUuid(), RentalRequestOutcome.FOUND);

        AdminRentalRequestResponse closed = detail(request);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.outcome()).as("유학생 화면과 달리 관리자는 결과를 본다").isEqualTo("FOUND");
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.office().officeUuid()).as("종료해도 맡았던 사무소는 남는다").isEqualTo(a.office().getUuid());
        assertThat(closed.claimHistory())
                .extracting(AdminRentalRequestResponse.ClaimEvent::type, AdminRentalRequestResponse.ClaimEvent::officeUuid,
                        AdminRentalRequestResponse.ClaimEvent::officeName, AdminRentalRequestResponse.ClaimEvent::returnCause,
                        AdminRentalRequestResponse.ClaimEvent::outcome)
                .containsExactly(
                        tuple("CLAIMED", a.office().getUuid(), "행복공인중개사사무소", null, null),
                        tuple("CLOSED", a.office().getUuid(), "행복공인중개사사무소", null, "FOUND"));
        assertThat(closed.claimHistory().get(0).occurredAt()).isEqualTo(closed.claimedAt());
        assertThat(closed.claimHistory().get(1).occurredAt()).isEqualTo(closed.closedAt());
    }

    @Test
    @DisplayName("놓으면 사무소가 비워지고 그 사무소 필터에서 빠진다 — 다른 사무소가 잡으면 그쪽으로 옮겨 간다")
    void release_moves_the_request_out_of_the_office() {
        RentalRequest request = waiting("RQ-261007-AAAAA2");
        partnerService.claim(a.user().getUuid(), request.getUuid());
        assertThat(numbers(null, a.office().getUuid(), null)).containsExactly("RQ-261007-AAAAA2");

        partnerService.release(a.user().getUuid(), request.getUuid());

        AdminRentalRequestResponse released = detail(request);
        assertThat(released.status()).isEqualTo("SUBMITTED");
        assertThat(released.office()).isNull();
        assertThat(released.claimedAt()).isNull();
        assertThat(numbers(null, a.office().getUuid(), null)).isEmpty();

        partnerService.claim(b.user().getUuid(), request.getUuid());

        assertThat(numbers(null, b.office().getUuid(), null)).containsExactly("RQ-261007-AAAAA2");
        AdminRentalRequestResponse retaken = detail(request);
        assertThat(retaken.office().name()).isEqualTo("소망공인중개사사무소");
        assertThat(retaken.claimHistory()).as("놓아서 비워진 사무소도 이력에는 남는다")
                .extracting(AdminRentalRequestResponse.ClaimEvent::type, AdminRentalRequestResponse.ClaimEvent::officeName)
                .containsExactly(tuple("CLAIMED", "행복공인중개사사무소"), tuple("RELEASED", "행복공인중개사사무소"),
                        tuple("CLAIMED", "소망공인중개사사무소"));
    }

    @Test
    @DisplayName("부문 정지로 대기로 돌아간 요청은 사무소가 비워진 대기 건으로 보인다")
    void division_suspension_is_visible_as_waiting() {
        RentalRequest request = waiting("RQ-261007-AAAAA3");
        partnerService.claim(a.user().getUuid(), request.getUuid());

        divisionService.suspend(admin.getUuid(), a.division().getUuid(), "허위 매물");

        AdminRentalRequestResponse returned = detail(request);
        assertThat(returned.status()).isEqualTo("SUBMITTED");
        assertThat(returned.office()).isNull();
        assertThat(returned.claimHistory())
                .extracting(AdminRentalRequestResponse.ClaimEvent::type, AdminRentalRequestResponse.ClaimEvent::officeUuid,
                        AdminRentalRequestResponse.ClaimEvent::returnCause)
                .containsExactly(tuple("CLAIMED", a.office().getUuid(), null),
                        tuple("RETURNED", a.office().getUuid(), "DIVISION_SUSPENDED"));
        assertThat(numbers(RentalRequestStatus.SUBMITTED, null, null)).containsExactly("RQ-261007-AAAAA3");
    }

    @Test
    @DisplayName("상태 · 사무소 필터 · 최신순 — 없는 사무소 uuid 는 전체가 아니라 빈 목록이다")
    void filters_and_newest_first() {
        RentalRequest first = waiting("RQ-261007-BBBBB1");
        waiting("RQ-261007-BBBBB2");
        RentalRequest third = waiting("RQ-261007-BBBBB3");
        partnerService.claim(a.user().getUuid(), first.getUuid());
        partnerService.claim(b.user().getUuid(), third.getUuid());

        assertThat(numbers(null, null, null))
                .containsExactly("RQ-261007-BBBBB3", "RQ-261007-BBBBB2", "RQ-261007-BBBBB1");
        assertThat(numbers(RentalRequestStatus.IN_PROGRESS, null, null))
                .containsExactly("RQ-261007-BBBBB3", "RQ-261007-BBBBB1");
        assertThat(numbers(RentalRequestStatus.SUBMITTED, null, null)).containsExactly("RQ-261007-BBBBB2");
        assertThat(numbers(null, a.office().getUuid(), null)).containsExactly("RQ-261007-BBBBB1");
        assertThat(numbers(RentalRequestStatus.CLOSED, b.office().getUuid(), null)).isEmpty();
        assertThat(numbers(null, "  " + b.office().getUuid() + " ", null))
                .as("앞뒤 공백은 지운다").containsExactly("RQ-261007-BBBBB3");
        assertThat(numbers(null, UUID.randomUUID().toString(), null)).as("없는 사무소").isEmpty();
        assertThat(numbers(null, " ", " ")).as("빈 값은 걸지 않는다").hasSize(3);
    }

    @Test
    @DisplayName("검색어는 요청번호 · 회원 이름 · 여권 성명의 부분일치다(대소문자 무시)")
    void query_matches_number_and_names() {
        waiting("RQ-261007-CCCCC1");
        User other = user(UserRole.USER, null, "01000000014");
        Long otherVerification = verificationRepository.save(ForeignerVerification.builder()
                .uuid(UUID.randomUUID().toString()).userId(other.getId())
                .residentStatusId(1L).residentStatusCode("STUDENT").requiredDocumentCodes("[]")
                .nationalityCountryId(7L).fullName("TRAN THI B").birthDate(LocalDate.of(1999, 1, 1))
                .status(VerificationStatus.APPROVED).build()).getId();
        requestRepository.save(RentalRequest.builder().requestNumber("RQ-261007-DDDDD1")
                .userId(other.getId()).verificationId(otherVerification).status(RentalRequestStatus.SUBMITTED)
                .moveInDate(LocalDate.of(2026, 11, 1)).build());

        assertThat(numbers(null, null, "ccccc1")).containsExactly("RQ-261007-CCCCC1");
        assertThat(numbers(null, null, " andy ")).containsExactly("RQ-261007-CCCCC1");
        assertThat(numbers(null, null, "tran")).as("회원 이름이 없어도 여권 성명으로 잡힌다")
                .containsExactly("RQ-261007-DDDDD1");
        assertThat(numbers(null, null, "없는값")).isEmpty();
    }

    @Test
    @DisplayName("정지 · 탈퇴한 신청자의 요청도 계정 상태와 함께 보인다(탈퇴하면 이름이 지워진다)")
    void applicant_account_status() {
        RentalRequest request = waiting("RQ-261007-EEEEE1");

        adminUserService.suspend(admin.getUuid(), student.getUuid(), "센티널 사유");
        assertThat(detail(request).applicant().accountStatus()).isEqualTo(UserStatus.SUSPENDED);

        adminUserService.unsuspend(admin.getUuid(), student.getUuid());
        userService.withdraw(student.getUuid());

        AdminRentalRequestResponse after = detail(request);
        assertThat(after.applicant().accountStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(after.applicant().name()).isNull();
        assertThat(after.status()).as("탈퇴는 열린 요청을 취소한다").isEqualTo("CANCELED");
    }

    /*
     * ★ 직렬화 수준에서 본다 — 진행 중이라 유학생 · 사무소 화면에서는 양쪽 휴대폰이 열려 있는 순간이다.
     *   관리자 응답의 키가 정확히 계약의 목록인지, 어느 번호도 값으로 섞이지 않았는지 함께 본다.
     */
    @Test
    @DisplayName("직렬화된 응답에는 전화 칸이 없고, 진행 중이어도 신청자 · 대표자 번호가 값으로도 없다")
    void serialized_response_has_no_phone() {
        RentalRequest request = waiting("RQ-261007-FFFFF1");
        partnerService.claim(a.user().getUuid(), request.getUuid());

        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(detail(request));
        JsonNode tree = mapper.readTree(json);

        assertThat(keysOf(tree)).containsExactlyInAnyOrder(
                "requestUuid", "requestNumber", "status", "regions", "options",
                "moveInDate", "moveInFlexible", "leaseMonths", "depositMin", "depositMax",
                "monthlyRentMin", "monthlyRentMax", "householdSize", "preferredAreaNote",
                "requestNote", "closedReason", "submittedAt", "claimedAt", "closedAt", "outcome",
                "applicant", "office", "claimHistory");
        assertThat(keysOf(tree.get("applicant"))).containsExactlyInAnyOrder("userUuid", "name", "accountStatus");
        assertThat(keysOf(tree.get("office"))).containsExactlyInAnyOrder("officeUuid", "name", "regNo");
        assertThat(keysOf(tree.get("claimHistory").get(0))).containsExactlyInAnyOrder(
                "type", "officeUuid", "officeName", "occurredAt", "returnCause", "outcome");
        assertThat(json).doesNotContain(STUDENT_PHONE, CEO_A_PHONE, "hone", PASSPORT_NAME);

        String page = mapper.writeValueAsString(adminService.search(null, null, null, PageRequest.of(0, 20)));
        assertThat(page).contains("RQ-261007-FFFFF1").doesNotContain(STUDENT_PHONE, CEO_A_PHONE, "hone");
        assertThat(page).as("잡기 이력은 상세에만 — 목록 줄에는 칸째 없다").doesNotContain("claimHistory");
    }

    @Test
    @DisplayName("없는 요청은 404 다")
    void unknown_request_is_not_found() {
        assertThatThrownBy(() -> adminService.get(UUID.randomUUID().toString()))
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getCode())
                        .isEqualTo("RENTAL_REQUEST_NOT_FOUND"));
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}
