package com.kovil.domain.partner.entity;

import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.partner.exception.PartnerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 부문의 상태 전이 격자. 승인 · 정지의 축이 이 행 하나라서, 여기서 잘못 열리는 전이는 곧
 * "서류 없이 승인" · "정지된 중개사의 광고 복귀" 같은 사고가 된다.
 */
class PartnerDivisionTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 6, 10, 0);

    private static PartnerDivision division(PartnerDivisionStatus status, LocalDateTime submittedAt) {
        return PartnerDivision.builder().uuid("pd-1").userId(7L)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER)
                .status(status).submittedAt(submittedAt).build();
    }

    private static PartnerErrorCode codeOf(Throwable e) {
        return (PartnerErrorCode) ((PartnerException) e).getErrorCode();
    }

    @Nested
    @DisplayName("제출 · 재제출")
    class Submit {

        @Test
        @DisplayName("가입 직후(제출 전)의 부문은 제출하면 대기열에 오른다")
        void first_submission() {
            PartnerDivision d = division(PartnerDivisionStatus.PENDING, null);

            d.submit(AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(d.isSubmitted()).isTrue();
            assertThat(d.getSubmittedAt()).isEqualTo(AT);
        }

        // 반려 사유는 남는다 — 심사자가 재제출 건을 볼 때 "지난번에 왜 반려했나"의 근거다.
        @Test
        @DisplayName("반려된 부문은 고쳐 내면 PENDING 으로 돌아가고 반려 사유는 지우지 않는다")
        void resubmission_after_rejection() {
            PartnerDivision d = division(PartnerDivisionStatus.PENDING, AT.minusDays(2));
            d.reject(1L, "등록증의 대표자 성명과 다릅니다", AT.minusDays(1));

            d.submit(AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(d.getSubmittedAt()).isEqualTo(AT);
            assertThat(d.getRejectReason()).isEqualTo("등록증의 대표자 성명과 다릅니다");
        }

        // 정지된 부문은 서류를 바꿔 스스로 정지를 풀 수 없다.
        @Test
        @DisplayName("정지된 부문은 다시 낼 수 없다")
        void suspended_cannot_resubmit() {
            PartnerDivision d = division(PartnerDivisionStatus.SUSPENDED, AT.minusDays(1));

            assertThatThrownBy(() -> d.submit(AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE);
            assertThatThrownBy(d::assertEditable)
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        }

        /*
         * ★ 승인된 부문은 다시 내도 대기로 떨어지지 않는다(PO 확정 2026-10-07) — 대기로 돌아가면 영업 중인 사무소의 매물 · 기능이
         *   멈춘다. 승인 뒤의 변경은 부문을 건드리지 않는 변경 신청이 받는다(서비스가 그쪽으로 보낸다). 이 가드는 그 분기가
         *   빠졌을 때 부문이 조용히 대기로 떨어지는 것을 막는 마지막 선이다.
         *   (의도된 행동 변경 — 이전에는 승인된 부문의 재제출이 재심사로 대기에 돌아갔다.)
         */
        @Test
        @DisplayName("승인된 부문은 다시 낼 수 없다 — 상태 · 제출 시각 · 승인 시각이 그대로다")
        void approved_cannot_resubmit() {
            PartnerDivision d = division(PartnerDivisionStatus.PENDING, AT.minusDays(3));
            d.approve(1L, AT.minusDays(2));

            assertThatThrownBy(() -> d.submit(AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE);
            assertThatThrownBy(d::assertEditable)
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(d.getSubmittedAt()).isEqualTo(AT.minusDays(3));
            assertThat(d.getLastApprovedAt()).isEqualTo(AT.minusDays(2));
        }

        // 대기 · 반려만 고쳐 낼 수 있다 — 첫 심사의 길이다.
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = {"PENDING", "REJECTED"})
        @DisplayName("대기 · 반려 부문은 고쳐 낼 수 있다")
        void pending_or_rejected_is_editable(PartnerDivisionStatus status) {
            PartnerDivision d = division(status, AT.minusDays(1));

            d.assertEditable();
            d.submit(AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(d.getSubmittedAt()).isEqualTo(AT);
        }
    }

    /*
     * ★ 관리자가 본 판과 지금 판을 대조한다. @Version 은 한 요청 안만 지키므로, 상세를 본 뒤 재제출이 끼면
     *   관리자가 본 적 없는 서류가 승인된다 — 그 사이를 이 대조가 막는다.
     */
    @Nested
    @DisplayName("관리자가 본 판 대조")
    class SeenVersion {

        private PartnerDivision versioned(long version) {
            return PartnerDivision.builder().uuid("pd-1").userId(7L)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER)
                    .status(PartnerDivisionStatus.PENDING).submittedAt(AT).version(version).build();
        }

        @Test
        @DisplayName("본 판과 같으면 통과한다")
        void same_version_passes() {
            versioned(3L).assertVersion(3L);
        }

        @Test
        @DisplayName("본 판과 다르면 PARTNER_DIVISION_CHANGED 다")
        void different_version_is_rejected() {
            assertThatThrownBy(() -> versioned(4L).assertVersion(3L))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_CHANGED);
            assertThatThrownBy(() -> versioned(4L).assertVersion(null))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_CHANGED);
        }
    }

    /*
     * ★ 탈퇴하면 대기 중인 부문을 닫는다 — 제출 전이든 후든. 닫지 않으면 대기열에 승인할 수 없는 익명 행이 남는다.
     *   사람이 한 판단이 아니라 심사자는 비운다. 이미 판정이 난 건은 이력이라 건드리지 않는다.
     */
    @Nested
    @DisplayName("탈퇴로 닫기")
    class Withdrawal {

        @ParameterizedTest(name = "제출됨={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("대기 중인 부문은 제출 여부와 관계없이 반려로 닫히고 심사자는 비어 있다")
        void pending_is_closed(boolean submitted) {
            PartnerDivision d = division(PartnerDivisionStatus.PENDING, submitted ? AT.minusDays(1) : null);

            d.closeOnWithdrawal("회원이 탈퇴했습니다.", AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.REJECTED);
            assertThat(d.getRejectReason()).isEqualTo("회원이 탈퇴했습니다.");
            assertThat(d.getReviewedBy()).isNull();
            assertThat(d.getReviewedAt()).isEqualTo(AT);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("판정이 난 부문은 그대로 둔다")
        void decided_is_left_alone(PartnerDivisionStatus status) {
            PartnerDivision d = division(status, AT.minusDays(1));

            d.closeOnWithdrawal("회원이 탈퇴했습니다.", AT);

            assertThat(d.getStatus()).isEqualTo(status);
            assertThat(d.getRejectReason()).isNull();
            assertThat(d.getReviewedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("심사")
    class Review {

        @Test
        @DisplayName("제출된 대기 건을 승인하면 APPROVED 와 심사자 · 시각이 남는다")
        void approve_submitted() {
            PartnerDivision d = division(PartnerDivisionStatus.PENDING, AT.minusDays(1));

            d.approve(1L, AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(d.getReviewedBy()).isEqualTo(1L);
            assertThat(d.getReviewedAt()).isEqualTo(AT);
            assertThat(d.getLastApprovedAt()).isEqualTo(AT);
        }

        // 반려는 승인 이력을 건드리지 않는다 — 이전 방식의 재심사로 대기에 남은 행이 반려돼도 "예전에 승인됐었다"는 남는다.
        @Test
        @DisplayName("반려는 마지막 승인 시각을 바꾸지 않는다")
        void reject_keeps_last_approved_at() {
            PartnerDivision d = PartnerDivision.builder().uuid("pd-1").userId(7L)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING)
                    .submittedAt(AT.minusDays(1)).lastApprovedAt(AT.minusDays(2)).build();

            d.reject(1L, "새 등록증이 흐립니다", AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.REJECTED);
            assertThat(d.getLastApprovedAt()).isEqualTo(AT.minusDays(2));
        }

        // ★ 서류(등록증) 없이 승인하는 길을 막는다 — 승인 기준이 대표자 이름 · 휴대폰 · 서류다.
        @Test
        @DisplayName("제출 전(서류 없음)인 부문은 승인도 반려도 할 수 없다")
        void unsubmitted_cannot_be_reviewed() {
            PartnerDivision d = division(PartnerDivisionStatus.PENDING, null);

            assertThatThrownBy(() -> d.approve(1L, AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_REVIEWABLE);
            assertThatThrownBy(() -> d.reject(1L, "사유", AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_REVIEWABLE);
            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
        }

        // 반려된 건을 관리자가 바로 승인으로 뒤집는 길은 없다 — 신청자가 고쳐 다시 내야 한다.
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("대기 중이 아닌 부문은 승인 · 반려할 수 없다")
        void only_pending_is_reviewable(PartnerDivisionStatus status) {
            PartnerDivision d = division(status, AT.minusDays(1));

            assertThatThrownBy(() -> d.approve(1L, AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_REVIEWABLE);
            assertThatThrownBy(() -> d.reject(1L, "사유", AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_REVIEWABLE);
            assertThat(d.getStatus()).isEqualTo(status);
        }
    }

    @Nested
    @DisplayName("정지 · 복구")
    class SuspendAndReinstate {

        @Test
        @DisplayName("승인된 부문만 정지되고 정지 사유가 남는다")
        void suspend_approved() {
            PartnerDivision d = division(PartnerDivisionStatus.APPROVED, AT.minusDays(3));

            d.suspend(1L, "허위 매물 신고 누적", AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
            assertThat(d.getSuspendReason()).isEqualTo("허위 매물 신고 누적");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("승인 상태가 아니면 정지할 수 없다")
        void only_approved_is_suspendable(PartnerDivisionStatus status) {
            PartnerDivision d = division(status, AT.minusDays(1));

            assertThatThrownBy(() -> d.suspend(1L, "사유", AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_APPROVED);
        }

        // 정지 사유는 지우지 않는다 — 이력 테이블이 없어 지우면 "왜 정지했었나"가 사라진다.
        @Test
        @DisplayName("정지된 부문만 복구되고 정지 사유는 남는다")
        void reinstate_suspended() {
            PartnerDivision d = division(PartnerDivisionStatus.APPROVED, AT.minusDays(3));
            d.suspend(1L, "사유", AT.minusDays(1));

            d.reinstate(2L, AT);

            assertThat(d.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
            assertThat(d.getReviewedBy()).isEqualTo(2L);
            assertThat(d.getSuspendReason()).isEqualTo("사유");
            assertThat(d.getLastApprovedAt()).isEqualTo(AT);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = "SUSPENDED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("정지 상태가 아니면 복구할 수 없다")
        void only_suspended_is_reinstatable(PartnerDivisionStatus status) {
            PartnerDivision d = division(status, AT.minusDays(1));

            assertThatThrownBy(() -> d.reinstate(1L, AT))
                    .extracting(PartnerDivisionTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_SUSPENDED);
        }
    }
}
