package com.kovil.domain.notification.service;

import com.kovil.domain.device.entity.DeviceToken;
import com.kovil.domain.device.repository.DeviceTokenRepository;
import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.entity.PushNotification;
import com.kovil.domain.notification.enums.NotiCategory;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.notification.repository.PushNotificationRepository;
import com.kovil.domain.notification.repository.UserNotificationSettingRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.push.PushMessage;
import com.kovil.infra.push.PushSendResult;
import com.kovil.infra.push.PushSender;
import com.kovil.domain.notification.NotificationProperties;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationOutboxRepository outboxRepository;
    private final PushNotificationRepository pushNotificationRepository;
    private final UserNotificationSettingRepository settingRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final NotificationRenderer renderer;
    private final PushSender pushSender;
    private final NotificationProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(Long outboxId) {
        NotificationOutbox outbox = outboxRepository.findById(outboxId).orElse(null);
        if (outbox == null || !outbox.getStatus().isRetryable()) {
            return;
        }
        User user = userRepository.findById(outbox.getUserId()).orElse(null);
        if (user == null) {
            outbox.markSkipped("수신자를 찾을 수 없다");
            return;
        }

        if (!isCategoryEnabled(user.getId(), outbox)) {
            outbox.markSkipped("수신 설정 OFF");
            return;
        }

        List<DeviceToken> devices = deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(user.getId());
        if (devices.isEmpty()) {
            outbox.markSkipped("등록된 기기 없음");
            return;
        }

        Locale locale = Locale.forLanguageTag(
                user.getPreferredLanguage() == null ? "en" : user.getPreferredLanguage());
        NotificationRenderer.Rendered rendered =
                renderer.render(outbox.getTemplateCode(), outbox.getParams(), locale);

        int success = 0;
        Map<String, String> data = pushData(outbox);
        List<DeviceToken> dead = new ArrayList<>();

        Map<String, Integer> failureDetails = new LinkedHashMap<>();
        for (DeviceToken device : devices) {
            PushSendResult result = pushSender.send(PushMessage.of(
                    device.getToken(), rendered.title(), rendered.body(), data));

            if (result.isSuccess()) {
                success++;
            } else {
                String detail = result.detail() == null ? "원인 미상" : result.detail();
                failureDetails.merge(detail, 1, Integer::sum);
            }
            if (result.isTokenInvalid()) {
                dead.add(device);
            }
        }
        deviceTokenRepository.deleteAll(dead);

        pushNotificationRepository.save(PushNotification.builder()
                .outboxId(outbox.getId())
                .userId(user.getId())
                .title(rendered.title())
                .body(rendered.body())
                .languageCode(locale.getLanguage())
                .deviceCount(devices.size())
                .successCount(success)
                .build());

        if (success > 0) {
            outbox.markSent();
        } else {
            fail(outbox, "모든 기기 발송 실패 (기기 " + devices.size() + "대) · " + summarize(failureDetails));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long outboxId, String error) {
        outboxRepository.findById(outboxId).ifPresent(outbox -> fail(outbox, error));
    }

    private Map<String, String> pushData(NotificationOutbox outbox) {
        Map<String, String> data = new LinkedHashMap<>();
        if (outbox.getDeepLink() != null) {
            data.put("deepLink", outbox.getDeepLink());
        }
        data.put("notiType", outbox.getNotiType().name());
        if (outbox.getUserNotificationUuid() != null) {
            data.put("notificationUuid", outbox.getUserNotificationUuid());
        }
        return data;
    }

    private String summarize(Map<String, Integer> details) {
        return details.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(entry -> entry.getKey() + "\u00d7" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("원인 미상");
    }

    private void fail(NotificationOutbox outbox, String error) {
        outbox.markFailed(error, properties.maxAttempts(),
                properties.retryBackoff(), LocalDateTime.now(clock));
        if (outbox.getStatus() == OutboxStatus.GIVEN_UP) {
            log.error("[notification] 발송 포기 outbox={} 시도={}회 — 관리자 확인 필요",
                    outbox.getId(), outbox.getAttemptCount());
        }
    }

    private boolean isCategoryEnabled(Long userId, NotificationOutbox outbox) {
        NotiCategory category = outbox.getNotiType().getCategory();
        if (!category.isToggleable()) {
            return true;
        }
        return settingRepository.findByUserIdAndCategory(userId, category)
                .map(setting -> Boolean.TRUE.equals(setting.getEnabled()))
                .orElse(true);
    }
}
