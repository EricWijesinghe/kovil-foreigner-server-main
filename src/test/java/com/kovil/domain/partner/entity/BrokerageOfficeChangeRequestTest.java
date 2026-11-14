package com.kovil.domain.partner.entity;

import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.partner.exception.PartnerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 변경 신청의 상태 전이 격자. 판정이 난 건을 다시 덮거나 뒤집을 수 있으면 "승인된 값이 몰래 바뀐다" ·
 * "반려된 신청이 승인으로 적용된다" 같은 사고가 된다.
 */
class BrokerageOfficeChangeRequestTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    private static BrokerageOfficeChangeRequest request(OfficeChangeRequestStatus status) {
        return BrokerageOfficeChangeRequest.builder().uuid("cr-1").officeId(70L).requestedBy(7L)
                .regNo("99999-2026-00009").name("새상호공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 동작구").licenseFileId(66L).status(status)
                .submittedAt(AT.minusDays(1)).version(3L).build();
    }

    private static PartnerErrorCode codeOf(Throwable e) {
        return (PartnerErrorCode) ((PartnerException) e).getErrorCode();
    }

    @Test
    @DisplayName("새 신청은 대기 상태다")
    void new_request_is_pending() {
        BrokerageOfficeChangeRequest r = BrokerageOfficeChangeRequest.builder().uuid("cr-1").officeId(70L)
                .requestedBy(7L).regNo("99999-2026-00009").name("상호").ceoName("홍길동").address("주소")
                .licenseFileId(66L).submittedAt(AT).build();

        assertThat(r.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
        assertThat(r.isPending()).isTrue();
    }

    @Nested
    @DisplayName("덮어쓰기")
    class Overwrite {

        // 대기 중에 다시 내면 새 행이 아니라 그 건을 통째로 덮는다 — 제출 시각이 새로 찍혀 대기열 뒤로 간다.
        @Test
        @DisplayName("대기 건은 바꿀 값 한 벌과 제출 시각을 통째로 덮는다")
        void pending_is_overwritten() {
            BrokerageOfficeChangeRequest r = request(OfficeChangeRequestStatus.PENDING);

            r.overwrite("99999-2026-00010", "다른상호공인중개사사무소", "김철수", "서울특별시 종로구", "000-00-00000", 77L, AT);

            assertThat(r.getRegNo()).isEqualTo("99999-2026-00010");
            assertThat(r.getName()).isEqualTo("다른상호공인중개사사무소");
            assertThat(r.getCeoName()).isEqualTo("김철수");
            assertThat(r.getAddress()).isEqualTo("서울특별시 종로구");
            assertThat(r.getBizNo()).isEqualTo("000-00-00000");
            assertThat(r.getLicenseFileId()).isEqualTo(77L);
            assertThat(r.getSubmittedAt()).isEqualTo(AT);
            assertThat(r.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
        }

        // 승인된 값을 덮으면 사무소에 적용된 값과 기록이 어긋난다. 반려된 건을 덮으면 반려 사유와 값이 어긋난다.
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OfficeChangeRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("판정이 난 건은 덮을 수 없다")
        void decided_cannot_be_overwritten(OfficeChangeRequestStatus status) {
            BrokerageOfficeChangeRequest r = request(status);

            assertThatThrownBy(() -> r.overwrite("99999-2026-00010", "상호", "홍길동", "주소", null, 77L, AT))
                    .extracting(BrokerageOfficeChangeRequestTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE);
            assertThat(r.getRegNo()).isEqualTo("99999-2026-00009");
            assertThat(r.getLicenseFileId()).isEqualTo(66L);
        }
    }

    @Nested
    @DisplayName("심사")
    class Review {

        @Test
        @DisplayName("승인하면 APPROVED 와 심사자 · 시각이 남는다")
        void approve_pending() {
            BrokerageOfficeChangeRequest r = request(OfficeChangeRequestStatus.PENDING);

            r.approve(1L, AT);

            assertThat(r.getStatus()).isEqualTo(OfficeChangeRequestStatus.APPROVED);
            assertThat(r.getReviewedBy()).isEqualTo(1L);
            assertThat(r.getReviewedAt()).isEqualTo(AT);
            assertThat(r.getRejectReason()).isNull();
        }

        @Test
        @DisplayName("반려하면 REJECTED 와 사유 · 심사자 · 시각이 남는다")
        void reject_pending() {
            BrokerageOfficeChangeRequest r = request(OfficeChangeRequestStatus.PENDING);

            r.reject(1L, "새 등록증의 상호가 다릅니다", AT);

            assertThat(r.getStatus()).isEqualTo(OfficeChangeRequestStatus.REJECTED);
            assertThat(r.getRejectReason()).isEqualTo("새 등록증의 상호가 다릅니다");
            assertThat(r.getReviewedBy()).isEqualTo(1L);
            assertThat(r.getReviewedAt()).isEqualTo(AT);
        }

        // 반려된 건을 관리자가 바로 승인으로 뒤집는 길은 없다 — 파트너가 다시 내야 한다(새 대기 건).
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OfficeChangeRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("대기 건이 아니면 승인 · 반려할 수 없다")
        void only_pending_is_reviewable(OfficeChangeRequestStatus status) {
            BrokerageOfficeChangeRequest r = request(status);

            assertThatThrownBy(() -> r.approve(1L, AT))
                    .extracting(BrokerageOfficeChangeRequestTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE);
            assertThatThrownBy(() -> r.reject(1L, "사유", AT))
                    .extracting(BrokerageOfficeChangeRequestTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE);
            assertThat(r.getStatus()).isEqualTo(status);
        }

        // 관리자가 상세를 본 뒤 신청자가 덮어썼으면 본 적 없는 값이 승인된다 — 요청에 실린 판과 대조한다.
        @Test
        @DisplayName("관리자가 본 판과 다르면 OFFICE_CHANGE_REQUEST_CHANGED 다")
        void stale_version_is_rejected() {
            BrokerageOfficeChangeRequest r = request(OfficeChangeRequestStatus.PENDING);

            r.assertVersion(3L);
            assertThatThrownBy(() -> r.assertVersion(2L))
                    .extracting(BrokerageOfficeChangeRequestTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_CHANGED);
            assertThatThrownBy(() -> r.assertVersion(null))
                    .extracting(BrokerageOfficeChangeRequestTest::codeOf)
                    .isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_CHANGED);
        }
    }

    @Nested
    @DisplayName("탈퇴로 닫기")
    class Withdrawal {

        @Test
        @DisplayName("대기 건은 반려로 닫히고 심사자는 비어 있다")
        void pending_is_closed() {
            BrokerageOfficeChangeRequest r = request(OfficeChangeRequestStatus.PENDING);

            r.closeOnWithdrawal("회원이 탈퇴했습니다.", AT);

            assertThat(r.getStatus()).isEqualTo(OfficeChangeRequestStatus.REJECTED);
            assertThat(r.getRejectReason()).isEqualTo("회원이 탈퇴했습니다.");
            assertThat(r.getReviewedBy()).isNull();
            assertThat(r.getReviewedAt()).isEqualTo(AT);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OfficeChangeRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("판정이 난 건은 그대로 둔다")
        void decided_is_left_alone(OfficeChangeRequestStatus status) {
            BrokerageOfficeChangeRequest r = request(status);

            r.closeOnWithdrawal("회원이 탈퇴했습니다.", AT);

            assertThat(r.getStatus()).isEqualTo(status);
            assertThat(r.getRejectReason()).isNull();
            assertThat(r.getReviewedAt()).isNull();
        }
    }
    // 신청자가 거둬들인다 — 사람이 판단한 처리가 아니라 심사자 · 심사 시각을 남기지 않는다(탈퇴로 닫기와 같은 판단).
    @Nested
    @DisplayName("신청자 취소")
    class Cancel {

        @Test
        @DisplayName("대기 건은 CANCELED 가 되고 심사자 · 심사 시각 · 사유는 비어 있다")
        void pending_becomes_canceled() {
            BrokerageOfficeChangeRequest r = request(OfficeChangeRequestStatus.PENDING);

            r.cancel();

            assertThat(r.getStatus()).isEqualTo(OfficeChangeRequestStatus.CANCELED);
            assertThat(r.getReviewedBy()).isNull();
            assertThat(r.getReviewedAt()).isNull();
            assertThat(r.getRejectReason()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OfficeChangeRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("판정이 난 건 · 이미 거둬들인 건은 취소할 수 없다 — OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE")
        void decided_cannot_be_canceled(OfficeChangeRequestStatus status) {
            BrokerageOfficeChangeRequest r = request(status);

            assertThatThrownBy(r::cancel)
                    .isInstanceOf(PartnerException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE));
            assertThat(r.getStatus()).isEqualTo(status);
        }
    }
}
