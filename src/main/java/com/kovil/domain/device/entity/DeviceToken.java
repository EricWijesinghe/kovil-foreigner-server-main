package com.kovil.domain.device.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.device.enums.DevicePlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@SuperBuilder
@Table(name = "device_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 전역 유니크다. 같은 기기를 다른 사람이 쓰면 행을 새로 만드는 게 아니라 소유자를 옮긴다.
    @Column(name = "token", nullable = false, updatable = false, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    public static DeviceToken register(
            Long userId, String token, DevicePlatform platform, LocalDateTime now) {
        return DeviceToken.builder()
                .userId(userId)
                .token(token)
                .platform(platform)
                .lastUsedAt(now)
                .build();
    }

    // 기기를 넘겨받은 사람에게 소유자를 이전한다. 이전하지 않으면 이전 사용자에게
    // 새 사용자의 알림이 계속 간다 — 토큰이 유니크라 새 행을 만들 수도 없다.
    public void reassignTo(Long userId, DevicePlatform platform, LocalDateTime now) {
        this.userId = userId;
        this.platform = platform;
        this.lastUsedAt = now;
    }
}
