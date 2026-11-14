package com.kovil.domain.notification.entity;

import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.exception.NotificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// BE_REQUEST_08_20 §1 — 재시도 가드. 화면 잠금은 예의이지 방어가 아니다:
// 두 관리자가 같은 행을 각자 누르면, 첫 재시도가 SENT로 만든 행에 두 번째 클릭이
// 다시 PENDING을 박아 **같은 푸시가 유저에게 한 번 더** 나갔다.
class NotificationOutboxRetryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);

    private NotificationOutbox outbox() {
        return NotificationOutbox.builder()
                .userId(10L).notiType(NotiType.RENTAL_REQUEST_IN_PROGRESS)
                .templateCode("RENTAL_REQUEST_IN_PROGRESS").params("{}")
                .status(OutboxStatus.PENDING)
                .scheduledAt(NOW.minusHours(1))
                .build();
    }

    private NotificationOutbox givenUp() {
        NotificationOutbox target = outbox();
        // maxAttempts=1이면 첫 실패가 곧 GIVEN_UP이다 — 실제 전이 경로로 만든다.
        target.markFailed("UNAVAILABLE", 1, Duration.ofMinutes(5), NOW);
        return target;
    }

    @Test
    @DisplayName("GIVEN_UP 행은 재시도된다 — 시도 횟수·오류가 초기화되고 즉시 집힌다")
    void given_up_is_retryable() {
        NotificationOutbox target = givenUp();

        target.retryByAdmin(NOW);

        assertThat(target.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(target.getAttemptCount()).isZero();
        assertThat(target.getLastError()).isNull();
        assertThat(target.getScheduledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이미 재시도돼 대기 중인 행은 거절된다 — 두 번째 관리자의 클릭")
    void pending_is_rejected() {
        NotificationOutbox target = givenUp();
        target.retryByAdmin(NOW);

        assertThatThrownBy(() -> target.retryByAdmin(NOW.plusSeconds(3)))
                .isInstanceOf(NotificationException.class)
                .extracting(e -> ((NotificationException) e).getErrorCode().getCode())
                .isEqualTo("NOTIFICATION_NOT_RETRYABLE");
    }

    @Test
    @DisplayName("발송 성공한 행은 거절된다 — 중복 푸시의 경로였다")
    void sent_is_rejected() {
        NotificationOutbox target = givenUp();
        target.retryByAdmin(NOW);
        target.markSent();

        assertThatThrownBy(() -> target.retryByAdmin(NOW.plusMinutes(1)))
                .isInstanceOf(NotificationException.class)
                .extracting(e -> ((NotificationException) e).getErrorCode().getCode())
                .isEqualTo("NOTIFICATION_NOT_RETRYABLE");
    }

    @Test
    @DisplayName("자동 재시도가 남은 FAILED 행도 거절된다 — 워커가 이미 다시 집는다")
    void failed_with_attempts_left_is_rejected() {
        NotificationOutbox target = outbox();
        target.markFailed("UNAVAILABLE", 5, Duration.ofMinutes(5), NOW);

        assertThat(target.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThatThrownBy(() -> target.retryByAdmin(NOW))
                .isInstanceOf(NotificationException.class)
                .extracting(e -> ((NotificationException) e).getErrorCode().getCode())
                .isEqualTo("NOTIFICATION_NOT_RETRYABLE");
    }
}
