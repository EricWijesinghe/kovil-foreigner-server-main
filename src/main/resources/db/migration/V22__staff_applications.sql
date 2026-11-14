-- ★ 직원 가입 신청.
--
-- 왜 users 밖에 두는가: 가입은 그 자리에서 끝나고(계정은 실재한다), **역할만** 심사 대상이다.
-- 승인 전에는 일반 유저이므로 관리자 웹의 어떤 것도 볼 수 없고(권한 0개), 앱은 그대로 쓴다.
-- 승인은 users.role 을 바꾸는 한 줄이 되고, 거절은 기존 탈퇴 경로를 그대로 탄다 —
-- 그러면 번호·이메일 슬롯이 풀려 **재가입이 공짜로 열린다**(PO 확정 2026-09-17).
--
-- 반대안(승인 전에는 계정을 아예 만들지 않는다)을 버린 이유: 비밀번호 해시·전화 암호문·
-- 휴대폰 인증 토큰을 임시 테이블에 복제해야 하고, 재가입 허용 로직을 새로 짜야 한다.
-- 복제한 자격 증명은 지우는 것을 잊는 날 그대로 유출 표면이 된다.
CREATE TABLE staff_applications
(
    id             BIGINT                                   NOT NULL AUTO_INCREMENT,
    uuid           CHAR(36)                                 NOT NULL COMMENT '외부 노출 식별자',
    user_id        BIGINT                                   NOT NULL COMMENT '신청자. 계정은 이미 있다',
    requested_role VARCHAR(30)                              NOT NULL COMMENT 'UserRole 이름. ENUM 이 아닌 이유는 아래',
    status         ENUM ('PENDING','APPROVED','REJECTED')   NOT NULL DEFAULT 'PENDING',
    reject_reason  VARCHAR(255)                             NULL COMMENT '본인에게 보여줄 수 있어야 한다 — 내부 은어를 쓰지 않는다',
    reviewed_by    BIGINT                                   NULL COMMENT '심사한 관리자(users.id)',
    reviewed_at    DATETIME(6)                              NULL,
    created_at     DATETIME(6)                              NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)                              NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- ★ 대기 중인 신청은 사람당 **하나뿐**이다. 애플리케이션 검사만으로는 두 번 누른 요청
    --   둘이 같은 순간을 통과해 대기 신청이 두 건 생긴다 — 관리자가 같은 사람을 두 번 승인하고
    --   두 번째 승인이 첫 번째 역할을 덮는다. 처리되면(APPROVED/REJECTED) NULL 이 되어
    --   슬롯이 풀리므로 **재신청은 얼마든지 된다**(거절당해도 다시 신청할 수 있어야 한다).
    --   blocked_identities.active_guard · user_auth.active_identifier 와 같은 기법이다.
    pending_guard  BIGINT GENERATED ALWAYS AS
        (IF(status = 'PENDING', user_id, NULL)) STORED,

    PRIMARY KEY (id),
    UNIQUE KEY uq_sa_uuid (uuid),
    UNIQUE KEY uq_sa_pending (pending_guard),
    INDEX idx_sa_status (status, id) COMMENT '대기열 조회',
    INDEX idx_sa_user (user_id, id) COMMENT '내 신청 이력 — 거절 이력이 남아야 재신청을 알아본다',
    CONSTRAINT fk_sa_user     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sa_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '직원 가입 신청 (append-only 이력 — 거절해도 행을 지우지 않는다)';

-- requested_role 을 ENUM 으로 박지 않은 이유: 역할은 늘어난다(행정사 등). ENUM 이면 역할 하나에
-- ALTER TABLE 이 따라붙고, 그 순간 "역할 추가 = 코드 두 줄"이라는 이 설계의 전제가 깨진다.
-- 값의 유효성은 UserRole.from 이 읽는 자리에서 판정한다.
