-- 임대 요청을 파트너스(공인중개사 사무소)로 옮긴다 (PO 결정 2026-10-06 · 10-07).
--
-- 내부 담당자가 사라졌다. 요청은 승인된 공인중개사 사무소들이 보는 대기 목록에 올라가고, **먼저 잡은 사무소가 맡는다**
-- (선착순 — 잡는 단위는 사람이 아니라 사무소). 맡은 사무소는 놓을 수 있고(다시 대기), 결과를 골라 종료한다.
-- 대표자가 탈퇴하거나 부문 · 계정이 정지되면 그 사무소가 맡은 진행 중 요청은 대기로 돌아간다.
--
--   SUBMITTED(대기) → IN_PROGRESS(사무소가 잡음) → CLOSED(사무소가 종료, 결과 FOUND · NOT_PROCEEDED)
--   CANCELED(유학생 — 대기 · 진행 중 언제나) · IN_PROGRESS → SUBMITTED(놓기 · 맡을 사람이 사라짐)
--
-- ⚠ 되돌릴 수 없다(담당자 칸 · 담당자 메모가 사라진다). 운영에 적용하기 전에 RDS 수동 스냅샷을 뜬다 —
--   담당자 메모는 그 스냅샷이 유일한 사본이 된다.
--
-- 순서가 중요하다:
--   새 칸 붙이기 → 기존 행 옮기기(진행 중 → 대기 · 계약됨 → 종료) → 담당자 칸 지우기 → 상태 ENUM 줄이기(옛 값이 남은 행이 없어야 한다)
--   → 옛 알림 행의 문구 키 옮기기 · 아직 안 나간 옛 푸시 닫기 → 담당자 메모 DROP → 잡기 이력 테이블.
--   (칼럼 추가 · 삭제는 문장 하나에 하나씩 쓴다 — 스키마 대조 테스트가 "ALTER TABLE … ADD COLUMN …" · "… DROP COLUMN …" 모양만 읽는다)

-- ① 맡은 사무소 · 잡은 시각 · 종료 결과.
--    office_id 는 **사무소**를 가리킨다(사람이 아니다) — 대표자가 바뀌거나 떠나도 "어느 사무소가 맡았었나"는 남는다.
--    대기로 돌아가면 비운다. 종료 · 취소된 요청에는 남는다(사무소의 "맡았던 요청" 목록 · 유학생 화면의 사무소 표시).
ALTER TABLE rental_requests
    ADD COLUMN office_id BIGINT NULL
        COMMENT '맡은 중개사무소(brokerage_offices). NULL = 대기(아무도 안 잡음). 대기로 돌아가면 비우고, 종료 · 취소되면 그대로 남는다' AFTER verification_id;
ALTER TABLE rental_requests
    ADD COLUMN claimed_at DATETIME(6) NULL
        COMMENT '사무소가 잡은 시각. office_id 와 함께 채우고 함께 비운다' AFTER office_id;
ALTER TABLE rental_requests
    ADD COLUMN outcome ENUM ('FOUND','NOT_PROCEEDED') NULL
        COMMENT '사무소가 종료할 때 고른 결과 — FOUND(방을 구함) · NOT_PROCEEDED(진행 안 함). 성사율 지표의 유일한 자리. 유학생 응답에는 싣지 않는다' AFTER status;
--    ★★ 맡은 사무소 FK 는 (office_id) **단독** 인덱스에 얹는다 — 그래서 그 인덱스를 아래 복합 인덱스보다 **먼저** 만든다
--       (InnoDB 는 그 칸으로 시작하는 인덱스 중 앞선 것을 FK 에 쓰고, 나중에 만든 인덱스로 옮겨 가지 않는다 — 실측).
--       FK 가 복합 인덱스(office_id, status, id)에 얹히면
--       status 만 바꾸는 UPDATE(유학생 취소 · 탈퇴)도 그 인덱스에 새 항목을 넣으며 FK 검사를 돌려 사무소 행을 S 로 잡는다 —
--       사무소 행을 X 로 쥔 놓기 · 종료 · 대기 복귀와 서로를 기다리다 한쪽이 1213 으로 진다(MySQL 9.5 재현).
--       단독 인덱스면 office_id 가 그대로인 UPDATE 는 FK 검사를 하지 않는다.
--    ⚠⚠ 복합 인덱스의 앞부분과 겹쳐 중복처럼 보여도 지우지 말 것 — 지우면 놓기 · 종료와 신청자 탈퇴가 교착한다.
--       지워도 MySQL 은 말없이 FK 를 복합 인덱스로 옮겨 붙이고, 테스트 게이트(H2)는 그린이다 — 교착은 운영에서 조용히 돌아온다.
--       그래서 게이트의 정적 검사(RentalRequestOfficeFkIndexGuardTest)가 이 인덱스 · 만드는 순서 · 뒤 버전의 DROP 을 지키고,
--       CI 의 마이그레이션 검증이 실 MySQL 에서 잠금으로 확인한다(RentalRequestV28MigrationMySqlTest).
ALTER TABLE rental_requests
    ADD INDEX idx_rr_office_fk (office_id) COMMENT '맡은 사무소 FK 전용 — 상태만 바꾸는 UPDATE 가 사무소 행을 잠그지 않게 한다. idx_rr_office 와 겹쳐 보여도 지우지 않는다';
ALTER TABLE rental_requests
    ADD INDEX idx_rr_office (office_id, status, id) COMMENT '사무소가 맡은 요청 목록 · 사무소별 진행 중 개수(잡기 상한)';
ALTER TABLE rental_requests
    ADD CONSTRAINT fk_rr_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT;

-- ② 기존 행을 옮긴다. 담당자가 사라지면 담당자가 잡고 있던 건은 맡은 사람이 없다 — **대기로 되돌려** 사무소들이 잡게 한다.
--    계약까지 간 건은 결과만 남기면 된다 — 종료 + 방을 구함.
--    ★ updated_at 을 제 값으로 다시 적는다 — 그 칼럼은 ON UPDATE CURRENT_TIMESTAMP 라 두면 이관이 모든 행의 갱신 시각을
--      마이그레이션 시각으로 덮는다.
--    ★ 계약됨 → 종료의 종료 시각은 그 행의 updated_at 이다. 계약됨으로 바뀐 뒤에는 유학생도 담당자도 그 행을 고칠 수 없었으므로
--      (수정 · 취소 · 이관 · 종료가 전부 막혀 있었다) 마지막 갱신 시각이 곧 계약이 활성화된 시각이다. 비워 두면 종료된 요청이
--      종료 시각 없이 남는다.
UPDATE rental_requests
SET status              = 'SUBMITTED',
    assigned_manager_id = NULL,
    assigned_at         = NULL,
    started_at          = NULL,
    updated_at          = updated_at
WHERE status = 'IN_PROGRESS';

UPDATE rental_requests
SET status     = 'CLOSED',
    outcome    = 'FOUND',
    closed_at  = COALESCE(closed_at, updated_at),
    updated_at = updated_at
WHERE status = 'CONTRACTED';

-- ③ 담당자 칸을 지운다. FK 부터 풀고, 그 칸으로 시작하는 인덱스를 지운 뒤 칼럼을 지운다 —
--    칼럼만 지우면 idx_rr_manager 가 (status) 하나로 줄어든 채 남는다.
ALTER TABLE rental_requests
    DROP FOREIGN KEY fk_rr_manager;
ALTER TABLE rental_requests
    DROP INDEX idx_rr_manager;
ALTER TABLE rental_requests DROP COLUMN assigned_manager_id;
ALTER TABLE rental_requests DROP COLUMN assigned_at;
ALTER TABLE rental_requests DROP COLUMN started_at;

-- ④ 상태 ENUM 을 줄인다. ② 에서 CONTRACTED 행을 모두 옮겼으므로 실패하지 않는다.
--    나머지 주석도 사실대로 고친다(정의는 그대로 다시 적는다 — 위치를 적지 않은 MODIFY 는 칼럼 자리를 옮기지 않는다).
--    겹치는 쪽 · 번호를 부르는 쪽 · 대기열을 보는 쪽이 이제 담당자가 아니라 사무소다.
ALTER TABLE rental_requests
    MODIFY status ENUM ('SUBMITTED','IN_PROGRESS','CANCELED','CLOSED') NOT NULL DEFAULT 'SUBMITTED'
        COMMENT 'SUBMITTED(대기 — 승인된 모든 사무소가 본다) · IN_PROGRESS(사무소가 잡음) · CLOSED(사무소가 종료) · CANCELED(유학생이 취소)';
ALTER TABLE rental_requests
    MODIFY version BIGINT NOT NULL DEFAULT 0
        COMMENT '낙관적 락 — 유학생 수정 · 취소와 사무소 잡기 · 놓기 · 종료가 겹치면 늦은 쪽을 돌려보낸다. 동시에 잡은 두 사무소 중 하나만 이긴다';
ALTER TABLE rental_requests
    MODIFY request_number VARCHAR(20) NOT NULL
        COMMENT '업무번호 RQ-yyMMdd-RAND6 — 유학생과 맡은 사무소가 전화 · 문자로 부르는 이름';
ALTER TABLE rental_requests
    MODIFY closed_reason VARCHAR(255) NULL
        COMMENT '취소 사유(유학생이 적은 것 · 탈퇴). 사무소의 종료는 사유 대신 결과(outcome)를 남긴다 — 사유가 있는 CLOSED 는 이 마이그레이션 전 담당자가 접은 건이다';
--    대기 목록(대기 중인 요청을 오래된 것부터)이 이 인덱스를 탄다. 주석만 바꾸려면 다시 만들어야 해서 한 문장에서 지우고 붙인다.
ALTER TABLE rental_requests
    DROP INDEX idx_rr_status,
    ADD INDEX idx_rr_status (status, id) COMMENT '사무소들이 보는 대기 목록(오래된 것부터)';
ALTER TABLE rental_requests
    COMMENT = '임대 요청. 승인된 공인중개사 사무소가 먼저 잡아 맡는다(선착순) — 맡은 사무소는 office_id';

-- ⑤ 옛 "처리 시작" 알림의 문구 키를 옮긴다. 이 알림의 문구는 이제 맡은 사무소의 상호({1})를 싣는데,
--    옛 행의 치환 값은 요청 번호({0}) 하나뿐이다 — 알림함은 읽는 시점에 렌더하므로 그대로 두면 옛 알림이 "{1}" 을 그대로 보여 준다.
--    옛 행만 옛 문구(번들의 rental_request_in_progress_legacy)로 렌더하게 키를 옮긴다. 알림 종류(noti_type)는 그대로다.
--    ★ 이 마이그레이션 전의 행은 전부 옛 형태다(사무소가 잡는 경로가 아직 없었다).
UPDATE user_notifications
SET template_code = 'rental_request_in_progress_legacy'
WHERE noti_type = 'RENTAL_REQUEST_IN_PROGRESS'
  AND template_code = 'rental_request_in_progress';

UPDATE notification_outbox
SET template_code = 'rental_request_in_progress_legacy',
    updated_at    = updated_at
WHERE noti_type = 'RENTAL_REQUEST_IN_PROGRESS'
  AND template_code = 'rental_request_in_progress';

--    아직 안 나간 옛 "처리 시작" 푸시는 보내지 않고 닫는다. ② 가 그 요청을 대기로 돌렸으므로(또는 이미 종료됐으므로) 지금 보내면
--    아무도 맡지 않은 요청에 "처리 중"이 도착한다 — 런타임의 대기 복귀처럼 뒤따르는 "다시 대기" 알림도 없다.
--    워커가 다시 집는 PENDING · FAILED 만 닫는다. GIVEN_UP 은 저절로 나가지 않고 관리자의 포기 목록에 남아야 해서 그대로 둔다.
--    시도 횟수는 그대로 둔다(이관은 보내려 한 시도가 아니다). 갱신 시각은 이 상태 변경의 시각으로 둔다.
UPDATE notification_outbox
SET status     = 'SKIPPED',
    last_error = '임대 요청 이관(담당자 제거)으로 낡은 처리 시작 알림이라 보내지 않음'
WHERE noti_type = 'RENTAL_REQUEST_IN_PROGRESS'
  AND status IN ('PENDING', 'FAILED');

-- ⑥ 담당자 메모. 중개사와의 소통은 나중에 요청마다 채팅으로 붙인다. 이 테이블을 잡는 FK 는 없다(자기 FK 만 요청 · 작성자를 잡는다).
DROP TABLE IF EXISTS rental_request_memos;

-- ⑦ 잡기 이력(append-only). 사무소가 잡고 · 놓고 · 종료하고, 맡을 사람이 사라져 대기로 돌아간 순간마다 한 행 — 고치지도 지우지도 않는다.
--    잡기 → 놓기를 되풀이해 유학생 연락처를 모으는 것을 한도 대신 이 이력으로 본다(관리자가 "이 요청을 잡았다 놓은 사무소"를 본다).
--    요청 행의 office_id 는 놓으면 비워져, 그것만으로는 지나간 사무소가 남지 않는다.
--    ★ 이 마이그레이션 전에는 사무소가 잡은 적이 없다(담당자 시절) — 옮길 이력이 없어 빈 채로 시작한다.
--    ★ 상호는 저장하지 않는다 — office_id 로 그 사무소의 지금 상호를 읽는다(사무소 행은 지워지지 않는다).
--    ★ 유학생의 수정 · 취소 · 탈퇴는 남기지 않는다 — 사무소가 한 일이 아니고, 요청 행의 상태 · 종료 시각이 그것을 말한다.
--    ★★ 잠금 순서: 이 INSERT 의 FK 검사는 부모 행을 S 로 잡는다.
--       · 요청 · 사무소 FK 는 괜찮다 — 남기는 쪽(잡기 · 놓기 · 종료 · 대기 복귀)이 그 두 행을 이미 배타로 쥔 뒤에 남긴다.
--       · 행위자(actor_id → users)에는 **FK 를 걸지 않는다.** 걸면 사무소 행을 쥔 트랜잭션이 이력 INSERT 한 줄에서 users 행을 S 로
--         잡아 전역 순서(users → 사무소)를 거스른다. 지금 남기는 쪽은 그 사람의 users 행을 맨 앞에서 이미 배타로 쥐고 있어 실제로는
--         기다리지 않지만, 그 전제가 INSERT 안에 숨어 있으면 행위자를 다르게 남기는 다음 길이 말없이 교착을 들인다
--         (매물 등록이 INSERT 의 사무소 S → users S 로 겪은 것과 같은 함정). users 행은 지워지지 않아(탈퇴는 표시만 한다)
--         FK 가 없어도 가리키는 사람이 사라지지 않는다.
--       · 이 테이블은 잠금 읽기로 읽지 않는다(관리자 상세는 일반 읽기) — INSERT 가 인덱스 구간에서 기다릴 일이 없다.
CREATE TABLE rental_request_claim_events
(
    id                BIGINT                                          NOT NULL AUTO_INCREMENT,
    rental_request_id BIGINT                                          NOT NULL COMMENT '임대 요청',
    office_id         BIGINT                                          NOT NULL COMMENT '그 순간 요청을 쥐고 있던 중개사무소(잡은 · 놓은 · 종료한 · 잃은 사무소)',
    actor_id          BIGINT                                          NULL COMMENT '한 사람(users.id) — 그 사무소의 대표자. 대기 복귀는 사람이 한 일이 아니라 비어 있다. FK 없음(잠금 순서)',
    event_type        ENUM ('CLAIMED','RELEASED','RETURNED','CLOSED') NOT NULL
        COMMENT 'CLAIMED(잡음) · RELEASED(맡은 사무소가 놓음) · RETURNED(맡을 사람이 사라져 대기 복귀) · CLOSED(종료). 같은 사무소의 두 번 누른 잡기는 남기지 않는다',
    return_cause      ENUM ('REPRESENTATIVE_WITHDRAWN','DIVISION_SUSPENDED','ACCOUNT_SUSPENDED') NULL
        COMMENT '대기 복귀의 원인 — 대표자 탈퇴 · 공인중개사 부문 정지 · 대표자 계정 정지. RETURNED 에만 있다',
    outcome           ENUM ('FOUND','NOT_PROCEEDED')                  NULL COMMENT '종료 결과. CLOSED 에만 있다',
    occurred_at       DATETIME(6)                                     NOT NULL COMMENT '일어난 시각',
    PRIMARY KEY (id),
    INDEX idx_rrce_request (rental_request_id, id) COMMENT '관리자 상세의 이력(일어난 순서)',
    INDEX idx_rrce_office (office_id, id) COMMENT '사무소가 잡고 놓은 요청',
    CONSTRAINT fk_rrce_request FOREIGN KEY (rental_request_id) REFERENCES rental_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rrce_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '임대 요청의 잡기 이력(append-only) — 잡기 · 놓기 · 대기 복귀 · 종료. 관리자에게만 보인다';
