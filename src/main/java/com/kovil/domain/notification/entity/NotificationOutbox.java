package com.kovil.domain.notification.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.exception.NotificationErrorCode;
import com.kovil.domain.notification.exception.NotificationException;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

// ★ INV-09가 여기서 시작한다. 이벤트 트랜잭션은 **이 행을 넣는 것까지만** 하고
// FCM 호출은 커밋 뒤 워커가 한다 — 롤백된 트랜잭션의 푸시는 되돌릴 방법이 없다.
@Getter
@Entity
@SuperBuilder
@Table(name = "notification_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 500;

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

    // FR-602 — 멱등 축. 타입마다 다른 키 조합을 **문자열 하나로 흡수**한다.
    // UNIQUE라 배치 이중 실행·재기동에도 알림은 1건이다.
    @Column(name = "dedupe_key", nullable = false, updatable = false, length = 150)
    private String dedupeKey;

    @Column(name = "template_code", nullable = false, length = 60)
    private String templateCode;

    // FR-603 — 치환 값만 저장한다. 렌더된 문자열이 아니다.
    @Column(name = "params", columnDefinition = "json")
    private String params;

    @Column(name = "deep_link", length = 255)
    private String deepLink;

    @Column(name = "user_notification_uuid", updatable = false, length = 36)
    private String userNotificationUuid;

    // FR-605 — 워커는 이 값이 지난 행만 집는다. 예약 발송이 같은 경로로 처리된다.
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.attemptCount = this.attemptCount + 1;
        this.lastError = null;
    }

    // 재시도하지 않되 기록은 남긴다 — 왜 안 갔는지 물어보는 CS가 반드시 온다.
    public void markSkipped(String reason) {
        this.status = OutboxStatus.SKIPPED;
        this.attemptCount = this.attemptCount + 1;
        this.lastError = truncate(reason);
    }

    // 실패는 두 갈래다.
    //  · 아직 기회가 남았으면 FAILED로 두고 다음 시각에 다시 집게 한다(지수적으로 뒤로 민다).
    //  · 기회를 다 썼으면 GIVEN_UP — **자동 재시도를 멈추고 사람에게 넘긴다.**
    //    무한 재시도는 벤더 장애 때 큐를 태우고, 조용한 포기는 알림을 잃는다.
    public void markFailed(String error, int maxAttempts, Duration backoff, LocalDateTime now) {
        this.attemptCount = this.attemptCount + 1;
        this.lastError = truncate(error);

        if (this.attemptCount >= maxAttempts) {
            this.status = OutboxStatus.GIVEN_UP;
            return;
        }
        this.status = OutboxStatus.FAILED;
        // 시도를 거듭할수록 간격을 벌린다 — 벤더가 흔들릴 때 같은 속도로 두드리면 더 나빠진다.
        this.scheduledAt = now.plus(backoff.multipliedBy(this.attemptCount));
    }

    // ADMIN이 손으로 다시 큐에 올린다. 시도 횟수는 0으로 되돌려 기회를 새로 준다.
    // ★ GIVEN_UP에서만 — 가드가 없으면 이미 SENT인 행을 두 번째 관리자가 눌러
    //   **같은 푸시가 유저에게 한 번 더** 나간다. 화면 잠금은 예의이지 방어가 아니다.
    public void retryByAdmin(LocalDateTime now) {
        if (this.status != OutboxStatus.GIVEN_UP) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_NOT_RETRYABLE);
        }
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.lastError = null;
        this.scheduledAt = now;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
