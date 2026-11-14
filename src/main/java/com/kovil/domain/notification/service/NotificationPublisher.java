package com.kovil.domain.notification.service;

import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.entity.UserNotification;
import com.kovil.domain.notification.repository.UserNotificationRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    private final NotificationOutboxRepository outboxRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public void publish(Long userId, NotiType type, String dedupeKey,
                        Map<String, Object> params, String deepLink) {
        publishAt(userId, type, dedupeKey, params, deepLink, LocalDateTime.now(clock));
    }

    public void publishAt(Long userId, NotiType type, String dedupeKey,
                          Map<String, Object> params, String deepLink, LocalDateTime scheduledAt) {

        if (outboxRepository.existsByDedupeKey(dedupeKey)) {
            log.debug("[notification] 중복 무시 dedupeKey={}", dedupeKey);
            return;
        }

        String paramsJson = (params == null || params.isEmpty())
                ? null : jsonMapper.writeValueAsString(params);

        UserNotification userNotification = userNotificationRepository.save(UserNotification.builder()
                .userId(userId)
                .notiType(type)
                .templateCode(type.templateCode())
                .params(paramsJson)
                .deepLink(deepLink)
                .build());

        outboxRepository.save(NotificationOutbox.builder()
                .userId(userId)
                .notiType(type)
                .dedupeKey(dedupeKey)
                .templateCode(type.templateCode())
                .params(paramsJson)
                .deepLink(deepLink)
                .userNotificationUuid(userNotification.getUuid())
                .scheduledAt(scheduledAt)
                .build());
    }
}
