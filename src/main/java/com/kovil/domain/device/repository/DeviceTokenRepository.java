package com.kovil.domain.device.repository;

import com.kovil.domain.device.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findByToken(String token);

    // 해제는 반드시 소유자까지 함께 본다 — uuid만으로 지우면 남의 기기를 끊을 수 있다.
    Optional<DeviceToken> findByUuidAndUserId(String uuid, Long userId);

    List<DeviceToken> findAllByUserIdOrderByLastUsedAtDesc(Long userId);

    void deleteAllByUserId(Long userId);
}
