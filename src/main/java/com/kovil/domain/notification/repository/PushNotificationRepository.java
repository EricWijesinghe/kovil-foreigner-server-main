package com.kovil.domain.notification.repository;

import com.kovil.domain.notification.entity.PushNotification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushNotificationRepository extends JpaRepository<PushNotification, Long> {
}
