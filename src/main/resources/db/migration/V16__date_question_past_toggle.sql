-- =============================================================================
-- V16__date_question_past_toggle.sql — DATE 질문의 "지난 날짜 선택 허용" 축
--
-- "언제부터 입주하고 싶으세요?"는 과거를 고를 수 없어야 하지만, "입국일이 언제였나요?"
-- 같은 질문은 과거가 답이다 — 그래서 코드가 아니라 **관리자가 질문마다 정한다**.
-- V14 로 사라졌던 MOVE_IN_DATE_IN_PAST 검증이 질문 단위 축으로 복원되는 것이기도 하다.
--
-- 기본값 1(허용) — 기존 DATE 질문의 동작을 바꾸지 않는다. 검증은 새 제출·수정부터
-- 적용되고 이미 저장된 답을 소급 무효화하지 않는다(required 와 같은 취급).
-- =============================================================================

ALTER TABLE request_options
    ADD COLUMN input_allow_past TINYINT(1) NOT NULL DEFAULT 1
        COMMENT 'DATE 질문만: 0 = 오늘 이전 날짜를 거부(OPTION_DATE_IN_PAST)' AFTER input_multiline;

-- 시드 입주일 질문은 과거 불가 — 이 축을 만든 이유가 이 질문이다.
UPDATE request_options SET input_allow_past = 0 WHERE code = 'MOVE_IN_HOPE';
