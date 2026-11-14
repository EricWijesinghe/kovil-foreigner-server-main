package com.kovil.domain.notification.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.notification.enums.NotiType;
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

// 인앱 알림함. **푸시가 유일한 전달 수단이면 안 된다** —
// 푸시를 놓치거나 꺼 둔 유저의 복구 경로다.
//
// 렌더된 문자열이 아니라 키를 저장한다 — 언어를 바꾸면 **과거 알림도 그 언어로 읽힌다**.
@Getter
@Entity
@SuperBuilder
@Table(name = "user_notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "noti_type", nullable = false, length = 50)
    private NotiType notiType;

    @Column(name = "template_code", nullable = false, length = 60)
    private String templateCode;

    @Column(name = "params", columnDefinition = "json")
    private String params;

    @Column(name = "deep_link", length = 255)
    private String deepLink;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    // 이미 읽은 것을 다시 읽어도 시각을 덮어쓰지 않는다 — 처음 읽은 때가 사실이다.
    public void markRead(LocalDateTime at) {
        if (this.readAt == null) {
            this.readAt = at;
        }
    }
}
