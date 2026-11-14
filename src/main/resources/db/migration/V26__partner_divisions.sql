-- 내부 담당자 제거 · 파트너와 부문 승인 (PO 결정 2026-10-06).
--
-- 역할은 셋으로 줄어든다 — USER(유학생) · ADMIN(관리자) · PARTNER(외부 파트너).
-- 내부 담당자(MANAGER)는 개념째 사라지고 직원 가입 신청 흐름도 함께 사라진다.
-- 외부 공인중개사(BROKER)는 파트너가 되고, 파트너는 **부문**을 신청해 관리자에게 부문별로 승인받는다.
-- 기능은 승인된 부문이 연다. 지금 부문은 공인중개사(REAL_ESTATE_BROKER) 하나다.
--
-- ★ 승인 · 정지의 축은 partner_divisions.status 하나로 모은다. 중개사무소의 status 는 지운다 —
--   상태가 두 곳에 있으면 "부문은 승인, 사무소는 대기" 같은 어긋난 조합이 생긴다.
--   매물 공개 조회와 게시 관문은 "그 사무소 대표자의 공인중개사 부문이 APPROVED 인가"를 본다.
--
-- ★ 중개등록번호 중복은 **가입이 아니라 승인에서** 막는다(PO 결정 2026-10-06). 같은 번호로 여러 명이 신청할 수 있고
--   관리자가 등록증을 보고 한 명만 승인한다. reg_no 의 유니크를 풀고, "승인받아 쓰고 있는 번호"(claimed_reg_no)에
--   유니크를 건다 — 그 번호를 마지막으로 승인받은 사무소가 남아 있는 동안만 막히고(정지 · 재심사 중에도 놓지 않는다),
--   대표자가 탈퇴하면 풀린다.
--
-- ⚠ 되돌릴 수 없다(사무소 상태 · 직원 신청 테이블이 사라진다). 운영에 적용하기 전에 RDS 수동 스냅샷을 뜬다.
--
-- 순서가 중요하다:
--   역할 ENUM 넓히기 → 사무소당 대표자 하나만 남기기 → 부문 테이블 · 기존 중개사 이관(사무소 상태를 읽는다)
--   → 역할 옮기기(부문이 생긴 사람만 PARTNER) → ENUM 줄이기 → 사무소 유니크 · 칼럼 정리(상태는 이관 뒤에 지운다)
--   → 승인 · 정지된 사무소의 번호 잡기(부문 상태를 읽는다) → 직원 신청 DROP.

-- ① 역할 ENUM 을 먼저 넓힌다. 값이 없는 ENUM 으로는 행을 옮길 수 없다.
--    새 값은 맨 뒤에 붙인다 — 기존 값의 순번이 그대로라 표를 다시 쓰지 않는다.
ALTER TABLE users
    MODIFY role ENUM ('USER','MANAGER','ADMIN','BROKER','PARTNER') NOT NULL DEFAULT 'USER'
        COMMENT '역할 이관 중 — 이 마이그레이션 끝에서 USER · ADMIN · PARTNER 로 줄어든다';

-- ② 사무소당 계정 하나 · 대표자만(PO 확정 2026-10-06). 아래 ⑦ 의 유니크를 걸려면 한 사무소에 연결이
--    둘 이상인 행을 먼저 정리해야 한다. 남기는 쪽은 대표자(CEO), 대표자가 없으면 가장 먼저 붙은 사람이다.
--    빠지는 사람(소속공인중개사 등)은 사무소 연결만 사라진다 — 계정 · 그 사람이 올린 매물(사무소 소속)은 그대로다.
--    ★ 집계 서브쿼리를 한 겹 더 감싼다 — 지우는 테이블을 같은 문장에서 읽으면 MySQL 이 거절한다(1093).
--      집계가 있는 파생 테이블은 병합되지 않고 먼저 만들어진다.
DELETE FROM broker_profiles
WHERE id NOT IN (SELECT keep_id
                 FROM (SELECT COALESCE(MIN(CASE WHEN role_in_office = 'CEO' THEN id END), MIN(id)) AS keep_id
                       FROM broker_profiles
                       GROUP BY office_id) AS keep_per_office);

-- ③ 파트너 부문. 사람 × 부문마다 행 하나 — 한 사람이 여러 부문을 가질 수 있고 부문마다 따로 심사한다.
--    division 을 ENUM 으로 박지 않는다: 부문은 늘어난다(행정사 등) — 부문을 더할 때 ALTER 가 필요 없게.
--    ★ 엔티티는 이 칸을 @Enumerated(STRING) 로 읽는다. 코드(PartnerDivisionType)에서 상수를 지우면 그 값을 가진
--      행을 읽는 순간 로딩이 깨진다 — 부문을 접을 때는 상수를 지우지 않고 은퇴시킨다(files.purpose 의 은퇴 용도와
--      같은 규칙). 이름을 문자열로 읽어 모르는 값을 건너뛰는 곳은 토큰 발급의 잠금 읽기 하나뿐이다.
CREATE TABLE partner_divisions
(
    id               BIGINT                                             NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)                                           NOT NULL COMMENT '외부 노출 식별자',
    user_id          BIGINT                                             NOT NULL COMMENT '파트너 계정',
    division         VARCHAR(30)                                        NOT NULL COMMENT 'PartnerDivisionType 이름 — REAL_ESTATE_BROKER …',
    status           ENUM ('PENDING','APPROVED','REJECTED','SUSPENDED') NOT NULL DEFAULT 'PENDING'
        COMMENT '승인 · 정지의 유일한 축. APPROVED 일 때만 그 부문의 기능이 열리고 매물이 공개된다',
    submitted_at     DATETIME(6)                                        NULL COMMENT '서류까지 낸 시각. NULL = 가입만 하고 아직 내지 않음 — 심사 대기열에 나오지 않는다',
    reject_reason    VARCHAR(255)                                       NULL COMMENT '마지막 반려 사유. 신청자에게 그대로 보여 준다 — 내부 은어를 쓰지 않는다',
    suspend_reason   VARCHAR(255)                                       NULL COMMENT '마지막 정지 사유. 관리자 내부 기록(파트너에게 보이지 않는다)',
    reviewed_by      BIGINT                                             NULL COMMENT '마지막으로 상태를 바꾼 관리자(users.id). 탈퇴 자동 반려는 비어 있다',
    reviewed_at      DATETIME(6)                                        NULL,
    last_approved_at DATETIME(6)                                        NULL COMMENT '마지막 승인 · 복구 시각. 대기 중인데 값이 있으면 재심사다 — 심사자에게 보여 준다',
    version          BIGINT                                             NOT NULL DEFAULT 0 COMMENT '낙관적 락 — 겹친 심사 · 재제출의 늦은 쪽을 409 로 돌려보낸다. 관리자가 본 값과 다르면 심사를 거절한다',
    created_at       DATETIME(6)                                        NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)                                        NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_pd_uuid (uuid),
    UNIQUE KEY uq_pd_user_division (user_id, division) COMMENT '같은 부문을 두 번 신청하지 않는다. 토큰 발급 · 공개 조회가 이 키로 찾는다',
    INDEX idx_pd_queue (status, submitted_at, id) COMMENT '심사 대기열',
    CONSTRAINT fk_pd_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pd_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '파트너 부문 — 승인된 부문의 기능이 열린다. 탈퇴해도 지우지 않는다(승인 이력)';

-- ④ 기존 중개사 → 공인중개사 부문. 사무소 상태를 그대로 옮긴다:
--    ACTIVE → APPROVED · PENDING → PENDING · SUSPENDED → SUSPENDED · CLOSED → REJECTED.
--    ★ 역할이 BROKER 인 사람의 연결만 옮긴다. 다른 역할에 연결이 남아 있으면(정상 경로로는 생기지 않는다)
--      부문을 만들지 않는다 — 파트너가 아닌 사람의 부문이 사무소 매물을 공개로 열면 안 된다.
--    ★ 관리자가 손으로 등록하던 사무소라 등록증이 없다. 그래서 대기(PENDING)로 옮겨지는 건은 **제출 전**(submitted_at
--      NULL)으로 둔다 — 대기열에 오르면 심사자가 서류 없는 건에 승인 버튼을 보게 된다. 파트너가 등록증을 올려
--      제출하면 그때 대기열에 오른다. 이미 판정이 난 건(승인 · 정지 · 반려)은 연결된 시각을 제출 시각으로 둔다(이력).
--    ★ 마지막 승인 시각은 옛 사무소의 updated_at 으로 채운다. verified_at 은 레지스트리 대조 시각이라 승인과 뜻이 다르고
--      비어 있을 수 있다 — 비면 승인된 부문이 "한 번도 승인된 적 없음"으로 읽힌다. updated_at 은 상태가 마지막으로
--      바뀐 시각이라 ACTIVE 면 승인(또는 그 뒤의 수정) 시각, SUSPENDED 면 정지 시각이다 — 정지 건은 실제 승인보다
--      늦은 근사값이지만 "승인된 적이 있다"는 재심사 표시에는 충분하다(심사 시각 reviewed_at 과도 같은 값이다).
INSERT INTO partner_divisions (uuid, user_id, division, status, submitted_at, reviewed_at, last_approved_at,
                               created_at, updated_at)
SELECT UUID(),
       bp.user_id,
       'REAL_ESTATE_BROKER',
       CASE bo.status
           WHEN 'ACTIVE' THEN 'APPROVED'
           WHEN 'PENDING' THEN 'PENDING'
           WHEN 'SUSPENDED' THEN 'SUSPENDED'
           ELSE 'REJECTED'
           END,
       CASE WHEN bo.status = 'PENDING' THEN NULL ELSE bp.created_at END,
       CASE WHEN bo.status = 'PENDING' THEN NULL ELSE bo.updated_at END,
       CASE WHEN bo.status IN ('ACTIVE', 'SUSPENDED') THEN bo.updated_at END,
       bp.created_at,
       bp.created_at
FROM broker_profiles bp
         JOIN brokerage_offices bo ON bo.id = bp.office_id
         JOIN users u ON u.id = bp.user_id
WHERE u.role = 'BROKER';

-- ⑤ 역할을 옮긴다. 내부 담당자 계정은 지우지 않고 유학생 계정으로 남긴다 — users 를 잡는 FK 가 수십 곳이라
--    지우는 것은 위험하고 되돌릴 수 없다. 그 사람에게 배정돼 있던 임대 요청은 관리자가 이관한다.
--    ★ 중개사는 ④ 에서 **부문이 생긴 사람만** PARTNER 가 된다. ② 에서 연결이 지워졌거나 처음부터 연결이 없던
--      BROKER 는 USER 로 내린다 — 부문 없는 PARTNER 는 제출할 사무소도 신청할 부문도 없어 빠져나갈 화면이 없다.
--      그래서 이 두 문장은 ④ 뒤에 와야 한다(부문 존재를 본다).
UPDATE users SET role = 'USER' WHERE role = 'MANAGER';
UPDATE users u
SET u.role = 'USER'
WHERE u.role = 'BROKER'
  AND NOT EXISTS (SELECT 1 FROM partner_divisions pd WHERE pd.user_id = u.id);
UPDATE users SET role = 'PARTNER' WHERE role = 'BROKER';

-- ⑥ ENUM 을 줄인다. 위에서 옛 값을 쓰는 행을 모두 옮겼으므로 실패하지 않는다.
ALTER TABLE users
    MODIFY role ENUM ('USER','ADMIN','PARTNER') NOT NULL DEFAULT 'USER'
        COMMENT 'USER(유학생) · ADMIN(관리자) · PARTNER(외부 파트너 — 기능은 승인된 부문이 연다)';

-- ⑦ 사무소 : 대표자 = 1 : 1 을 DB 가 지킨다. 유니크를 먼저 걸고 나서 옛 인덱스를 지운다 —
--    fk_bp_office 가 office_id 인덱스를 요구해서, 대신할 인덱스가 생기기 전에는 옛 것을 지울 수 없다.
--    role_in_office 는 대표자만 남아 뜻이 없다.
--    (칼럼 삭제는 문장 하나에 하나씩 쓴다 — 스키마 대조 테스트가 "ALTER TABLE … DROP COLUMN …" 모양만 읽는다)
ALTER TABLE broker_profiles
    ADD UNIQUE KEY uq_bp_office (office_id) COMMENT '사무소당 계정 하나 — 대표자만 가입한다';
ALTER TABLE broker_profiles
    DROP INDEX idx_bp_office;
ALTER TABLE broker_profiles DROP COLUMN role_in_office;

-- ⑧ 사무소 상태는 ④ 에서 부문으로 옮겼다. 인덱스부터 지우고 칼럼을 지운다.
--    등록증 사본 칸을 붙인다 — 민감 파일(files.purpose = BROKER_LICENSE, 공개 URL 없음)을 가리킨다.
ALTER TABLE brokerage_offices
    DROP INDEX idx_bo_status;
ALTER TABLE brokerage_offices DROP COLUMN status;
ALTER TABLE brokerage_offices
    ADD COLUMN license_file_id BIGINT NULL
        COMMENT '중개사무소 등록증 사본(files). 가입 직후에는 비어 있고 제출하면 채워진다' AFTER verified_at,
    ADD CONSTRAINT fk_bo_license_file FOREIGN KEY (license_file_id) REFERENCES files (id) ON DELETE RESTRICT;
--    낙관적 락 칸을 붙인다. 이제 이 행을 쓰는 곳이 넷이다(제출 · 대표번호 변경 · 승인의 번호 잡기 · 탈퇴의 번호 놓기).
--    JPA 는 @DynamicUpdate 가 없으면 UPDATE 에 전 칼럼을 싣는다 — 둘이 겹치면 늦은 쪽이 앞사람이 쓴 칸(잡은 번호 ·
--    대표번호 · 낸 서류)을 낡은 값으로 덮는다. users 의 version 과 같은 이유다. 늦은 쪽은 409 로 돌아간다.
--    DEFAULT 가 있어 기존 행은 0 에서 시작한다.
ALTER TABLE brokerage_offices
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0
        COMMENT '낙관적 락 — 대표번호 변경과 제출 · 승인 · 탈퇴가 서로 쓴 칸을 낡은 값으로 덮지 않게';

-- ⑨ 등록번호의 유니크를 "승인받아 쓰고 있는 번호"로 옮긴다.
--    reg_no 의 유니크는 일반 인덱스로 바꾼다 — 같은 번호의 신청이 여럿일 수 있고, 심사 화면이 같은 번호의 다른 신청을
--    이 인덱스로 찾는다. reg_no 를 잡는 FK 는 없다(사무소를 가리키는 FK 는 전부 id 를 본다) — 새 인덱스를 먼저 붙이는
--    것은 같은 칸의 인덱스가 한순간도 비지 않게 하려는 것일 뿐이다.
ALTER TABLE brokerage_offices
    ADD INDEX idx_bo_reg_no (reg_no) COMMENT '같은 등록번호의 다른 신청(심사 화면)',
    DROP INDEX uq_bo_reg_no;
--    두 칼럼의 주석을 사실대로 고친다(정의는 그대로다). 옛 주석의 "이건 안 바뀐다" · "중개사가 고치지 못한다"는 이제
--    거짓이다 — 등록번호는 승인 전에 고쳐 낼 수 있고 승인 뒤에 고치면 재심사이며, 대표번호는 파트너가 바로 바꾼다.
ALTER TABLE brokerage_offices
    MODIFY reg_no VARCHAR(20) NOT NULL
        COMMENT '중개등록번호 지자체5-연도4-일련5. 유니크가 아니다 — 같은 번호로 여러 명이 신청하고 한 명만 승인받는다(claimed_reg_no). 승인 전에는 고쳐 낼 수 있고 승인 뒤에 고치면 재심사다',
    MODIFY tel VARCHAR(30) NOT NULL
        COMMENT '공시 연락처. 광고 법정 표시 항목 — 파트너가 심사 없이 바로 바꾼다';
--    claimed_reg_no = 이 사무소가 마지막으로 승인받은 번호, 대표자가 남아 있는 동안만. 승인 · 복구 때 채우고
--    대표자가 탈퇴하면 비운다. 정지는 비우지 않는다 — 정지가 이어지는 동안 같은 번호의 다른 신청이 승인받지 못하게.
--    탈퇴하면 비우므로, 부문이 정지된 동안은 탈퇴를 막는다(코드) — 정지 → 탈퇴 → 재가입으로 정지가 씻기지 않게.
--    NULL 은 여럿이어도 유니크에 걸리지 않는다(InnoDB) — 미승인 신청끼리는 번호가 겹쳐도 된다.
ALTER TABLE brokerage_offices
    ADD COLUMN claimed_reg_no VARCHAR(20) NULL
        COMMENT '승인받아 쓰고 있는 중개등록번호. 승인 · 복구 때 채우고 대표자가 탈퇴하면 비운다' AFTER reg_no,
    ADD UNIQUE KEY uq_bo_claimed_reg_no (claimed_reg_no) COMMENT '한 번호는 한 사무소만 승인받아 쓴다 — 동시 승인 둘 중 하나만 이긴다';

-- ⑩ 승인 · 정지로 옮겨진 사무소는 지금 그 번호를 쓰고 있다 — 잡아 둔다. ④ 의 부문 상태를 읽으므로 그 뒤에,
--    칼럼이 생긴 ⑨ 뒤에 온다. 이 마이그레이션 전까지 reg_no 가 유니크였으므로 여기서 충돌할 수 없다.
--    ★ 탈퇴한 계정에 남은 연결은 번호를 잡지 않는다 — 번호는 대표자가 남아 있는 동안만 잡는다. 탈퇴 정리 전에 탈퇴해
--      연결이 남은 계정(개발 DB)이 잡으면, 탈퇴 계정은 다시 탈퇴할 수 없어 그 번호를 놓을 길이 없다.
UPDATE brokerage_offices bo
    JOIN broker_profiles bp ON bp.office_id = bo.id
    JOIN users u ON u.id = bp.user_id AND u.deleted_at IS NULL
    JOIN partner_divisions pd ON pd.user_id = bp.user_id AND pd.division = 'REAL_ESTATE_BROKER'
SET bo.claimed_reg_no = bo.reg_no
WHERE pd.status IN ('APPROVED', 'SUSPENDED');

-- ⑪ 직원 가입 신청 흐름이 사라졌다. 이 테이블을 잡는 FK 는 없다(자기 FK 만 users 를 잡는다).
DROP TABLE IF EXISTS staff_applications;
