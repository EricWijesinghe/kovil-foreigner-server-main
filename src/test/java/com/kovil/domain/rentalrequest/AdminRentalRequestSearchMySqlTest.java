package com.kovil.domain.rentalrequest;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
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
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 관리자 열람의 검색 JPQL(EXISTS 두 개 · LOWER LIKE · 파생 count · 최신순)을 **진짜 MySQL** 에 붙여 본다 — H2 는 대소문자 · 정렬 규칙과
 * 서브쿼리가 섞인 count 파생을 MySQL 과 똑같이 하지 않는다. 사무소의 잡기 · 종료 · 놓기를 실제로 거친 행으로 본다.
 *
 * 스프링을 그 MySQL 에 붙여 띄우고(Flyway 로 최신까지 올린다), 매번 새 행을 심는다(지우지 않는다) — 다른 실행의 행과 섞이지 않게
 * 실행마다 고유한 표식으로 검색한다. RR_ADMIN_DB_URL 이 없으면 통째로 건너뛴다. 그 이름의 스키마만 쓴다.
 */
@EnabledIfEnvironmentVariable(named = "RR_ADMIN_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${RR_ADMIN_DB_URL}",
        "spring.datasource.username=${RR_ADMIN_DB_USER:root}",
        "spring.datasource.password=${RR_ADMIN_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true"})
class AdminRentalRequestSearchMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_rr_admin";

    @Autowired AdminRentalRequestService adminService;
    @Autowired PartnerRentalRequestService partnerService;
    @Autowired UserRepository userRepository;
    @Autowired ForeignerVerificationRepository verificationRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired RentalRequestRepository requestRepository;
    @Autowired Encryptor encryptor;
    @Autowired JdbcTemplate jdbc;

    // 세션 끊기의 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("RR_ADMIN_DB_URL"))
                .describedAs("RR_ADMIN_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    private record Partner(User user, BrokerageOffice office) {
    }

    private User user(UserRole role, String name) {
        String uuid = UUID.randomUUID().toString();
        return userRepository.save(User.builder().uuid(uuid).name(name)
                .nickname(uuid.substring(0, 8)).nicknameNormalized(uuid.substring(0, 8))
                .phone(encryptor.encrypt("01000000021")).phoneHash(UUID.randomUUID().toString())
                .email(uuid.substring(0, 8) + "@example.test").preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(role).build());
    }

    private Long verification(User student, String fullName) {
        return verificationRepository.save(ForeignerVerification.builder()
                .uuid(UUID.randomUUID().toString()).userId(student.getId())
                .residentStatusId(jdbc.queryForObject("SELECT MIN(id) FROM resident_statuses", Long.class))
                .residentStatusCode("STUDENT").requiredDocumentCodes("[]")
                .nationalityCountryId(jdbc.queryForObject("SELECT MIN(id) FROM countries", Long.class))
                .fullName(fullName).birthDate(LocalDate.of(1998, 3, 15))
                .status(VerificationStatus.APPROVED).build()).getId();
    }

    private Partner partner(String name) {
        User user = user(UserRole.PARTNER, "홍길동");
        String regNo = "99999-2026-" + UUID.randomUUID().toString().substring(0, 5);
        BrokerageOffice office = officeRepository.save(BrokerageOffice.builder().uuid(UUID.randomUUID().toString())
                .regNo(regNo).claimedRegNo(regNo).name(name).ceoName("홍길동")
                .address("서울특별시 광진구 능동로 000").tel("02-000-0000").build());
        profileRepository.save(BrokerProfile.builder().userId(user.getId()).officeId(office.getId()).build());
        divisionRepository.save(PartnerDivision.builder().uuid(UUID.randomUUID().toString())
                .userId(user.getId()).division(PartnerDivisionType.REAL_ESTATE_BROKER).officeId(office.getId())
                .status(PartnerDivisionStatus.APPROVED).build());
        return new Partner(user, office);
    }

    private RentalRequest waiting(String number, User student, Long verificationId) {
        return requestRepository.save(RentalRequest.builder()
                .requestNumber(number).userId(student.getId()).verificationId(verificationId)
                .status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 11, 1)).build());
    }

    private List<String> numbers(PageResponse<AdminRentalRequestResponse> page) {
        return page.content().stream().map(AdminRentalRequestResponse::requestNumber).toList();
    }

    @Test
    @DisplayName("실 MySQL: 요청번호 · 회원 이름 · 여권 성명 검색 · 사무소 · 상태 필터 · 최신순 · 페이지 총수가 맞고, 잡기 · 종료 · 놓기가 그대로 보인다")
    void search_on_mysql() {
        String mark = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        User andy = user(UserRole.USER, "Andy" + mark);
        Long andyVerification = verification(andy, "NGUYEN VAN " + mark);
        User social = user(UserRole.USER, null);
        Long socialVerification = verification(social, "TRAN THI " + mark);
        Partner a = partner("행복공인중개사사무소");
        Partner b = partner("소망공인중개사사무소");

        RentalRequest first = waiting("RQ-" + mark + "-1", andy, andyVerification);
        RentalRequest second = waiting("RQ-" + mark + "-2", andy, andyVerification);
        RentalRequest third = waiting("RQ-" + mark + "-3", social, socialVerification);

        partnerService.claim(a.user().getUuid(), first.getUuid());
        partnerService.close(a.user().getUuid(), first.getUuid(), RentalRequestOutcome.FOUND);
        partnerService.claim(b.user().getUuid(), third.getUuid());
        partnerService.claim(a.user().getUuid(), second.getUuid());
        partnerService.release(a.user().getUuid(), second.getUuid());

        String lower = mark.toLowerCase(Locale.ROOT);
        assertThat(numbers(adminService.search(null, null, "rq-" + lower, PageRequest.of(0, 20))))
                .as("요청번호 — 대소문자 무시, 최신순")
                .containsExactly("RQ-" + mark + "-3", "RQ-" + mark + "-2", "RQ-" + mark + "-1");
        assertThat(numbers(adminService.search(null, null, "andy" + lower, PageRequest.of(0, 20))))
                .as("회원 이름").containsExactly("RQ-" + mark + "-2", "RQ-" + mark + "-1");
        assertThat(numbers(adminService.search(null, null, "tran thi " + lower, PageRequest.of(0, 20))))
                .as("회원 이름이 없으면 여권 성명").containsExactly("RQ-" + mark + "-3");

        assertThat(numbers(adminService.search(null, a.office().getUuid(), null, PageRequest.of(0, 20))))
                .as("놓은 요청은 그 사무소에서 빠지고 종료한 요청은 남는다").containsExactly("RQ-" + mark + "-1");
        assertThat(numbers(adminService.search(RentalRequestStatus.IN_PROGRESS, b.office().getUuid(), null,
                PageRequest.of(0, 20)))).containsExactly("RQ-" + mark + "-3");
        assertThat(numbers(adminService.search(RentalRequestStatus.SUBMITTED, null, mark, PageRequest.of(0, 20))))
                .containsExactly("RQ-" + mark + "-2");
        assertThat(adminService.search(null, UUID.randomUUID().toString(), null, PageRequest.of(0, 20)).content())
                .isEmpty();

        PageResponse<AdminRentalRequestResponse> page = adminService.search(null, null, mark, PageRequest.of(0, 2));
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(numbers(page)).containsExactly("RQ-" + mark + "-3", "RQ-" + mark + "-2");

        AdminRentalRequestResponse closed = adminService.get(first.getUuid());
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.outcome()).isEqualTo("FOUND");
        assertThat(closed.office().regNo()).isEqualTo(a.office().getRegNo());
        assertThat(closed.applicant().name()).isEqualTo("Andy" + mark);
        AdminRentalRequestResponse released = adminService.get(second.getUuid());
        assertThat(released.status()).isEqualTo("SUBMITTED");
        assertThat(released.office()).isNull();
        assertThat(released.claimedAt()).isNull();
        assertThat(adminService.get(third.getUuid()).applicant().name()).isNull();
    }
}
