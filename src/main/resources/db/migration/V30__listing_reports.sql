CREATE TABLE listing_reports
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    listing_id       BIGINT       NOT NULL,
    reporter_user_id BIGINT       NOT NULL,
    reason_code      VARCHAR(30)  NOT NULL COMMENT 'FAKE_LISTING|ALREADY_RENTED|WRONG_INFO|FOREIGNER_REFUSED|SCAM|ETC',
    detail           VARCHAR(500) NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING → RESOLVED(차단 집행) | DISMISSED(기각). 처리 후 불변',
    handled_by       BIGINT       NULL,
    handled_at       DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lrep_uuid (uuid),
    UNIQUE KEY uq_lrep_listing_reporter (listing_id, reporter_user_id) COMMENT '같은 매물 중복 신고 차단(409 의 DB 축)',
    INDEX idx_lrep_status (status, id) COMMENT '관리자 처리 대기열',
    CONSTRAINT fk_lrep_listing  FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lrep_reporter FOREIGN KEY (reporter_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lrep_handler  FOREIGN KEY (handled_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '매물 신고 원장. 차단은 매물 status 가 담당 — 신고는 이력';

CREATE TABLE listing_block_logs
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    listing_id    BIGINT       NOT NULL,
    action        VARCHAR(10)  NOT NULL COMMENT 'BLOCK | UNBLOCK',
    reason        VARCHAR(500) NULL COMMENT '차단 사유 — 관리자만 본다(PO 2026-10-12, 중개사에게 숨김)',
    admin_user_id BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_lbl_listing (listing_id, id),
    CONSTRAINT fk_lbl_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lbl_admin   FOREIGN KEY (admin_user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '매물 차단 · 해제 이력. 행을 고치지 않는다';