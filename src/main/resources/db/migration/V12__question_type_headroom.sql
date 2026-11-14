-- =============================================================================
-- **새 질문 유형을 나중에 붙일 여지**를 미리 만든다.
--
-- 유형은 앞으로 늘어난다(DATE_RANGE · LONG_TEXT · FILE …). 그때마다 마이그레이션이
-- 필요한 것은 어쩔 수 없지만, **지금 알면서 남겨 두면 안 되는 것** 셋만 정리한다.
--
-- ① 이름 길이 — VARCHAR(10)은 이미 한계다. 'DATE_RANGE'가 정확히 10자라 한 글자도 못 늘린다.
--    다음 유형을 넣을 때 컬럼 폭 때문에 마이그레이션이 하나 더 붙는 건 낭비다.
-- ② 표시 단위 — "3 회", "300,000 원", "2 년"처럼 숫자 뒤에 붙는 말은 유형이 아니라 **꾸밈**이다.
--    이게 없으면 관리자가 "주 몇 회 수업을 희망하시나요?"를 만들 때 단위를 넣을 자리가 없어
--    NUMBER_WITH_UNIT 같은 유형을 새로 만들자는 요구가 나온다. 유형이 아니라 속성으로 받는다.
-- ③ 여러 줄 — 자유 서술은 TEXT와 다른 유형이 아니라 **같은 유형의 다른 렌더**다.
--    LONG_TEXT 유형을 따로 만들면 검증·저장이 TEXT와 똑같은데 분기만 하나 늘어난다.
--
-- ★ 값 저장은 **타입별 컬럼**을 유지한다(JSON 한 칸으로 바꾸지 않는다).
--   region_id의 외래키와 담당자 화면의 집계·필터가 그 위에 서 있고, JSON으로 옮기면
--   무결성과 질의 가능성을 한꺼번에 잃는다. 새 값 모양이 필요한 유형(FILE 등)은
--   그때 컬럼 하나를 더하는 편이 싸다 — ALTER 한 번이면 끝난다.
-- =============================================================================

-- ① 유형 이름 여유 — 10 → 20
ALTER TABLE request_options
    MODIFY COLUMN selection_type VARCHAR(20) NULL
        COMMENT '질문 행에만 값: SINGLE | MULTI | TEXT | NUMBER | DATE | ADDRESS (확장 예정). 항목 행은 NULL',
    MODIFY COLUMN extra_input_type VARCHAR(20) NOT NULL DEFAULT 'NONE'
        COMMENT '항목만: NONE | TEXT | NUMBER | DATE — 고르면 열리는 입력칸의 종류';

-- ② 표시 단위 — 숫자·텍스트 뒤에 붙는 말. 유형을 늘리지 않고 흡수한다.
ALTER TABLE request_options
    ADD COLUMN input_unit VARCHAR(20) NULL
        COMMENT '입력칸 뒤에 붙는 표시 단위(원 · 년 · 회 …). 값 검증에는 쓰지 않는다';

-- ③ 여러 줄 입력 — TEXT의 렌더 방식이지 별개 유형이 아니다.
ALTER TABLE request_options
    ADD COLUMN input_multiline TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'TEXT 질문을 여러 줄(textarea)로 받는가';
