-- =============================================================================
-- V15__unlock_admin_operations.sql — 관리자 조작을 막는 마지막 잠금 해제
--
-- PO 확정 2026-08-26: **관리자는 답변이 달린 질문·선택지도 조건 없이 지우고 바꾼다.**
-- 제출된 요청은 스냅샷(V13)이 지키므로 — 표시가 마스터에 기대지 않으므로 — 막을 이유가 없다.
--
-- 그래서 답변→마스터 FK 를 RESTRICT 에서 **SET NULL** 로 바꾼다:
--   · 마스터 행을 지우면 답변 행의 기계 링크만 끊긴다. 답변 행 자체(값·스냅샷)는 그대로다 —
--     지난 요청의 화면은 1글자도 변하지 않는다(박제 원칙).
--   · 노출 조건의 대상 항목을 지우면 그 조건만 사라진다(질문은 항상 노출로 돌아간다).
-- =============================================================================

-- ① 답변 → 마스터: RESTRICT → SET NULL (링크 컬럼 NULL 허용 선행)
ALTER TABLE rental_request_options DROP FOREIGN KEY fk_rro_option;
ALTER TABLE rental_request_options
    MODIFY request_option_id BIGINT NULL
        COMMENT '답의 마스터 링크. NULL = 마스터가 삭제됨 — 표시는 스냅샷이, 값은 값 컬럼이 정본';
ALTER TABLE rental_request_options
    ADD CONSTRAINT fk_rro_option FOREIGN KEY (request_option_id)
        REFERENCES request_options (id) ON DELETE SET NULL;

-- ② 노출 조건 → 대상 항목: RESTRICT → SET NULL (컬럼은 이미 NULL 허용)
ALTER TABLE request_options DROP FOREIGN KEY fk_ro_show_when;
ALTER TABLE request_options
    ADD CONSTRAINT fk_ro_show_when FOREIGN KEY (show_when_option_id)
        REFERENCES request_options (id) ON DELETE SET NULL;
