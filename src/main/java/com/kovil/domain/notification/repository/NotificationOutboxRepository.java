package com.kovil.domain.notification.repository;

import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    // FR-605 — 워커 폴링 축. 예약 발송과 재시도가 같은 경로로 처리된다.
    // FAILED를 함께 집는 것이 핵심이다 — PENDING만 보면 한 번 실패한 알림이 영영 잊힌다.
    List<NotificationOutbox> findByStatusInAndScheduledAtLessThanEqualOrderByIdAsc(
            Collection<OutboxStatus> statuses, LocalDateTime scheduledAt, Pageable pageable);

    Optional<NotificationOutbox> findByUuid(String uuid);

    // 포기 대기열 — 사람이 봐야 하는 것들이다.
    Page<NotificationOutbox> findByStatusOrderByIdDesc(OutboxStatus status, Pageable pageable);
}
