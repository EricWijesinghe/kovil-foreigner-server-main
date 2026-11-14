package com.kovil.domain.notification.service;

import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

// ★ INV-09의 반대편 — **커밋된 outbox 행만 집어서** 발송한다.
// 이벤트 트랜잭션과 완전히 분리돼 있으므로 벤더 지연이 도메인을 붙잡지 않는다.
//
// 실제 발송은 NotificationDispatcher가 한다. 같은 클래스에 두면
// 자기호출이 프록시를 우회해 트랜잭션이 걸리지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWorker {

    private static final int BATCH_SIZE = 100;
    private static final List<OutboxStatus> RETRYABLE =
            Arrays.stream(OutboxStatus.values()).filter(OutboxStatus::isRetryable).toList();

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    // 한 건이 실패해도 나머지가 멈추면 안 된다 — 건마다 트랜잭션을 끊는다.
    public int drain() {
        List<NotificationOutbox> pending = outboxRepository
                // ★ FAILED를 함께 집는다. PENDING만 보면 한 번 실패한 알림이 영영 잊힌다 —
                // 기회를 다 쓴 건은 GIVEN_UP이 되어 이 조건에서 자연히 빠진다.
                .findByStatusInAndScheduledAtLessThanEqualOrderByIdAsc(
                        RETRYABLE, LocalDateTime.now(clock), PageRequest.of(0, BATCH_SIZE));

        int processed = 0;
        for (NotificationOutbox outbox : pending) {
            try {
                dispatcher.dispatch(outbox.getId());
                processed++;
            } catch (RuntimeException e) {
                log.warn("[notification] 발송 처리 실패 outbox={}", outbox.getId(), e);
                dispatcher.markFailed(outbox.getId(), e.getMessage());
            }
        }
        return processed;
    }
}
