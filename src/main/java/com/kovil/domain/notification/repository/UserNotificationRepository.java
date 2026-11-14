package com.kovil.domain.notification.repository;

import com.kovil.domain.notification.entity.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    Page<UserNotification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Optional<UserNotification> findByUuidAndUserId(String uuid, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);
}
