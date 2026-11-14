package com.kovil.domain.notification.service;

import com.kovil.domain.notification.enums.NotiType;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.notification.dto.response.AdminOutboxResponse;
import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.exception.NotificationException;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import static com.kovil.domain.notification.exception.NotificationErrorCode.NOTIFICATION_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private final NotificationOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminOutboxResponse> findGivenUp(Pageable pageable) {
        return PageResponse.from(outboxRepository
                .findByStatusOrderByIdDesc(OutboxStatus.GIVEN_UP, pageable)
                .map(this::toResponse));
    }

    @Transactional
    public void retry(String outboxUuid) {
        NotificationOutbox outbox = outboxRepository.findByUuid(outboxUuid)
                .orElseThrow(() -> new NotificationException(NOTIFICATION_NOT_FOUND));

        outbox.retryByAdmin(LocalDateTime.now(clock));

        log.info("[notification] 관리자 재시도 outbox={}", outbox.getId());
    }

    private AdminOutboxResponse toResponse(NotificationOutbox outbox) {
        User recipient = userRepository.findById(outbox.getUserId()).orElse(null);
        return new AdminOutboxResponse(
                outbox.getUuid(),
                // v1의 발송 채널은 푸시 하나다 — 값이 늘어나면 outbox가 채널을 소유하게 된다.
                "PUSH",
                recipient == null ? null : recipient.getUuid(),
                recipient == null ? null : recipient.getName(),
                outbox.getNotiType().name(),
                outbox.getDedupeKey(),
                outbox.getAttemptCount(),
                outbox.getLastError(),
                outbox.getCreatedAt(),
                outbox.getUpdatedAt());
    }

    @Transactional
    public LocalDateTime sendTestPush(String adminUuid) {
        var admin = userQueryService.getActiveUser(adminUuid);
        var now = LocalDateTime.now(clock);
        notificationPublisher.publish(admin.getId(), NotiType.PUSH_TEST,
                "push-test:" + admin.getId() + ":" + now,
                Map.of("0", now.toString().substring(0, 16).replace('T', ' ')),
                "/verifications/me");
        return now;
    }
}
