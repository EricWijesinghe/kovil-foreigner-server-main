-- =============================================================================
-- V14__questionnaire_takeover.sql — 고정 4축(지역·입주일·기간·월세)의 질문지 이관
--
-- PO 확정 2026-08-26 (기존 "지역 등은 고정으로 박는다" 확정을 **명시적으로 번복**):
-- "모두 없애라. 질문과 답변 타입을 모두 질문지가 대응하도록."
--
-- 요청서의 모든 질문이 질문지(request_options)에서 나온다. 앱에 하드코딩된 스텝은 없다.
-- 기존 고정 컬럼(move_in_date 등)은 **레거시 축**으로 남는다 — 이 마이그레이션 이전에
-- 제출된 요청의 데이터가 거기 있다. 새 요청은 답변(rental_request_options)이 정본이다.
--
-- ★ 시드는 code 충돌을 방어한다(WHERE NOT EXISTS) — dev/QA 에서 관리자가 이미 만든
--   질문과 부딪혀 마이그레이션이 죽으면 안 된다.
-- =============================================================================

-- ① 고정 컬럼 비필수화 — 새 요청은 이 컬럼 대신 답변 행을 쓴다.
ALTER TABLE rental_requests
    MODIFY move_in_date DATE NULL
        COMMENT '입주 희망일 — V14부터 레거시. 새 요청은 질문지 답변(DATE)이 정본';

-- ② 고정 질문 4개를 질문지로 시드한다(ko·en 번역 포함). 기존 최상위 질문은 순서를 한 칸 민다.
UPDATE request_options SET sort_order = sort_order + 1 WHERE parent_id IS NULL;

INSERT INTO request_options (parent_id, code, selection_type, sort_order, active, required, input_multiple)
SELECT NULL, 'HOPE_REGION', 'ADDRESS', 1, 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM request_options WHERE code = 'HOPE_REGION');

INSERT INTO request_options (parent_id, code, selection_type, sort_order, active, required)
SELECT NULL, 'LEASE_PERIOD', 'SINGLE',
       (SELECT COALESCE(MAX(o.sort_order), 0) + 1 FROM (SELECT sort_order FROM request_options WHERE parent_id IS NULL) o), 1, 0
WHERE NOT EXISTS (SELECT 1 FROM request_options WHERE code = 'LEASE_PERIOD');

INSERT INTO request_options (parent_id, code, sort_order, active)
SELECT g.id, c.code, c.sort_order, 1
FROM request_options g
JOIN (SELECT 'LEASE_PERIOD_1Y' AS code, 1 AS sort_order
      UNION ALL SELECT 'LEASE_PERIOD_2Y', 2
      UNION ALL SELECT 'LEASE_PERIOD_ETC', 3) c
WHERE g.code = 'LEASE_PERIOD'
  AND NOT EXISTS (SELECT 1 FROM request_options d WHERE d.code = c.code);

INSERT INTO request_options (parent_id, code, selection_type, sort_order, active, required)
SELECT NULL, 'MOVE_IN_HOPE', 'DATE',
       (SELECT COALESCE(MAX(o.sort_order), 0) + 1 FROM (SELECT sort_order FROM request_options WHERE parent_id IS NULL) o), 1, 1
WHERE NOT EXISTS (SELECT 1 FROM request_options WHERE code = 'MOVE_IN_HOPE');

INSERT INTO request_options (parent_id, code, selection_type, sort_order, active, required, input_min_number, input_unit)
SELECT NULL, 'RENT_BUDGET', 'NUMBER',
       (SELECT COALESCE(MAX(o.sort_order), 0) + 1 FROM (SELECT sort_order FROM request_options WHERE parent_id IS NULL) o), 1, 1, 0, '원'
WHERE NOT EXISTS (SELECT 1 FROM request_options WHERE code = 'RENT_BUDGET');

-- ③ 번역 — 비면 화면에 code 가 노출된다. 나머지 언어는 관리자 화면에서 채운다.
INSERT INTO request_option_translations (request_option_id, language_code, name)
SELECT o.id, t.language_code, t.name
FROM request_options o
JOIN (SELECT 'HOPE_REGION' AS code, 'ko' AS language_code, '어디에 살고 싶으세요?' AS name
      UNION ALL SELECT 'HOPE_REGION', 'en', 'Where do you want to live?'
      UNION ALL SELECT 'LEASE_PERIOD', 'ko', '얼마나 사실 예정인가요?'
      UNION ALL SELECT 'LEASE_PERIOD', 'en', 'How long will you stay?'
      UNION ALL SELECT 'LEASE_PERIOD_1Y', 'ko', '1년'
      UNION ALL SELECT 'LEASE_PERIOD_1Y', 'en', '1 year'
      UNION ALL SELECT 'LEASE_PERIOD_2Y', 'ko', '2년'
      UNION ALL SELECT 'LEASE_PERIOD_2Y', 'en', '2 years'
      UNION ALL SELECT 'LEASE_PERIOD_ETC', 'ko', '기타 (미정)'
      UNION ALL SELECT 'LEASE_PERIOD_ETC', 'en', 'Other (undecided)'
      UNION ALL SELECT 'MOVE_IN_HOPE', 'ko', '언제부터 입주하고 싶으세요?'
      UNION ALL SELECT 'MOVE_IN_HOPE', 'en', 'When do you want to move in?'
      UNION ALL SELECT 'RENT_BUDGET', 'ko', '월세 예산은 얼마인가요?'
      UNION ALL SELECT 'RENT_BUDGET', 'en', 'What is your monthly rent budget?') t
  ON t.code = o.code
WHERE NOT EXISTS (SELECT 1 FROM request_option_translations x
                  WHERE x.request_option_id = o.id AND x.language_code = t.language_code);
