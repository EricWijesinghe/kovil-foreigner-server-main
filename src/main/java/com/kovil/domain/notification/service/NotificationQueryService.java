package com.kovil.domain.notification.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.notification.dto.response.NotificationResponse;
import com.kovil.domain.notification.dto.response.NotificationSettingResponse;
import com.kovil.domain.notification.entity.UserNotification;
import com.kovil.domain.notification.entity.UserNotificationSetting;
import com.kovil.domain.notification.enums.NotiCategory;
import com.kovil.domain.notification.exception.NotificationException;
import com.kovil.domain.notification.repository.UserNotificationRepository;
import com.kovil.domain.notification.repository.UserNotificationSettingRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kovil.domain.notification.exception.NotificationErrorCode.NOTIFICATION_CATEGORY_NOT_TOGGLEABLE;
import static com.kovil.domain.notification.exception.NotificationErrorCode.NOTIFICATION_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private final UserQueryService userQueryService;
    private final UserNotificationRepository notificationRepository;
    private final UserNotificationSettingRepository settingRepository;
    private final NotificationRenderer renderer;
    private final Clock clock;

    // ★ 문구를 **조회 시점의 언어로** 렌더한다 — 저장된 것은 키와 치환 값뿐이다.
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> findMine(String userUuid, Pageable pageable) {
        User user = userQueryService.getActiveUser(userUuid);
        Locale locale = LocaleContextHolder.getLocale();

        var page = notificationRepository.findByUserIdOrderByIdDesc(user.getId(), pageable);
        return PageResponse.from(new PageImpl<>(
                page.getContent().stream().map(notification -> toResponse(notification, locale)).toList(),
                pageable, page.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public long countUnread(String userUuid) {
        return notificationRepository.countByUserIdAndReadAtIsNull(
                userQueryService.getActiveUser(userUuid).getId());
    }

    @Transactional
    public void markRead(String userUuid, String notificationUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        notificationRepository.findByUuidAndUserId(notificationUuid, user.getId())
                .orElseThrow(() -> new NotificationException(NOTIFICATION_NOT_FOUND))
                .markRead(LocalDateTime.now(clock));
    }

    @Transactional
    public void markAllRead(String userUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        LocalDateTime now = LocalDateTime.now(clock);
        notificationRepository.findByUserIdOrderByIdDesc(user.getId(), Pageable.unpaged())
                .forEach(notification -> notification.markRead(now));
    }

    // 토글 가능한 카테고리만 내보낸다. 행이 없으면 ON이다.
    @Transactional(readOnly = true)
    public List<NotificationSettingResponse> findSettings(String userUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        Map<NotiCategory, Boolean> saved = settingRepository.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(UserNotificationSetting::getCategory,
                        setting -> Boolean.TRUE.equals(setting.getEnabled()), (a, b) -> b));

        return Arrays.stream(NotiCategory.values())
                .filter(NotiCategory::isToggleable)
                .map(category -> new NotificationSettingResponse(
                        category.name(), saved.getOrDefault(category, true)))
                .toList();
    }

    @Transactional
    public List<NotificationSettingResponse> changeSetting(String userUuid, String rawCategory,
                                                           boolean enabled) {
        User user = userQueryService.getActiveUser(userUuid);
        NotiCategory category = parseToggleable(rawCategory);
        LocalDateTime now = LocalDateTime.now(clock);

        settingRepository.findByUserIdAndCategory(user.getId(), category)
                .ifPresentOrElse(
                        setting -> setting.change(enabled, now),
                        () -> settingRepository.save(UserNotificationSetting.builder()
                                .userId(user.getId()).category(category)
                                .enabled(enabled).updatedAt(now).build()));

        return findSettings(userUuid);
    }

    private NotiCategory parseToggleable(String rawCategory) {
        NotiCategory category = Arrays.stream(NotiCategory.values())
                .filter(value -> value.name().equalsIgnoreCase(rawCategory))
                .findFirst()
                .orElseThrow(() -> new NotificationException(NOTIFICATION_CATEGORY_NOT_TOGGLEABLE));
        if (!category.isToggleable()) {
            throw new NotificationException(NOTIFICATION_CATEGORY_NOT_TOGGLEABLE);
        }
        return category;
    }

    private NotificationResponse toResponse(UserNotification notification, Locale locale) {
        NotificationRenderer.Rendered rendered =
                renderer.render(notification.getTemplateCode(), notification.getParams(), locale);
        return new NotificationResponse(
                notification.getUuid(),
                notification.getNotiType().name(),
                notification.getNotiType().getCategory().name(),
                rendered.title(),
                rendered.body(),
                notification.getDeepLink(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
