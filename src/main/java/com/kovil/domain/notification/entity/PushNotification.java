package com.kovil.domain.notification.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// FR-604 — **실제로 나간 문자열의 스냅샷**이다. 알림함과 달리 여기는 "무엇을 보냈나"의 사실이라
// 나중에 번들 문구가 바뀌어도 이 값은 움직이지 않는다.
// 발송 당시 언어를 함께 남긴다 — "문구가 이상하다"는 CS의 첫 확인 지점이다.
@Getter
@Entity
@SuperBuilder
@Table(name = "push_notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushNotification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "outbox_id", nullable = false)
    private Long outboxId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "device_count", nullable = false)
    private Integer deviceCount;

    // success_count < device_count면 만료 토큰 정리 대상이다(FR-607).
    @Column(name = "success_count", nullable = false)
    private Integer successCount;
}
