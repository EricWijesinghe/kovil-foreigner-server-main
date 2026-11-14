package com.kovil.domain.notification.repository;

import com.kovil.domain.notification.entity.UserNotificationSetting;
import com.kovil.domain.notification.enums.NotiCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationSettingRepository extends JpaRepository<UserNotificationSetting, Long> {

    List<UserNotificationSetting> findByUserId(Long userId);

    Optional<UserNotificationSetting> findByUserIdAndCategory(Long userId, NotiCategory category);
}
