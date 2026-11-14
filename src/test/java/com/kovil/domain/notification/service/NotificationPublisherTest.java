package com.kovil.domain.notification.service;

import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.entity.UserNotification;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.notification.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

// ★ INV-09 — 이벤트 트랜잭션이 하는 일은 이 행을 넣는 것뿐이다.
// 여기서 FCM을 부르면 벤더 지연이 도메인 트랜잭션을 붙잡고,
// 롤백되면 이미 나간 푸시를 되돌릴 방법이 없다.
@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Mock NotificationOutboxRepository outboxRepository;
    @Mock UserNotificationRepository userNotificationRepository;

    private NotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new NotificationPublisher(outboxRepository, userNotificationRepository,
                JsonMapper.builder().build(), Clock.fixed(NOW, SEOUL));
        // 실제 저장소처럼 넣은 행을 돌려준다 — 발행이 그 행의 uuid 를 outbox 에 옮긴다. 중복 적재 테스트는 save 를 안 부르므로 lenient
        lenient().when(userNotificationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("PENDING 상태의 outbox 행 하나만 만든다 — 문구가 아니라 키와 치환 값을 담는다")
    void publishes_pending_outbox_row_only() {
        publisher.publish(10L, NotiType.RENTAL_REQUEST_IN_PROGRESS, "RENTAL_REQUEST_IN_PROGRESS:request:1",
                Map.of("0", "NGUYEN", "1", "700000"), "/rental-requests/abc");

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        then(outboxRepository).should().save(captor.capture());
        NotificationOutbox saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getTemplateCode()).isEqualTo("rental_request_in_progress");
        // 렌더된 문자열이 아니라 치환 값이다 — 언어 전환이 과거 알림에 소급되려면 이래야 한다.
        assertThat(saved.getParams()).contains("NGUYEN").contains("700000");
        assertThat(saved.getScheduledAt()).isEqualTo(LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
    }

    // FR-602 — 배치 이중 실행·재기동에도 알림은 1건이다.
    @Test
    @DisplayName("같은 dedupe_key가 이미 있으면 조용히 넘어간다")
    void duplicate_dedupe_key_is_ignored() {
        given(outboxRepository.existsByDedupeKey("RENTAL_REQUEST_IN_PROGRESS:request:1")).willReturn(true);

        publisher.publish(10L, NotiType.RENTAL_REQUEST_IN_PROGRESS, "RENTAL_REQUEST_IN_PROGRESS:request:1", Map.of(), null);

        then(outboxRepository).should(never()).save(any());
    }

    // FR-605 — 예약 발송(납부 3일 전 리마인더)이 같은 경로로 처리된다.
    @Test
    @DisplayName("예약 발송은 scheduled_at만 미래로 둔다 — 별도 경로가 없다")
    void scheduled_notification_uses_the_same_path() {
        LocalDateTime future = LocalDateTime.now(Clock.fixed(NOW, SEOUL)).plusDays(3);

        publisher.publishAt(10L, NotiType.LISTING_EXPIRED, "LISTING_EXPIRED:listing:1:2026-11",
                Map.of(), null, future);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        then(outboxRepository).should().save(captor.capture());
        assertThat(captor.getValue().getScheduledAt()).isEqualTo(future);
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("치환 값이 없으면 params는 null이다")
    void empty_params_is_null() {
        publisher.publish(10L, NotiType.VERIFICATION_APPROVED, "k", Map.of(), null);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        then(outboxRepository).should().save(captor.capture());
        assertThat(captor.getValue().getParams()).isNull();
    }

    // ★ 알림함은 사건의 사실이라 적재 시점에 함께 남는다.
    // 발송 워커에 두면 재시도마다 중복된다 — 실측에서 관리자 재시도로 확인했다.
    @Test
    @DisplayName("인앱 알림함도 같은 트랜잭션에서 함께 남는다")
    void inapp_notification_is_written_with_the_outbox() {
        publisher.publish(10L, NotiType.RENTAL_REQUEST_IN_PROGRESS, "k", Map.of("0", "NGUYEN"), "/x");

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        then(userNotificationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10L);
        assertThat(captor.getValue().getTemplateCode()).isEqualTo("rental_request_in_progress");
        assertThat(captor.getValue().getParams()).contains("NGUYEN");
    }

    // 푸시를 누른 앱이 이 uuid 로 알림함 행을 읽음 처리한다 — 두 행을 잇는 칸이 outbox 에 있어야 발송이 안다
    @Test
    @DisplayName("outbox 에 같은 발행의 알림함 행 uuid 가 담긴다")
    void outbox_carries_the_inbox_row_uuid() {
        given(userNotificationRepository.save(any())).willReturn(UserNotification.builder().uuid("un-1").build());

        publisher.publish(10L, NotiType.RENTAL_REQUEST_IN_PROGRESS, "k", Map.of(), "/x");

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        then(outboxRepository).should().save(captor.capture());
        assertThat(captor.getValue().getUserNotificationUuid()).isEqualTo("un-1");
    }

    @Test
    @DisplayName("중복 적재는 알림함도 만들지 않는다")
    void duplicate_creates_neither_row() {
        given(outboxRepository.existsByDedupeKey("k")).willReturn(true);

        publisher.publish(10L, NotiType.RENTAL_REQUEST_IN_PROGRESS, "k", Map.of(), null);

        then(userNotificationRepository).should(never()).save(any());
        then(outboxRepository).should(never()).save(any());
    }
}
