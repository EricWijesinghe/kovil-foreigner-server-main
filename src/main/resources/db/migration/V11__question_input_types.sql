-- =============================================================================
-- 질문 **유형** 자체를 관리자가 정한다 (숨고의 items[].name = radio | address3 … 축).
--
-- V10 이 만든 것은 "선택지에 딸린 추가 입력"(기타 → 직접 입력)이었다. 그건 선택형 질문의
-- 곁가지일 뿐이고, **선택지가 아예 없는 질문**(주소·숫자·텍스트·날짜)은 만들 수 없었다.
-- 여기서 그 축을 연다.
--
-- 유형별 답의 생김새가 다르다:
--   SINGLE / MULTI  → 선택한 **항목 행**을 참조한다(기존 그대로). 선택지가 있다.
--   TEXT / NUMBER / DATE / ADDRESS → 선택지가 없다. 답이 **질문 행 자체**에 붙는다.
--
-- 그래서 rental_request_options.request_option_id 는 이제 항목 행뿐 아니라
-- **질문(그룹) 행**도 가리킬 수 있다 — 단 그 질문이 비선택형일 때만(서비스가 강제).
-- V1 의 "항목 행만 참조" 주석은 여기서 뒤집힌다.
--
-- ADDRESS 는 새 주소 체계를 만들지 않는다 — 이미 있는 regions(시도→시군구) 마스터를
-- 그대로 재사용한다. 앱도 이미 쓰는 RegionPicker 를 그대로 띄운다.
-- ★ 고정 질문인 "희망 지역"(rental_requests ↔ rental_request_regions)과는 **다른 축**이다.
--   그쪽은 담당자 대장의 필터·매칭이 직접 읽는 계약 컬럼이라 동적화하지 않는다(PO 확정).
--   ADDRESS 질문은 "직장 위치는 어디인가요?" 같은 **추가** 지역 질문을 위한 것이다.
-- =============================================================================

-- selection_type 은 이제 "선택 방식"이 아니라 **입력 유형**이다.
-- 컬럼명을 바꾸지 않는 이유: 이름 하나 바꾸자고 엔티티·DTO·관리자 화면·앱을 한꺼번에
-- 건드리면 이 마이그레이션의 위험이 몇 배가 된다. 의미 확장은 주석과 enum 이 책임진다.
ALTER TABLE request_options
    MODIFY COLUMN selection_type VARCHAR(10) NULL
        COMMENT '질문 행에만 값: SINGLE | MULTI | TEXT | NUMBER | DATE | ADDRESS. 항목 행은 NULL';

-- 비선택형 질문의 입력 제약. 선택형(SINGLE/MULTI)에는 의미가 없다.
ALTER TABLE request_options
    ADD COLUMN input_max_length INT NULL
        COMMENT 'TEXT 질문의 글자 수 상한(미지정 시 서버 기본 255)',
    ADD COLUMN input_min_number BIGINT NULL
        COMMENT 'NUMBER 질문의 하한(미지정 시 0)',
    ADD COLUMN input_max_number BIGINT NULL
        COMMENT 'NUMBER 질문의 상한(미지정 시 제한 없음)',
    ADD COLUMN input_multiple TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'ADDRESS 질문에서 여러 지역을 고를 수 있는가';

-- ADDRESS 질문의 답 — 지역 마스터를 참조한다. 여러 개면 행이 여러 개다.
-- ON DELETE RESTRICT: 지역을 지우려 해도 답이 걸려 있으면 막힌다(과거 요청 보존).
ALTER TABLE rental_request_options
    ADD COLUMN region_id BIGINT NULL
        COMMENT 'ADDRESS 질문의 답(시군구 행). 다른 유형에서는 NULL';

ALTER TABLE rental_request_options
    ADD CONSTRAINT fk_rro_region FOREIGN KEY (region_id)
        REFERENCES regions (id) ON DELETE RESTRICT;

CREATE INDEX idx_rro_region ON rental_request_options (region_id)
    COMMENT '지역 질문의 수요 집계';

-- 같은 질문에 여러 답이 달릴 수 있게 된다(MULTI · ADDRESS 다중 · 비선택형 재저장).
-- V1 의 uq_rro_pair(rental_request_id, request_option_id) 는 질문 행 참조가 생기면서
-- 너무 좁아졌다 — ADDRESS 다중 선택이 한 질문에 두 행을 만들기 때문이다.
--
-- ★ 순서가 중요하다 — fk_rro_request 가 uq_rro_pair 를 자기 인덱스로 쓰고 있어서,
--   먼저 지우면 "Cannot drop index: needed in a foreign key constraint"(1553)로 죽는다.
--   새 인덱스의 **맨 왼쪽 컬럼이 rental_request_id** 라 FK 가 그대로 넘겨받을 수 있다.
--   (실측 2026-08-26: 지우기부터 쓴 첫 판이 이 오류로 실패했다.)
CREATE UNIQUE INDEX uq_rro_answer
    ON rental_request_options (rental_request_id, request_option_id, region_id)
    COMMENT '같은 질문에 같은 지역이 두 번 들어가는 것만 막는다(region_id NULL 은 MySQL 에서 중복 허용)';
ALTER TABLE rental_request_options DROP INDEX uq_rro_pair;
