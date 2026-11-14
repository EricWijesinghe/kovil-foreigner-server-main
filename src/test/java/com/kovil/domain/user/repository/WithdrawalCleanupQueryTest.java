package com.kovil.domain.user.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴 정리가 쓰는 파생 쿼리를 **진짜 DB에** 붙여 고정한다.
//
// ★ 서비스 테스트는 리포지토리를 목킹하므로 파생 쿼리의 이름이 틀려도 통과한다 —
//   메서드 이름 한 글자가 어긋나면 부팅이 죽는데 그것을 잡아 주는 곳이 여기뿐이다.
//   그리고 "어느 상태를 집는가"는 정책이라 목으로는 애초에 검증 대상이 아니다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class WithdrawalCleanupQueryTest {

    @Autowired RentalRequestRepository requestRepository;
    @Autowired UserRepository userRepository;
    @Autowired ForeignerVerificationRepository verificationRepository;

    private Long leaverId;
    private Long otherId;
    private Long verificationId;

    @BeforeEach
    void setUp() {
        leaverId = userRepository.save(user("u-leaver")).getId();
        otherId = userRepository.save(user("u-other")).getId();
        verificationId = verificationRepository.save(ForeignerVerification.builder()
                .uuid("v-leaver")
                .userId(leaverId)
                .residentStatusId(1L)
                .residentStatusCode("STUDENT")
                .requiredDocumentCodes("[]")
                .nationalityCountryId(7L)
                .fullName("NGUYEN VAN A")
                .birthDate(LocalDate.of(1998, 3, 15))
                .build()).getId();
    }

    /*
     * 열린 건만 집어야 한다. 이미 끝난 건까지 집으면 cancel 이 requireUserEditable 에서
     * 터져 **탈퇴 자체가 500 으로 막힌다** — 끝난 요청이 하나라도 있는 사람은 영영 못 나간다.
     */
    @Test
    @DisplayName("열린 임대 문의만 집는다 — 끝난 건과 남의 건은 빠진다")
    void finds_only_open_requests_of_that_user() {
        request("RQ-1", leaverId, RentalRequestStatus.SUBMITTED);
        request("RQ-2", leaverId, RentalRequestStatus.IN_PROGRESS);
        request("RQ-3", leaverId, RentalRequestStatus.CLOSED);
        request("RQ-4", leaverId, RentalRequestStatus.CANCELED);
        request("RQ-5", otherId, RentalRequestStatus.SUBMITTED);

        List<RentalRequest> open = requestRepository.findByUserIdAndStatusIn(
                leaverId, List.of(RentalRequestStatus.SUBMITTED, RentalRequestStatus.IN_PROGRESS));

        assertThat(open).extracting(RentalRequest::getRequestNumber)
                .containsExactlyInAnyOrder("RQ-1", "RQ-2");
    }

    // 문의를 낸 적 없는 사람도 탈퇴한다 — 빈 목록이어야 하고 예외가 아니어야 한다.
    @Test
    @DisplayName("문의가 없으면 빈 목록이다")
    void empty_when_no_request() {
        assertThat(requestRepository.findByUserIdAndStatusIn(
                leaverId, List.of(RentalRequestStatus.SUBMITTED))).isEmpty();
    }

    private User user(String uuid) {
        return User.builder()
                .uuid(uuid)
                .name("Andy")
                .nickname(uuid)
                .nicknameNormalized(uuid)
                .isNicknameCustom(false)
                .preferredLanguage("ko")
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();
    }

    private void request(String number, Long userId, RentalRequestStatus status) {
        requestRepository.save(RentalRequest.builder()
                .uuid("r-" + number)
                .requestNumber(number)
                .userId(userId)
                .verificationId(verificationId)
                .status(status)
                .moveInDate(LocalDate.of(2026, 10, 1))
                .moveInFlexible(false)
                .build());
    }
}
