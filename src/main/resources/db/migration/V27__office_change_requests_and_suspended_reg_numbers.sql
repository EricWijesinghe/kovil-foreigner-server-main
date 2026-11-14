-- 승인된 사무소의 정보 변경 = 변경 신청 · 정지된 중개등록번호의 이력 (PO 결정 2026-10-07).
--
-- ★ 승인된 공인중개사가 심사 대상(등록번호 · 상호 · 대표자 · 소재지 · 사업자번호 · 등록증)을 바꿔도 부문은 대기로
--   떨어지지 않는다. 바꿀 값 한 벌을 변경 신청으로 따로 받아 두고, 승인되면 그때 사무소에 적용한다. 반려되면 사무소는
--   그대로다 — 옛 번호도 계속 잡는다(그 번호로 승인받아 영업 중이다). 그래서 심사 동안에도 매물 공개 · 기능이 그대로다.
--   대표번호는 여기 오지 않는다 — 심사 없이 바로 바뀌는 칸이다.
--
-- ★ 정지된 사무소의 등록번호를 이력으로 남긴다. 나중에 같은 번호로 신청 · 변경 신청이 오면 심사 화면이
--   "정지 이력이 있는 중개등록번호"라고 경고한다 — 막지는 않는다(관리자가 판단한다). 번호 중복은 지금처럼 승인에서
--   막는다(claimed_reg_no). 정지 중 탈퇴 불가도 그대로다 — 이력은 그 위의 두 번째 장치다.
--
-- ★ 부문 행에 그 부문의 사무소를 남긴다(partner_divisions.office_id). 대표자 연결은 탈퇴가 지우므로, 탈퇴한 파트너의 승인
--   부문을 정지할 때도 정지 이력이 그 사무소의 번호를 찾을 수 있게.

-- ① 변경 신청. 사무소당 대기 건은 하나다 — DB 제약이 아니라 코드가 사무소 행을 잠그고 지킨다(대기 건이 있으면 새로
--    만들지 않고 덮는다). 판정이 난 건(승인 · 반려)은 지우지 않는다 — 무엇을 언제 바꿨는지의 이력이다.
--    ★ 바꿀 값은 한 벌 통째로 담는다 — 심사자는 "지금 값"과 "바꿀 값"을 나란히 보고 한 벌을 승인한다.
CREATE TABLE brokerage_office_change_requests
(
    id              BIGINT                                NOT NULL AUTO_INCREMENT,
    uuid            CHAR(36)                              NOT NULL COMMENT '외부 노출 식별자',
    office_id       BIGINT                                NOT NULL COMMENT '바꿀 중개사무소',
    requested_by    BIGINT                                NOT NULL COMMENT '신청한 파트너(users.id) — 그 사무소의 대표자. 승인 · 반려 알림을 받는다',
    reg_no          VARCHAR(20)                           NOT NULL COMMENT '바꿀 중개등록번호. 승인되면 사무소가 이 번호를 잡고 옛 번호를 놓는다. 정지 이력 경고도 이 번호로 찾는다',
    name            VARCHAR(100)                          NOT NULL COMMENT '바꿀 상호',
    ceo_name        VARCHAR(50)                           NOT NULL COMMENT '바꿀 대표자 성명',
    address         VARCHAR(255)                          NOT NULL COMMENT '바꿀 소재지',
    biz_no          VARCHAR(12)                           NULL COMMENT '바꿀 사업자등록번호',
    license_file_id BIGINT                                NOT NULL COMMENT '심사받을 등록증 사본(files). 지금 사무소의 등록증을 그대로 낼 수도 있다 — 그 파일은 반려돼도 폐기하지 않는다',
    status          ENUM ('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING 동안 사무소 · 부문은 그대로다. APPROVED = 사무소에 적용됨, REJECTED = 사무소 그대로',
    submitted_at    DATETIME(6)                           NOT NULL COMMENT '마지막으로 낸 시각. 대기 중에 다시 내면 덮어쓰고 이 시각이 새로 찍힌다 — 대기열은 이 순서다',
    reject_reason   VARCHAR(255)                          NULL COMMENT '반려 사유. 신청자에게 그대로 보여 준다 — 내부 은어를 쓰지 않는다',
    reviewed_by     BIGINT                                NULL COMMENT '승인 · 반려한 관리자(users.id). 탈퇴로 닫힌 건은 비어 있다',
    reviewed_at     DATETIME(6)                           NULL,
    version         BIGINT                                NOT NULL DEFAULT 0 COMMENT '낙관적 락 — 관리자가 본 값과 다르면(그사이 덮어씀 · 다른 처리) 심사를 거절한다',
    created_at      DATETIME(6)                           NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)                           NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bocr_uuid (uuid),
    INDEX idx_bocr_office (office_id, status, id) COMMENT '사무소의 대기 건 · 최근 건',
    INDEX idx_bocr_queue (status, submitted_at, id) COMMENT '변경 심사 대기열',
    INDEX idx_bocr_reg_no (reg_no, status) COMMENT '같은 번호로 대기 중인 변경 신청(심사 화면의 같은 번호의 다른 신청)',
    CONSTRAINT fk_bocr_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bocr_requester FOREIGN KEY (requested_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bocr_license_file FOREIGN KEY (license_file_id) REFERENCES files (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bocr_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '승인된 중개사무소의 정보 변경 신청 — 승인되면 사무소에 적용, 반려되면 사무소 그대로. 심사 동안 매물 · 기능은 그대로다';

-- ② 부문이 어느 사무소의 것인지를 부문 행에 남긴다. 지금은 대표자 연결(broker_profiles)로만 찾는데, 대표자가 탈퇴하면
--    그 연결이 지워져 사무소(와 번호)를 찾을 길이 없다 — 탈퇴한 파트너의 승인 부문을 정지하면 정지 이력이 남지 않았다.
--    ★ 가입 때 채우고 **탈퇴해도 지우지 않는다**(부문 행 자체가 지워지지 않는 이력이다). 공인중개사 부문만 채운다 —
--      다른 부문은 사무소가 없다.
--    ★ 지금 연결로 백필한다. 이 마이그레이션 전에 탈퇴해 연결이 이미 지워진 부문은 비어 있다(되살릴 근거가 없다).
ALTER TABLE partner_divisions
    ADD COLUMN office_id BIGINT NULL
        COMMENT '이 부문의 중개사무소(공인중개사 부문). 가입 때 채우고 탈퇴해도 지우지 않는다 — 정지 이력이 이 연결로 번호를 찾는다' AFTER division,
    ADD INDEX idx_pd_office (office_id) COMMENT '사무소의 부문',
    ADD CONSTRAINT fk_pd_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT;
--    ★ updated_at 을 제 값으로 다시 적는다 — 그 칼럼은 ON UPDATE CURRENT_TIMESTAMP 라 두면 백필이 모든 부문의 갱신 시각을
--      마이그레이션 시각으로 덮고, 아래 ④ 가 근사값으로 쓰는 정지 시각까지 마이그레이션 시각이 된다(MySQL 실측).
UPDATE partner_divisions pd
    JOIN broker_profiles bp ON bp.user_id = pd.user_id
SET pd.office_id  = bp.office_id,
    pd.updated_at = pd.updated_at
WHERE pd.division = 'REAL_ESTATE_BROKER';

-- ③ 정지된 중개등록번호의 이력(append-only). 부문을 정지할 때마다 한 행 — 복구해도 지우지 않는다.
--    ★ 번호로 찾고, 그 사무소로도 찾는다. 번호만 보면 정지됐던 사무소가 변경 승인으로 번호를 바꿨을 때 자기 경고가 사라지고,
--      사무소만 보면 대표자가 떠나고 같은 번호로 새 사람이 신청할 때 이력이 끊긴다 — 그래서 둘 다(심사 화면이 OR 로 찾는다).
--    ★ 상호는 저장하지 않고 office_id 로 그 사무소의 지금 상호를 읽는다 — 사무소 행은 지워지지 않는다.
CREATE TABLE suspended_reg_numbers
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    reg_no       VARCHAR(20)  NOT NULL COMMENT '정지 당시 그 사무소가 잡고 있던 중개등록번호(잡은 번호가 없으면 신청 번호)',
    office_id    BIGINT       NOT NULL COMMENT '정지된 사무소',
    division_id  BIGINT       NOT NULL COMMENT '정지된 공인중개사 부문',
    suspended_by BIGINT       NULL COMMENT '정지한 관리자(users.id). 이관된 옛 정지는 비어 있을 수 있다',
    reason       VARCHAR(255) NULL COMMENT '정지 사유. 관리자 내부 기록 — 파트너 응답 · 알림에 싣지 않는다',
    suspended_at DATETIME(6)  NOT NULL COMMENT '정지 시각',
    PRIMARY KEY (id),
    INDEX idx_srn_reg_no (reg_no) COMMENT '심사 화면의 "정지 이력이 있는 중개등록번호" 경고',
    INDEX idx_srn_office (office_id) COMMENT '그 사무소 자신의 정지 — 번호를 바꾼 뒤에도 경고가 이어진다',
    CONSTRAINT fk_srn_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_srn_division FOREIGN KEY (division_id) REFERENCES partner_divisions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_srn_suspender FOREIGN KEY (suspended_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '정지된 중개등록번호 이력(append-only). 같은 번호로 다시 신청하면 심사 화면이 경고한다 — 막지는 않는다';

-- ④ 지금 정지된 부문의 사무소 번호로 이력을 채운다. 이 마이그레이션 전의 정지는 이력이 따로 없어 부문 행이 유일한 근거다.
--    사무소는 ② 에서 채운 부문의 사무소다. 번호는 그 사무소가 잡고 있는 번호, 없으면 신청 번호다(정지는 번호를 놓지 않아
--    보통 둘이 같다). 정지 시각은 마지막 처분 시각(reviewed_at)이고, 이관된 행처럼 비어 있으면 그 행의 갱신 시각으로 근사한다.
--    ★ 사무소가 비어 있는 부문(이 마이그레이션 전에 탈퇴해 연결이 지워진 것)은 건너뛴다 — 남길 번호가 없다.
INSERT INTO suspended_reg_numbers (reg_no, office_id, division_id, suspended_by, reason, suspended_at)
SELECT COALESCE(bo.claimed_reg_no, bo.reg_no),
       bo.id,
       pd.id,
       pd.reviewed_by,
       pd.suspend_reason,
       COALESCE(pd.reviewed_at, pd.updated_at)
FROM partner_divisions pd
         JOIN brokerage_offices bo ON bo.id = pd.office_id
WHERE pd.status = 'SUSPENDED'
  AND pd.division = 'REAL_ESTATE_BROKER';

-- ⑤ 두 칼럼의 주석을 새 모델대로 고친다 — 승인된 부문은 대기로 돌아가지 않고(승인 뒤 변경은 변경 신청이 받는다),
--    승인된 사무소의 번호는 변경 신청이 승인될 때만 바뀐다. 옛 주석은 "재심사"를 말한다.
--    ★ 정의는 그대로 다시 적는다(타입 · NULL · 기본값). 위치를 적지 않은 MODIFY 는 칼럼 자리를 옮기지 않는다.
ALTER TABLE partner_divisions
    MODIFY last_approved_at DATETIME(6) NULL
        COMMENT '마지막 승인 · 복구 시각. 승인된 부문은 대기로 돌아가지 않는다 — 승인 뒤의 정보 변경은 변경 신청(brokerage_office_change_requests)이 받는다';
ALTER TABLE brokerage_offices
    MODIFY reg_no VARCHAR(20) NOT NULL
        COMMENT '중개등록번호 지자체5-연도4-일련5. 유니크가 아니다 — 같은 번호로 여러 명이 신청하고 한 명만 승인받는다(claimed_reg_no). 승인 전에는 고쳐 낼 수 있고, 승인 뒤에는 변경 신청이 승인될 때만 바뀐다';
ALTER TABLE brokerage_offices
    MODIFY claimed_reg_no VARCHAR(20) NULL
        COMMENT '승인받아 쓰고 있는 중개등록번호. 승인 · 복구 때 채우고, 변경 신청이 승인되면 새 번호로 옮기고, 대표자가 탈퇴하면 비운다';
