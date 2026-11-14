-- =============================================================================
-- 임대 요청서를 **관리자가 정의하는 질문지**로 확장한다 (숨고형).
--
-- 기존 request_options(2단 트리 · 번역 · SINGLE/MULTI · 순서 · active)를 그대로 쓰고
-- 모자란 축만 더한다. 새 테이블을 만들지 않는 이유는 이미 있는 관리자 CRUD·번역·
-- 정렬 API가 전부 이 테이블을 향하고 있어서다.
--
-- ★ 고정 질문은 고정으로 남는다 — 지역·입주일·거주기간·월세는 rental_requests 의
--   컬럼이고 담당자 대장의 필터·정렬·매칭이 직접 읽는다. 동적 답변으로 옮기면
--   그 화면들이 전부 EAV 조인이 된다. 동적화 대상은 **그 밖의 질문**뿐이다.
--
-- 과거 답변을 지키는 방법은 세 층이다:
--   ① 문구 수정  → 허용하되 답변 행에 그때 문구를 박제(아래 *_snapshot)
--   ② 구조 변경  → 답변이 하나라도 있으면 잠금(INV-12 선례, 도메인이 소유)
--   ③ 삭제       → 이미 막혀 있다(V1 의 ON DELETE RESTRICT + active 플래그)
-- =============================================================================

-- ── ① 질문 정의 확장 ---------------------------------------------------------
ALTER TABLE request_options
    -- 그룹(질문) 행에만 의미가 있다. 항목 행은 NULL.
    ADD COLUMN required TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '그룹만: 답을 반드시 받아야 하는 질문인가. 나중에 켜도 과거 요청에 소급하지 않는다',

    -- 조건부 질문 — 이 항목이 선택됐을 때만 이 질문을 보인다.
    -- 깊이 1 고정: 조건이 걸린 질문의 항목은 다시 다른 질문의 조건이 될 수 없다.
    -- (연쇄 의존은 관리자가 추적할 수 없다 — 2단 트리를 3단으로 못 늘리는 것과 같은 이유다.)
    ADD COLUMN show_when_option_id BIGINT NULL
        COMMENT '그룹만: 이 항목(2단 행)이 선택된 경우에만 노출. NULL = 항상 노출',

    -- 항목이 여는 추가 입력("기타 → 직접 입력", "원하는 날짜가 있어요 → 날짜").
    -- 항목(2단) 행에만 의미가 있다.
    ADD COLUMN extra_input_type VARCHAR(10) NOT NULL DEFAULT 'NONE'
        COMMENT '항목만: NONE | TEXT | NUMBER | DATE — 고르면 열리는 입력칸의 종류',
    ADD COLUMN extra_max_length INT NULL
        COMMENT '항목만: TEXT 일 때 글자 수 상한(미지정 시 서버 기본 255)',
    ADD COLUMN extra_required TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '항목만: 그 항목을 골랐으면 추가 입력도 반드시 채워야 하는가';

ALTER TABLE request_options
    ADD CONSTRAINT fk_ro_show_when FOREIGN KEY (show_when_option_id)
        REFERENCES request_options (id) ON DELETE RESTRICT;

CREATE INDEX idx_ro_show_when ON request_options (show_when_option_id)
    COMMENT '조건부 질문 역참조 — 항목을 지우려 할 때 걸린 질문을 찾는다';

-- 질문 밑에 붙는 설명 한 줄("해당 없다면 다음 질문으로 넘어가주세요").
-- 이름과 같은 번역 테이블에 둔다 — 언어가 늘 때 한 곳만 채우면 되게.
ALTER TABLE request_option_translations
    ADD COLUMN description VARCHAR(255) NULL
        COMMENT '질문 밑 보조 설명. NULL 이면 표시하지 않는다';

-- ── ② 답변에 값과 스냅샷을 싣는다 ---------------------------------------------
-- 지금까지는 "어떤 항목을 골랐다"만 남았다. 이제 그 항목이 연 입력값과,
-- **제출 당시 화면에 뭐라고 쓰여 있었는지**를 함께 남긴다.
--
-- 스냅샷이 필요한 이유: 관리자가 "원룸"을 "투룸"으로 고치면 과거 답변이 조용히
-- 거짓말이 된다. 담당자는 몇 달 뒤 이 답을 근거로 계약을 진행한다.
-- 표시는 스냅샷으로, 집계·필터는 request_option_id 로 한다.
ALTER TABLE rental_request_options
    ADD COLUMN text_value VARCHAR(255) NULL
        COMMENT 'extra_input_type=TEXT 로 받은 직접 입력',
    ADD COLUMN number_value BIGINT NULL
        COMMENT 'extra_input_type=NUMBER 로 받은 값',
    ADD COLUMN date_value DATE NULL
        COMMENT 'extra_input_type=DATE 로 받은 값',
    ADD COLUMN question_text_snapshot VARCHAR(255) NULL
        COMMENT '제출 당시 질문 문구. 마스터가 바뀌어도 과거 화면은 이걸 그대로 보여준다',
    ADD COLUMN answer_text_snapshot VARCHAR(255) NULL
        COMMENT '제출 당시 선택지 문구. 위와 같은 이유';
