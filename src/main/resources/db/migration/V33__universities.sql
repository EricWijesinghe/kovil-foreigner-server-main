CREATE TABLE universities
(
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    code           VARCHAR(16)    NOT NULL COMMENT '교육부 학교코드(7자리, 캠퍼스마다 다르다) — 행은 dml/04 가 채운다',
    name_ko        VARCHAR(100)   NOT NULL,
    name_en        VARCHAR(200)   NOT NULL,
    short_name_ko  VARCHAR(30)    NULL COMMENT '알약용 — 원천에 없다. NULL 이면 앱이 name',
    short_name_en  VARCHAR(40)    NULL,
    campus_name_ko VARCHAR(50)    NULL COMMENT '원천 본분교 값(제2캠퍼스 …). 본교 · 대학원만 있는 별도 캠퍼스는 NULL',
    campus_name_en VARCHAR(100)   NULL,
    address_road   VARCHAR(255)   NOT NULL COMMENT '도로명 주소(기관 주소) — 검색 부제 · 좌표 재확인의 근거',
    lat            DECIMAL(10, 7) NOT NULL,
    lng            DECIMAL(10, 7) NOT NULL,
    region_id      BIGINT         NULL COMMENT '시군구 — regions 에 있을 때만(지금 서울 25개 구)',
    is_active      TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '0 = 원천에서 사라짐(지우지 않는다 — 앱이 코드를 들고 있을 수 있다)',
    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_univ_code (code),
    INDEX idx_univ_map (is_active, lat, lng) COMMENT '지도 영역 조회',
    INDEX idx_univ_region (region_id),
    CONSTRAINT fk_univ_region FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '대학교(캠퍼스 = 한 점). 행은 dml/04_universities.sql 이 배포마다 멱등으로 채운다 — 관리자 화면은 P3';