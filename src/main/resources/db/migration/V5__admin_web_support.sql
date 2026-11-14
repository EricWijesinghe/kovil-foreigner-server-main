-- ① 수납 되돌림의 흔적: 행을 지우지 않고 CANCELED로 남기는 것만으로는
--    "언제·왜 되돌렸나"가 남지 않는다. 취소선 옆에 붙일 재료를 여기 둔다.
ALTER TABLE rent_payments
    ADD COLUMN canceled_at   DATETIME(6)  NULL COMMENT '되돌림 시각. NULL = 살아 있는 수납' AFTER confirmed_at,
    ADD COLUMN cancel_reason VARCHAR(255) NULL COMMENT '왜 되돌렸는지(내부용). 되돌림은 1클릭이라 비어 있을 수 있다' AFTER canceled_at;

-- ② 오승인 무효화. 승인된 인증의 full_name이 그대로 계약 명의가 되므로
--    되돌릴 수단이 없으면 잘못된 이름의 계약이 나간다.
--    reject_reason을 재사용하지 않는 이유: "반려했다"와 "승인했다가 취소했다"는 다른 사실이고,
--    reviewed_by/at을 덮어쓰면 원래 누가 승인했는지가 사라진다.
ALTER TABLE foreigner_verifications
    MODIFY COLUMN status ENUM ('PENDING_REVIEW','APPROVED','REJECTED','REVOKED') NOT NULL DEFAULT 'PENDING_REVIEW'
        COMMENT 'REVOKED = 승인 후 무효화. 승인 이력(reviewed_by/at)은 그대로 남는다',
    ADD COLUMN revoked_by    BIGINT       NULL COMMENT '무효화한 관리자' AFTER reviewed_at,
    ADD COLUMN revoked_at    DATETIME(6)  NULL AFTER revoked_by,
    ADD COLUMN revoke_reason VARCHAR(255) NULL COMMENT '무효화 사유(필수 입력, 내부 기록)' AFTER revoked_at,
    ADD CONSTRAINT fk_fv_revoker FOREIGN KEY (revoked_by) REFERENCES users (id) ON DELETE RESTRICT;

-- ③ 정지 화면이 "언제·왜"를 말할 수 있게 한다. status만으로는 정지 이력이 시각을 잃는다.
ALTER TABLE users
    ADD COLUMN suspended_at   DATETIME(6)  NULL COMMENT '정지 시각. 해제 시 NULL로 되돌린다' AFTER status,
    ADD COLUMN suspend_reason VARCHAR(255) NULL COMMENT '정지 사유(내부용). 유저 응답에 실리지 않는다' AFTER suspended_at;

-- ④ 임대 요청 메모를 목록으로. 단일 컬럼 덮어쓰기는 통화 기록을 지운다 —
--    "지난주에 뭐라고 했더라"가 운영에서 가장 자주 필요한 정보다.
CREATE TABLE rental_request_memos
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    uuid              CHAR(36)     NOT NULL,
    rental_request_id BIGINT       NOT NULL,
    content           VARCHAR(1000) NOT NULL COMMENT '통화 기록 한 건. 유저 응답에 절대 실리지 않는다',
    created_by        BIGINT       NOT NULL COMMENT '작성 담당자',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rrm_uuid (uuid),
    INDEX idx_rrm_request (rental_request_id, id) COMMENT '요청별 메모 타임라인',
    CONSTRAINT fk_rrm_request FOREIGN KEY (rental_request_id) REFERENCES rental_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rrm_author  FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '임대 요청 내부 메모(append-only). 삭제·수정 경로 없음';

-- 기존 단일 메모를 첫 행으로 옮긴다. 작성자를 알 수 없으므로 배정 담당자로 귀속하고,
-- 배정 전이면 옮길 곳이 없어 버린다(그 상태의 메모는 존재할 수 없다).
INSERT INTO rental_request_memos (uuid, rental_request_id, content, created_by, created_at)
SELECT UUID(), r.id, r.manager_memo, r.assigned_manager_id, r.updated_at
FROM rental_requests r
WHERE r.manager_memo IS NOT NULL
  AND r.manager_memo <> ''
  AND r.assigned_manager_id IS NOT NULL;

ALTER TABLE rental_requests
    DROP COLUMN manager_memo;

-- ⑤ 전화번호 평문 열람 기록. 화면이 "열람 기록이 남습니다"라고 적으려면
--    실제로 남아야 한다 — 지킬 수 없는 약속은 쓰지 않는다.
--    상세 응답에서 평문을 빼고 이 경로로만 내주기 때문에, 이 표가 곧 열람의 전부다.
CREATE TABLE admin_phone_reveals
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    admin_user_id  BIGINT      NOT NULL COMMENT '열람한 관리자',
    target_user_id BIGINT      NOT NULL COMMENT '열람 대상 회원',
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_apr_target (target_user_id, id) COMMENT '"이 회원의 번호를 누가 봤나"',
    INDEX idx_apr_admin (admin_user_id, id) COMMENT '"이 관리자가 무엇을 봤나"',
    CONSTRAINT fk_apr_admin  FOREIGN KEY (admin_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_apr_target FOREIGN KEY (target_user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '전화번호 평문 열람 감사 기록(append-only)';
