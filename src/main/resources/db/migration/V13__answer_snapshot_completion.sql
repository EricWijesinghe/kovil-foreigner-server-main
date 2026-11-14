-- =============================================================================
-- V13__answer_snapshot_completion.sql — 제출된 요청의 표시를 스냅샷으로 완결한다
--
-- 원칙(PO 확정 2026-08-26): **제출된 임대 요청은 그 시점의 박제본이다.** 관리자가 질문·
-- 선택지·지역을 어떻게 바꾸든(문구 수정·숨김·구조 변경·삭제) 제출된 요청의 표시는 변하지
-- 않는다. 이 완결이 있어야 구조 잠금을 "열린 요청 기준"으로 좁힐 수 있다.
--
-- V10 이 만든 ko 스냅샷의 구멍 네 개를 메운다:
--   ① 비선택형(TEXT·NUMBER·DATE·ADDRESS) 질문은 답이 질문 행 자체라 question_text_snapshot 이
--      항상 NULL 이었고, 질문 문구가 answer_text_snapshot 자리에 잘못 들어가 있었다.
--   ② 제출자가 본 언어의 스냅샷이 없었다 — 유저 화면(name)은 살아있는 마스터를 그렸다.
--   ③ V10 이전 저장 행은 스냅샷이 통째로 NULL 이었다(백필 부재).
--   ④ 고정 '희망 지역'(rental_request_regions)은 스냅샷 없이 100% 살아있는 regions 조인이었다.
-- =============================================================================

ALTER TABLE rental_request_options
    ADD COLUMN question_text_snapshot_user VARCHAR(255) NULL
        COMMENT '제출자가 본 언어의 질문 문구 박제 — 유저 화면 표시의 정본' AFTER answer_text_snapshot,
    ADD COLUMN answer_text_snapshot_user VARCHAR(255) NULL
        COMMENT '제출자가 본 언어의 답 문구 박제(선택지 라벨·지역명). 직접 입력 답은 값 컬럼이 정본이라 NULL'
        AFTER question_text_snapshot_user;

ALTER TABLE rental_request_regions
    ADD COLUMN region_name_snapshot VARCHAR(100) NULL
        COMMENT '제출 당시 지역명(ko) 박제 — 표시는 스냅샷, 집계는 region_id',
    ADD COLUMN region_name_snapshot_user VARCHAR(100) NULL
        COMMENT '제출자가 본 언어의 지역명 박제(ko 외에는 영문 인라인 표기)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 백필. **이미 박힌 진짜 박제가 최우선이다** — 지금 마스터는 원본이 없는 행의 폴백일 뿐이다
-- (이후 마스터 변경부터 보호가 시작되고, 과거 변경까지 복원할 수는 없다).
-- 과거 행의 실제 제출 언어는 복원 불가라 user 스냅샷은 en→ko→code 로 채운다.
-- ─────────────────────────────────────────────────────────────────────────────

-- ⓪-a 노이즈 정리: 값이 전부 NULL 인 직접 답 행은 "답하지 않은 것"이다 — 구 리졸버가
--     코드만 온 본문에서 만들던 행(V13과 함께 고친 검증 갭). 남겨두면 아래 백필이 질문
--     문구를 붙여 담당자 화면에 "답변된 질문"으로 렌더링된다.
DELETE rro FROM rental_request_options rro
    JOIN request_options o ON o.id = rro.request_option_id
WHERE o.parent_id IS NULL
  AND rro.region_id IS NULL
  AND rro.text_value IS NULL
  AND rro.number_value IS NULL
  AND rro.date_value IS NULL;

-- ⓪-b 진짜 박제 구출: V10~V13 사이 직접 답(TEXT·NUMBER·DATE)은 구버그로 **제출 당시
--     질문 문구**가 answer_text_snapshot 자리에 들어 있다 — 이것이 유일한 원본이다.
--     현재 마스터로 채우기 전에 제자리로 옮긴다(순서를 바꾸면 원본이 영구 소실된다).
--     MySQL 의 SET 은 좌→우 평가라 한 문장으로 안전하다.
UPDATE rental_request_options rro
    JOIN request_options o ON o.id = rro.request_option_id
SET rro.question_text_snapshot = COALESCE(rro.question_text_snapshot, rro.answer_text_snapshot),
    rro.answer_text_snapshot   = NULL
WHERE o.parent_id IS NULL
  AND rro.region_id IS NULL
  AND rro.answer_text_snapshot IS NOT NULL;

-- ① 질문 문구: 항목 답이면 부모가, 질문 행 답이면 자기 자신이 질문이다.
--    (⓪-b 에서 원본을 구출한 행은 WHERE 에 안 걸린다 — 지금 마스터는 원본 없는 행만 채운다.)
UPDATE rental_request_options rro
    JOIN request_options o ON o.id = rro.request_option_id
    JOIN request_options q ON q.id = COALESCE(o.parent_id, o.id)
    LEFT JOIN request_option_translations tko ON tko.request_option_id = q.id AND tko.language_code = 'ko'
    LEFT JOIN request_option_translations ten ON ten.request_option_id = q.id AND ten.language_code = 'en'
SET rro.question_text_snapshot      = COALESCE(tko.name, ten.name, q.code),
    rro.question_text_snapshot_user = COALESCE(ten.name, tko.name, q.code)
WHERE rro.question_text_snapshot IS NULL;

-- 위 WHERE 가 못 미치는 행: ko 스냅샷은 있는데(선택지 답) user 만 빈 경우.
UPDATE rental_request_options rro
    JOIN request_options o ON o.id = rro.request_option_id
    JOIN request_options q ON q.id = COALESCE(o.parent_id, o.id)
    LEFT JOIN request_option_translations tko ON tko.request_option_id = q.id AND tko.language_code = 'ko'
    LEFT JOIN request_option_translations ten ON ten.request_option_id = q.id AND ten.language_code = 'en'
SET rro.question_text_snapshot_user = COALESCE(ten.name, tko.name, q.code)
WHERE rro.question_text_snapshot_user IS NULL;

-- ③ 선택지 답의 라벨 백필(ko 빈 행 + user 전체).
UPDATE rental_request_options rro
    JOIN request_options o ON o.id = rro.request_option_id
    LEFT JOIN request_option_translations tko ON tko.request_option_id = o.id AND tko.language_code = 'ko'
    LEFT JOIN request_option_translations ten ON ten.request_option_id = o.id AND ten.language_code = 'en'
SET rro.answer_text_snapshot = COALESCE(rro.answer_text_snapshot, tko.name, ten.name, o.code),
    rro.answer_text_snapshot_user = COALESCE(ten.name, tko.name, o.code)
WHERE o.parent_id IS NOT NULL;

-- ④ ADDRESS 답의 지역명 백필.
UPDATE rental_request_options rro
    JOIN regions r ON r.id = rro.region_id
SET rro.answer_text_snapshot      = COALESCE(rro.answer_text_snapshot, r.name_ko),
    rro.answer_text_snapshot_user = r.name_en
WHERE rro.region_id IS NOT NULL;

-- ⑤ 고정 '희망 지역'의 지역명 박제.
UPDATE rental_request_regions rrr
    JOIN regions r ON r.id = rrr.region_id
SET rrr.region_name_snapshot      = r.name_ko,
    rrr.region_name_snapshot_user = r.name_en
WHERE rrr.region_name_snapshot IS NULL;
