package com.kovil.domain.rentalrequest.entity;

import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 임대 요청의 상태 전이는 엔티티가 소유한다 — 파트너 API · 대기 복귀 훅 · 유학생 API 가 모두 이 메서드들을 부른다.
 *
 *   SUBMITTED(대기) → IN_PROGRESS(사무소가 잡음) → CLOSED(사무소가 결과를 골라 종료)
 *   CANCELED(유학생 — 대기 · 진행 중 언제나) · IN_PROGRESS → SUBMITTED(놓기 · 맡을 사람이 사라짐)
 *
 * ★ 실패한 전이는 아무것도 바꾸지 않는다 — 상태 · 맡은 사무소 · 시각이 그대로인지 매번 같이 본다.
 */
class RentalRequestTransitionTest {

    private static final Long OFFICE = 7L;
    private static final Long OTHER_OFFICE = 8L;
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final LocalDateTime LATER = CLAIMED_AT.plusDays(2);

    private static RentalRequest submitted() {
        return RentalRequest.builder()
                .uuid("r-1").requestNumber("RQ-261007-AAAAAA").userId(10L).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 11, 1)).build();
    }

    private static RentalRequest inProgress() {
        RentalRequest request = submitted();
        request.claim(OFFICE, CLAIMED_AT);
        return request;
    }

    private static RentalRequest closed() {
        RentalRequest request = inProgress();
        request.close(OFFICE, RentalRequestOutcome.FOUND, LATER);
        return request;
    }

    private static RentalRequest canceledAfterClaim() {
        RentalRequest request = inProgress();
        request.cancel("직접 구했습니다", LATER);
        return request;
    }

    private static RentalRequest canceledWhileWaiting() {
        RentalRequest request = submitted();
        request.cancel(null, LATER);
        return request;
    }

    private static void assertFailsWith(ThrowingCallable call, RentalRequestErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(RentalRequestException.class)
                .extracting(e -> ((RentalRequestException) e).getErrorCode())
                .isEqualTo(code);
    }

    private record Snapshot(RentalRequestStatus status, Long officeId, LocalDateTime claimedAt,
                            RentalRequestOutcome outcome, LocalDateTime closedAt) {
        static Snapshot of(RentalRequest request) {
            return new Snapshot(request.getStatus(), request.getOfficeId(), request.getClaimedAt(),
                    request.getOutcome(), request.getClosedAt());
        }
    }

    @Test
    @DisplayName("상태는 넷이다 — 계약됨(CONTRACTED)은 없다")
    void four_states_only() {
        assertThat(Arrays.stream(RentalRequestStatus.values()).map(Enum::name))
                .containsExactly("SUBMITTED", "IN_PROGRESS", "CANCELED", "CLOSED");
        assertThat(Arrays.stream(RentalRequestOutcome.values()).map(Enum::name))
                .containsExactly("FOUND", "NOT_PROCEEDED");
    }

    @Nested
    @DisplayName("잡기")
    class Claim {

        @Test
        @DisplayName("대기 요청을 잡으면 진행 중이 되고 맡은 사무소 · 잡은 시각이 남는다")
        void claims_waiting_request() {
            RentalRequest request = submitted();

            request.claim(OFFICE, CLAIMED_AT);

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.IN_PROGRESS);
            assertThat(request.getOfficeId()).isEqualTo(OFFICE);
            assertThat(request.getClaimedAt()).isEqualTo(CLAIMED_AT);
            assertThat(request.getOutcome()).isNull();
        }

        // 선착순에서 진 쪽 — "이미 다른 사무소가 맡았다". 자기 사무소가 두 번 눌러도 같다(새로 잡히는 일이 없다).
        @Test
        @DisplayName("이미 잡힌 요청은 다른 사무소도 같은 사무소도 잡을 수 없다 — ALREADY_CLAIMED, 맡은 쪽은 그대로")
        void already_claimed_is_409() {
            RentalRequest request = inProgress();
            Snapshot before = Snapshot.of(request);

            assertFailsWith(() -> request.claim(OTHER_OFFICE, LATER), RentalRequestErrorCode.RENTAL_REQUEST_ALREADY_CLAIMED);
            assertFailsWith(() -> request.claim(OFFICE, LATER), RentalRequestErrorCode.RENTAL_REQUEST_ALREADY_CLAIMED);

            assertThat(Snapshot.of(request)).isEqualTo(before);
        }

        @Test
        @DisplayName("종료 · 취소된 요청은 잡을 수 없다 — REQUEST_NOT_OPEN")
        void terminal_requests_cannot_be_claimed() {
            for (RentalRequest request : new RentalRequest[]{closed(), canceledAfterClaim(), canceledWhileWaiting()}) {
                Snapshot before = Snapshot.of(request);

                assertFailsWith(() -> request.claim(OTHER_OFFICE, LATER), RentalRequestErrorCode.REQUEST_NOT_OPEN);

                assertThat(Snapshot.of(request)).isEqualTo(before);
            }
        }

        // 같은 요청이 잡히고 · 놓이고 · 다시 잡힐 수 있다 — 잡은 시각은 마지막 잡기다.
        @Test
        @DisplayName("놓인 요청은 다른 사무소가 다시 잡을 수 있고 잡은 시각이 새로 찍힌다")
        void released_request_can_be_claimed_again() {
            RentalRequest request = inProgress();
            request.release(OFFICE);

            request.claim(OTHER_OFFICE, LATER);

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.IN_PROGRESS);
            assertThat(request.getOfficeId()).isEqualTo(OTHER_OFFICE);
            assertThat(request.getClaimedAt()).isEqualTo(LATER);
        }
    }

    @Nested
    @DisplayName("놓기")
    class Release {

        @Test
        @DisplayName("맡은 사무소가 놓으면 대기로 돌아가고 맡은 사무소 · 잡은 시각이 비워진다")
        void holder_releases_to_pool() {
            RentalRequest request = inProgress();

            request.release(OFFICE);

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
            assertThat(request.getOfficeId()).isNull();
            assertThat(request.getClaimedAt()).isNull();
        }

        // 남의 요청 · 대기 요청은 "권한 없음"이 아니라 "없음"이다 — uuid 의 존재를 흘리지 않는다.
        @Test
        @DisplayName("다른 사무소 · 아무도 안 잡은 요청을 놓으려 하면 NOT_FOUND, 아무것도 안 바뀐다")
        void non_holder_gets_not_found() {
            RentalRequest held = inProgress();
            Snapshot heldBefore = Snapshot.of(held);
            RentalRequest waiting = submitted();
            Snapshot waitingBefore = Snapshot.of(waiting);

            assertFailsWith(() -> held.release(OTHER_OFFICE), RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND);
            assertFailsWith(() -> waiting.release(OFFICE), RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND);

            assertThat(Snapshot.of(held)).isEqualTo(heldBefore);
            assertThat(Snapshot.of(waiting)).isEqualTo(waitingBefore);
        }

        @Test
        @DisplayName("이미 놓은 요청을 다시 놓으면 NOT_FOUND 다 — 더는 그 사무소의 것이 아니다")
        void release_twice_is_not_found() {
            RentalRequest request = inProgress();
            request.release(OFFICE);

            assertFailsWith(() -> request.release(OFFICE), RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND);
        }

        // 종료 · 취소된 요청은 맡았던 사무소에 남아 있다 — 그래서 "없음"이 아니라 "진행 중이 아님"이다.
        @Test
        @DisplayName("맡았던 요청이 종료 · 취소됐으면 놓을 수 없다 — NOT_IN_PROGRESS, 맡았던 사무소는 남는다")
        void terminal_held_requests_cannot_be_released() {
            for (RentalRequest request : new RentalRequest[]{closed(), canceledAfterClaim()}) {
                Snapshot before = Snapshot.of(request);

                assertFailsWith(() -> request.release(OFFICE), RentalRequestErrorCode.RENTAL_REQUEST_NOT_IN_PROGRESS);

                assertThat(Snapshot.of(request)).isEqualTo(before);
                assertThat(request.getOfficeId()).isEqualTo(OFFICE);
            }
        }
    }

    @Nested
    @DisplayName("종료")
    class Close {

        @Test
        @DisplayName("맡은 사무소가 결과를 골라 종료한다 — 결과 · 종료 시각이 남고 맡은 사무소는 그대로다")
        void holder_closes_with_outcome() {
            for (RentalRequestOutcome outcome : RentalRequestOutcome.values()) {
                RentalRequest request = inProgress();

                request.close(OFFICE, outcome, LATER);

                assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.CLOSED);
                assertThat(request.getOutcome()).isEqualTo(outcome);
                assertThat(request.getClosedAt()).isEqualTo(LATER);
                assertThat(request.getOfficeId()).isEqualTo(OFFICE);
                assertThat(request.getClaimedAt()).isEqualTo(CLAIMED_AT);
                assertThat(request.getClosedReason()).isNull();
            }
        }

        @Test
        @DisplayName("결과 없이는 종료할 수 없다 — OUTCOME_REQUIRED, 진행 중 그대로")
        void outcome_is_required() {
            RentalRequest request = inProgress();
            Snapshot before = Snapshot.of(request);

            assertFailsWith(() -> request.close(OFFICE, null, LATER), RentalRequestErrorCode.RENTAL_REQUEST_OUTCOME_REQUIRED);

            assertThat(Snapshot.of(request)).isEqualTo(before);
        }

        @Test
        @DisplayName("다른 사무소 · 대기 요청은 종료할 수 없다 — NOT_FOUND")
        void non_holder_cannot_close() {
            RentalRequest held = inProgress();
            Snapshot before = Snapshot.of(held);

            assertFailsWith(() -> held.close(OTHER_OFFICE, RentalRequestOutcome.FOUND, LATER),
                    RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND);
            assertFailsWith(() -> submitted().close(OFFICE, RentalRequestOutcome.FOUND, LATER),
                    RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND);

            assertThat(Snapshot.of(held)).isEqualTo(before);
        }

        @Test
        @DisplayName("이미 종료 · 취소된 요청은 다시 종료할 수 없다 — NOT_IN_PROGRESS, 처음 결과가 그대로다")
        void terminal_cannot_be_closed_again() {
            RentalRequest done = closed();
            RentalRequest canceled = canceledAfterClaim();
            Snapshot doneBefore = Snapshot.of(done);
            Snapshot canceledBefore = Snapshot.of(canceled);

            assertFailsWith(() -> done.close(OFFICE, RentalRequestOutcome.NOT_PROCEEDED, LATER.plusDays(1)),
                    RentalRequestErrorCode.RENTAL_REQUEST_NOT_IN_PROGRESS);
            assertFailsWith(() -> canceled.close(OFFICE, RentalRequestOutcome.FOUND, LATER.plusDays(1)),
                    RentalRequestErrorCode.RENTAL_REQUEST_NOT_IN_PROGRESS);

            assertThat(Snapshot.of(done)).isEqualTo(doneBefore);
            assertThat(done.getOutcome()).isEqualTo(RentalRequestOutcome.FOUND);
            assertThat(Snapshot.of(canceled)).isEqualTo(canceledBefore);
        }
    }

    @Nested
    @DisplayName("대기 복귀(맡을 사람이 사라짐)")
    class ReturnToPool {

        @Test
        @DisplayName("진행 중 요청은 누구의 것이든 대기로 돌아간다 — 사무소 · 잡은 시각이 비워진다")
        void in_progress_returns_to_pool() {
            RentalRequest request = inProgress();

            request.returnToPool();

            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
            assertThat(request.getOfficeId()).isNull();
            assertThat(request.getClaimedAt()).isNull();
        }

        // 훅은 "그 사무소의 진행 중 요청"만 집지만, 사이에 상태가 바뀐 요청이 섞여도 종착 상태를 되살리지 않는다.
        @Test
        @DisplayName("대기 · 종료 · 취소 요청은 되돌리지 않는다 — NOT_IN_PROGRESS")
        void only_in_progress_returns() {
            for (RentalRequest request : new RentalRequest[]{
                    submitted(), closed(), canceledAfterClaim(), canceledWhileWaiting()}) {
                Snapshot before = Snapshot.of(request);

                assertFailsWith(request::returnToPool, RentalRequestErrorCode.RENTAL_REQUEST_NOT_IN_PROGRESS);

                assertThat(Snapshot.of(request)).isEqualTo(before);
            }
        }
    }

    @Nested
    @DisplayName("유학생 취소 · 수정")
    class UserEdits {

        @Test
        @DisplayName("대기 · 진행 중이면 고치고 취소할 수 있다")
        void editable_while_waiting_or_in_progress() {
            for (RentalRequest request : new RentalRequest[]{submitted(), inProgress()}) {
                request.updateConditions(LocalDate.of(2026, 12, 1), true, (short) 6,
                        null, 10_000_000L, null, 700_000L, (byte) 1, "건대입구역", "note");
                assertThat(request.getMoveInDate()).isEqualTo(LocalDate.of(2026, 12, 1));

                request.cancel("직접 구했습니다", LATER);

                assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.CANCELED);
                assertThat(request.getClosedReason()).isEqualTo("직접 구했습니다");
                assertThat(request.getClosedAt()).isEqualTo(LATER);
            }
        }

        // 맡았던 사무소의 "맡은 요청" 목록에 "취소됨"으로 남아야 한다. 휴대폰은 상태(진행 중일 때만)로 가려진다.
        @Test
        @DisplayName("진행 중에 취소해도 맡았던 사무소 · 잡은 시각은 남는다 — 결과는 비어 있다")
        void cancel_keeps_the_office() {
            RentalRequest request = canceledAfterClaim();

            assertThat(request.getOfficeId()).isEqualTo(OFFICE);
            assertThat(request.getClaimedAt()).isEqualTo(CLAIMED_AT);
            assertThat(request.getOutcome()).isNull();
        }

        @Test
        @DisplayName("종료 · 취소된 요청은 고칠 수도 다시 취소할 수도 없다 — REQUEST_NOT_EDITABLE")
        void terminal_is_frozen_for_user() {
            for (RentalRequest request : new RentalRequest[]{closed(), canceledAfterClaim(), canceledWhileWaiting()}) {
                Snapshot before = Snapshot.of(request);

                assertFailsWith(() -> request.cancel("또", LATER.plusDays(1)), RentalRequestErrorCode.REQUEST_NOT_EDITABLE);
                assertFailsWith(() -> request.updateConditions(LocalDate.of(2026, 12, 1), false, null,
                        null, null, null, null, null, null, null), RentalRequestErrorCode.REQUEST_NOT_EDITABLE);

                assertThat(Snapshot.of(request)).isEqualTo(before);
            }
        }
    }

    // 사무소 쪽 실패는 화면이 목록을 새로고침하게 하는 409 이고, 결과 누락만 입력 오류(400)다.
    @Test
    @DisplayName("잡기 · 놓기 · 종료의 오류 코드와 HTTP 상태")
    void error_codes_and_statuses() {
        assertThat(RentalRequestErrorCode.RENTAL_REQUEST_ALREADY_CLAIMED.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RentalRequestErrorCode.RENTAL_REQUEST_CLAIM_LIMIT.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RentalRequestErrorCode.RENTAL_REQUEST_NOT_IN_PROGRESS.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RentalRequestErrorCode.REQUEST_NOT_OPEN.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RentalRequestErrorCode.RENTAL_REQUEST_OUTCOME_REQUIRED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
