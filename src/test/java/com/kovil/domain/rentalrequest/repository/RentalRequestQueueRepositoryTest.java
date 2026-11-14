package com.kovil.domain.rentalrequest.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스 테스트는 리포지토리를 목킹하므로 **JPQL 자체의 논리 오류를 잡지 못한다.**
// 실제로 두 필터가 AND 로 묶여 "영원히 0건"이던 것이 그렇게 새어 나갔다.
// 이 파일은 사무소들의 대기 목록 · 사무소가 맡은 요청 · 관리자 열람 쿼리를 진짜 DB 에 붙여 고정한다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class RentalRequestQueueRepositoryTest {

    private static final Long OFFICE = 7L;
    private static final Long OTHER_OFFICE = 8L;
    private static final Long GWANGJIN = 20L;
    private static final Long MAPO = 21L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Autowired
    RentalRequestRepository requestRepository;

    @Autowired
    RentalRequestRegionRepository regionLinkRepository;

    @Autowired
    RentalRequestOptionRepository optionLinkRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ForeignerVerificationRepository verificationRepository;

    private Long applicantId;
    private Long verificationId;

    @BeforeEach
    void setUp() {
        // ★ 표시용 이름과 여권 성명을 **일부러 다르게** 둔다. 둘을 같게 채우면 픽스처가
        //   실제 데이터 형상을 위장해, 한쪽 축만 검색해도 테스트가 통과해 버린다.
        applicantId = userRepository.save(user("u-applicant", "Andy")).getId();
        verificationId = saveVerification(applicantId, "NGUYEN VAN A");
    }

    private Long saveVerification(Long userId, String fullName) {
        return verificationRepository.save(ForeignerVerification.builder()
                .uuid("v-" + userId + "-" + fullName)
                .userId(userId)
                .residentStatusId(1L)
                .residentStatusCode("STUDENT")
                .requiredDocumentCodes("[]")
                .nationalityCountryId(7L)
                .fullName(fullName)
                .birthDate(LocalDate.of(1998, 3, 15))
                .build()).getId();
    }

    private User user(String uuid, String name) {
        return User.builder()
                .uuid(uuid)
                .name(name)
                .nickname(uuid)
                .nicknameNormalized(uuid)
                .isNicknameCustom(false)
                .preferredLanguage("ko")
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();
    }

    private RentalRequest save(String number, Long userId, RentalRequestStatus status, Long officeId) {
        return requestRepository.save(RentalRequest.builder()
                .uuid("r-" + number)
                .requestNumber(number)
                .userId(userId)
                .verificationId(verificationId)
                .officeId(officeId)
                .claimedAt(officeId == null ? null : AT)
                .outcome(status == RentalRequestStatus.CLOSED && officeId != null ? RentalRequestOutcome.FOUND : null)
                .status(status)
                .moveInDate(LocalDate.of(2026, 10, 1))
                .moveInFlexible(false)
                .build());
    }

    private RentalRequest waiting(String number) {
        return save(number, applicantId, RentalRequestStatus.SUBMITTED, null);
    }

    private List<String> numbers(Page<RentalRequest> page) {
        return page.getContent().stream().map(RentalRequest::getRequestNumber).toList();
    }

    @Nested
    @DisplayName("사무소들이 보는 대기 목록")
    class Pool {

        @Test
        @DisplayName("대기 중인 요청만, 오래된 것부터 나온다 — 진행 중 · 종료 · 취소는 빠진다")
        void only_waiting_oldest_first() {
            waiting("RQ-1");
            save("RQ-2", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);
            save("RQ-3", applicantId, RentalRequestStatus.CLOSED, OFFICE);
            save("RQ-4", applicantId, RentalRequestStatus.CANCELED, null);
            waiting("RQ-5");

            assertThat(numbers(requestRepository.findPool(PageRequest.of(0, 20)))).containsExactly("RQ-1", "RQ-5");
        }

        // 연락이 닿지 않는 사람을 잡게 하지 않는다. 정지가 풀리면 요청은 그대로 돌아온다(요청을 건드리지 않는다).
        @Test
        @DisplayName("신청자 계정이 정지 · 탈퇴면 빠지고, 정지가 풀리면 돌아온다")
        void inactive_applicants_are_hidden() {
            User suspended = userRepository.save(user("u-suspended", "Bora"));
            User withdrawn = userRepository.save(user("u-withdrawn", "Chen"));
            waiting("RQ-1");
            save("RQ-2", suspended.getId(), RentalRequestStatus.SUBMITTED, null);
            save("RQ-3", withdrawn.getId(), RentalRequestStatus.SUBMITTED, null);
            suspended.suspend("정지 사유", AT);
            withdrawn.withdraw(AT);
            userRepository.saveAndFlush(suspended);
            userRepository.saveAndFlush(withdrawn);

            assertThat(numbers(requestRepository.findPool(PageRequest.of(0, 20)))).containsExactly("RQ-1");

            suspended.unsuspend();
            userRepository.saveAndFlush(suspended);

            assertThat(numbers(requestRepository.findPool(PageRequest.of(0, 20)))).containsExactly("RQ-1", "RQ-2");
        }

        @Test
        @DisplayName("총 건수와 페이지가 조건과 같은 축으로 센다")
        void count_matches_filter() {
            waiting("RQ-1");
            waiting("RQ-2");
            waiting("RQ-3");
            save("RQ-4", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);

            var page = requestRepository.findPool(PageRequest.of(0, 2));

            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(numbers(page)).containsExactly("RQ-1", "RQ-2");
        }
    }

    /*
     * 희망 지역은 두 축에 있다 — 질문지 이관 전 요청은 고정 지역(rental_request_regions), 그 뒤 요청은 질문지의 지역 답
     * (rental_request_options.region_id). 한쪽만 보면 옛 요청이나 새 요청이 통째로 사라진다.
     */
    @Nested
    @DisplayName("희망 지역으로 좁힌 대기 목록")
    class PoolInRegions {

        private void legacyRegion(RentalRequest request, Long regionId) {
            regionLinkRepository.save(RentalRequestRegion.builder()
                    .rentalRequestId(request.getId()).regionId(regionId).build());
        }

        private void answeredRegion(RentalRequest request, Long regionId) {
            optionLinkRepository.save(RentalRequestOption.builder()
                    .rentalRequestId(request.getId()).requestOptionId(900L).regionId(regionId).build());
        }

        @Test
        @DisplayName("고정 지역 축과 질문지 지역 답 축 둘 다로 걸린다 — 고른 지역 밖은 빠진다")
        void both_axes_match() {
            legacyRegion(waiting("RQ-1"), GWANGJIN);
            answeredRegion(waiting("RQ-2"), GWANGJIN);
            legacyRegion(waiting("RQ-3"), MAPO);
            answeredRegion(waiting("RQ-4"), MAPO);
            waiting("RQ-5");

            assertThat(numbers(requestRepository.findPoolInRegions(List.of(GWANGJIN), PageRequest.of(0, 20))))
                    .containsExactly("RQ-1", "RQ-2");
            assertThat(numbers(requestRepository.findPoolInRegions(List.of(GWANGJIN, MAPO), PageRequest.of(0, 20))))
                    .containsExactly("RQ-1", "RQ-2", "RQ-3", "RQ-4");
        }

        @Test
        @DisplayName("두 축 · 여러 지역에 함께 걸려도 한 번만 나오고, 총 건수도 한 번만 센다")
        void no_duplicates() {
            RentalRequest both = waiting("RQ-1");
            legacyRegion(both, GWANGJIN);
            legacyRegion(both, MAPO);
            answeredRegion(both, GWANGJIN);

            var page = requestRepository.findPoolInRegions(List.of(GWANGJIN, MAPO), PageRequest.of(0, 20));

            assertThat(numbers(page)).containsExactly("RQ-1");
            assertThat(page.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("지역으로 좁혀도 대기 조건(진행 중 제외 · 신청자 ACTIVE)은 그대로 걸린다")
        void pool_conditions_still_apply() {
            User suspended = userRepository.save(user("u-suspended", "Bora"));
            legacyRegion(save("RQ-1", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE), GWANGJIN);
            legacyRegion(save("RQ-2", suspended.getId(), RentalRequestStatus.SUBMITTED, null), GWANGJIN);
            legacyRegion(waiting("RQ-3"), GWANGJIN);
            suspended.suspend("정지 사유", AT);
            userRepository.saveAndFlush(suspended);

            assertThat(numbers(requestRepository.findPoolInRegions(List.of(GWANGJIN), PageRequest.of(0, 20))))
                    .containsExactly("RQ-3");
        }
    }

    @Nested
    @DisplayName("사무소가 맡은 요청")
    class Handled {

        @Test
        @DisplayName("진행 중 · 종료 · 맡은 뒤 취소된 것이 최신순으로 나오고, 남의 사무소 · 대기 요청은 빠진다")
        void handled_by_office_newest_first() {
            save("RQ-1", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);
            save("RQ-2", applicantId, RentalRequestStatus.CLOSED, OFFICE);
            save("RQ-3", applicantId, RentalRequestStatus.CANCELED, OFFICE);
            save("RQ-4", applicantId, RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE);
            waiting("RQ-5");

            assertThat(numbers(requestRepository.findHandledByOffice(OFFICE, null, PageRequest.of(0, 20))))
                    .containsExactly("RQ-3", "RQ-2", "RQ-1");
            assertThat(numbers(requestRepository.findHandledByOffice(
                    OFFICE, RentalRequestStatus.CLOSED, PageRequest.of(0, 20)))).containsExactly("RQ-2");
        }

        // 놓은 요청은 office_id 가 비워져 더는 그 사무소의 것이 아니다.
        @Test
        @DisplayName("놓아서 대기로 돌아간 요청은 그 사무소 목록에서 빠진다")
        void released_leaves_the_office() {
            RentalRequest request = save("RQ-1", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);
            request.release(OFFICE);
            requestRepository.saveAndFlush(request);

            assertThat(requestRepository.findHandledByOffice(OFFICE, null, PageRequest.of(0, 20))).isEmpty();
            assertThat(numbers(requestRepository.findPool(PageRequest.of(0, 20)))).containsExactly("RQ-1");
        }

        @Test
        @DisplayName("진행 중 개수 · 대기 복귀 대상은 그 사무소의 진행 중만 센다")
        void in_progress_count_and_list() {
            save("RQ-1", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);
            save("RQ-2", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);
            save("RQ-3", applicantId, RentalRequestStatus.CLOSED, OFFICE);
            save("RQ-4", applicantId, RentalRequestStatus.CANCELED, OFFICE);
            save("RQ-5", applicantId, RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE);
            waiting("RQ-6");

            assertThat(requestRepository.countByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS)).isEqualTo(2);
            assertThat(requestRepository.findByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS))
                    .extracting(RentalRequest::getRequestNumber).containsExactlyInAnyOrder("RQ-1", "RQ-2");
            assertThat(requestRepository.countByOfficeIdAndStatus(OTHER_OFFICE, RentalRequestStatus.IN_PROGRESS))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("관리자 열람")
    class AdminSearch {

        @Test
        @DisplayName("status · 맡은 사무소가 null 이면 전체, 값을 주면 그 조건만 — 최신순")
        void status_and_office_filters() {
            waiting("RQ-1");
            save("RQ-2", applicantId, RentalRequestStatus.IN_PROGRESS, OFFICE);
            save("RQ-3", applicantId, RentalRequestStatus.CLOSED, OFFICE);
            save("RQ-4", applicantId, RentalRequestStatus.IN_PROGRESS, OTHER_OFFICE);

            assertThat(numbers(requestRepository.searchForAdmin(null, null, null, PageRequest.of(0, 20))))
                    .containsExactly("RQ-4", "RQ-3", "RQ-2", "RQ-1");
            assertThat(numbers(requestRepository.searchForAdmin(
                    RentalRequestStatus.IN_PROGRESS, null, null, PageRequest.of(0, 20)))).containsExactly("RQ-4", "RQ-2");
            assertThat(numbers(requestRepository.searchForAdmin(null, OFFICE, null, PageRequest.of(0, 20))))
                    .containsExactly("RQ-3", "RQ-2");
            assertThat(numbers(requestRepository.searchForAdmin(
                    RentalRequestStatus.IN_PROGRESS, OFFICE, null, PageRequest.of(0, 20)))).containsExactly("RQ-2");
        }

        @Test
        @DisplayName("요청번호 부분일치 — 대소문자를 가리지 않는다")
        void matches_request_number() {
            waiting("RQ-260811-ABC123");
            waiting("RQ-260811-ZZZ999");

            assertThat(numbers(requestRepository.searchForAdmin(null, null, "abc123", PageRequest.of(0, 20))))
                    .containsExactly("RQ-260811-ABC123");
        }

        @Test
        @DisplayName("신청자 표시용 이름 부분일치도 잡는다")
        void matches_display_name() {
            waiting("RQ-1");

            assertThat(requestRepository.searchForAdmin(null, null, "andy", PageRequest.of(0, 20))).hasSize(1);
        }

        // ★ 회귀 방지: 화면에서 읽는 이름은 여권 성명이다. users.name 만 뒤지면
        //   소셜 가입자(표시용 이름이 아예 NULL)와 탈퇴자가 검색에서 통째로 사라진다.
        @Test
        @DisplayName("표시용 이름이 NULL 이어도(소셜 가입 · 탈퇴) 여권 성명으로 잡힌다")
        void matches_when_display_name_is_null() {
            Long social = userRepository.save(user("u-social", null)).getId();
            Long socialVerification = saveVerification(social, "TRAN THI B");
            requestRepository.save(RentalRequest.builder()
                    .uuid("r-social").requestNumber("RQ-9").userId(social)
                    .verificationId(socialVerification).status(RentalRequestStatus.SUBMITTED)
                    .moveInDate(LocalDate.of(2026, 10, 1)).moveInFlexible(false).build());

            assertThat(numbers(requestRepository.searchForAdmin(null, null, "tran", PageRequest.of(0, 20))))
                    .containsExactly("RQ-9");
        }

        @Test
        @DisplayName("아무것도 안 맞으면 빈 목록이다 — 필터가 조용히 풀리지 않는다")
        void no_match_is_empty() {
            waiting("RQ-1");

            assertThat(requestRepository.searchForAdmin(null, null, "없는값", PageRequest.of(0, 20))).isEmpty();
        }

        // Page 는 count 쿼리를 따로 파생시킨다 — EXISTS 서브쿼리가 섞이면 그 파생이 깨질 수 있고,
        // 깨지면 총 건수와 다음 페이지 존재 여부가 조용히 틀린다.
        @Test
        @DisplayName("검색이 걸린 상태에서도 총 건수와 페이지 수가 맞는다")
        void count_query_survives_the_exists_subquery() {
            waiting("RQ-1");
            waiting("RQ-2");
            waiting("RQ-3");

            var page = requestRepository.searchForAdmin(null, null, "nguyen", PageRequest.of(0, 2));

            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.getContent()).hasSize(2);
        }
    }
}
