ALTER TABLE notification_outbox
    ADD COLUMN user_notification_uuid CHAR(36) NULL COMMENT '같은 발행에서 만든 알림함 행(user_notifications.uuid) — 푸시 data 의 notificationUuid. 이 칸 전에 만든 행은 NULL' AFTER deep_link;