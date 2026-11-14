-- 유저 상태 전이 이력 (append-only).
-- users.status 는 한 칸뿐이라 덮어쓰면 과거가 사라진다. 정지 이력이 사라지면
-- "이 사람을 왜 정지했었나"를 아무도 답할 수 없고, 재심사도 차단 판단도 근거가 없어진다.
CREATE TABLE user_status_histories
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    from_status VARCHAR(20)  NULL COMMENT '가입 직후 최초 기록이면 NULL',
    to_status   VARCHAR(20)  NOT NULL,
    changed_by  BIGINT       NULL COMMENT '전환시킨 사람(users.id). 본인 탈퇴면 본인, 정지면 ADMIN',
    actor_note  VARCHAR(100) NULL COMMENT 'SELF | ADMIN | SYSTEM — changed_by 만으로는 자발적 탈퇴와 관리자 조치가 구분되지 않는다',
    reason      VARCHAR(255) NULL COMMENT '정지 사유. 재심사와 차단 판단의 근거가 된다',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_ush_user (user_id, id) COMMENT '계정 타임라인',
    CONSTRAINT fk_ush_user  FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ush_actor FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '유저 상태 전이 이력 (append-only)';


-- ★ 재가입 차단 목록.
--
-- 왜 필요한가: 탈퇴하면 users.phone_hash 를 NULL 로 풀고 user_auth 를 해제해 재가입을 허용한다.
-- 그런데 정지 상태에서 그 경로가 열리면 그대로 세탁이 된다 — 정지 → 탈퇴 → 재가입이면 깨끗한 새 계정이다.
-- 신원을 계속 붙잡아 두는 것으로 막을 수도 있지만, 그러면 탈퇴자가 식별자를 계속 들고 있게 된다.
-- 그래서 탈퇴는 그대로 허용하되(개인정보 삭제), 차단이 필요한 신원만 이 목록으로 옮긴다.
--
-- ★ 실제로 쓰는 축은 **PHONE 하나뿐이다**(PO 확정 2026-09-01). 소셜 가입도 휴대폰 인증을 강제하고
--    (FR-104), 계정 생성·소셜 연결이 전부 SignUpProcessor.loadUnconsumed 를 지나므로 번호가 단일 관문이다.
--    수단 축(LOCAL·소셜)은 지금 아무도 등록하지 않는다. 정지 계정은 탈퇴가 불가능해 user_auth 가
--    살아 있고, 기존 조회가 그 계정을 집어 SUSPENDED_USER 로 끊기 때문에 중복이다.
--    타입 축을 남겨 둔 이유: 탈퇴한 계정을 정지할 수 있게 되면 그때 수단 등록만 되살리면 되고,
--    이 컬럼이 있으므로 마이그레이션이 필요 없다.
--
-- 판정: released_at IS NULL 인 행이 있으면 가입 거부. **차단은 전부 영구다**(PO 확정 2026-09-01).
-- 기간제(N일 정지)는 만들지 않는다 — 만료를 넣으면 앱 판정과 아래 active_guard 가 어긋나
-- 만료된 차단이 슬롯을 붙잡은 채 재차단이 유니크 위반으로 터진다. 필요해지면 그때 둘을 함께 고친다.
CREATE TABLE blocked_identities
(
    id             BIGINT                                              NOT NULL AUTO_INCREMENT,
    identity_type  ENUM ('PHONE','LOCAL','APPLE','GOOGLE','FACEBOOK')  NOT NULL COMMENT '차단 축. PHONE 은 번호, 나머지는 로그인 수단',
    identity_value VARCHAR(255)                                        NOT NULL COMMENT 'PHONE=phone_hash(users 와 같은 키로 계산해야 대조가 성립) / LOCAL=이메일 / 소셜=provider sub',
    reason         VARCHAR(255)                                        NULL COMMENT '차단 사유. 본인에게 보여줄 수 있어야 하므로 내부 은어를 쓰지 않는다',
    source_user_id BIGINT                                              NULL COMMENT '어느 계정에서 비롯됐는지. 탈퇴로 익명화돼도 행은 남아 추적이 된다',
    blocked_by     BIGINT                                              NULL COMMENT '차단한 ADMIN. NULL = 시스템 자동',
    blocked_at     DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    released_at    DATETIME(6)                                         NULL COMMENT '차단 해제(오탐 구제·정지 해제). 행을 지우지 않고 해제로 남긴다 — 지우면 왜 풀렸는지가 사라진다',
    released_by    BIGINT                                              NULL,
    created_at     DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    -- 해제되지 않은 동안만 값을 갖는다. 유니크가 걸려 있어 같은 신원에 활성 차단이 둘 생길 수 없고,
    -- 해제되면 NULL 이 되어 슬롯이 풀리므로 같은 신원을 나중에 다시 차단할 수 있다(이력은 계속 쌓인다).
    -- ★ 이 조건이 앱의 판정(BlockedIdentityRepository.existsActive)과 **정확히 같아야** 한다.
    --   어긋나면 앱은 "차단 없음"이라 보고 insert 하는데 DB 는 슬롯이 찼다며 거부한다.
    active_guard   VARCHAR(300) GENERATED ALWAYS AS
        (IF(released_at IS NULL, CONCAT(identity_type, ':', identity_value), NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bi_active (active_guard),
    INDEX idx_bi_identity (identity_type, identity_value, id) COMMENT '차단 이력 조회(해제된 것 포함)',
    CONSTRAINT fk_bi_source   FOREIGN KEY (source_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_bi_blocker  FOREIGN KEY (blocked_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_bi_releaser FOREIGN KEY (released_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '재가입 차단 목록. 탈퇴로 신원을 풀어줘도 이 목록이 남아 정지 세탁을 막는다';
