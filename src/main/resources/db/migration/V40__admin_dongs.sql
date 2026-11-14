-- =============================================================================
-- V40 — 행정동(읍면동) 단계 추가 (2026-11-13)
--   regions 는 시도 → 시군구 2단 그대로 둔다 — 시군구를 전제로 한 코드(매물 지역 · 임대 요청 · 지역 검색 · 관리자 재정렬)가
--   3단 행을 만나면 의미가 바뀐다. 그래서 동은 별도 테이블이고 시군구(regions)에 매단다.
--   용도: 위치(GPS) → 시도/시군구/동 판정, 동 단위 검색 · 지도 경계 표시. 좌표 판정은 이 테이블의 경계로 한다 —
--   카카오 · 네이버 응답은 저장하지 않는다(카카오 운영정책: 캐시 금지).
--   행은 dml/08 이 채운다(원천: 통계청 SGIS 행정동 경계 · 가공 vuski/admdongkor · CC BY 4.0 — 출처표시 의무).
--   경계는 WGS84(SRID 4326) MULTIPOLYGON. bbox 칸은 공간 인덱스 없이도 빠른 1차 거름과 앱 지도 맞춤에 쓴다.
-- =============================================================================
CREATE TABLE admin_dongs
(
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    code           CHAR(10)       NOT NULL COMMENT '행정동 코드(행정안전부 10자리) — 앱이 들고 다니는 안정 키',
    stat_code      VARCHAR(10)    NULL COMMENT '통계청 행정동 코드(SGIS adm_cd) — 통계 결합용',
    region_id      BIGINT         NOT NULL COMMENT '시군구(regions 2단)',
    name_ko        VARCHAR(50)    NOT NULL,
    name_en        VARCHAR(100)   NOT NULL COMMENT '국어의 로마자 표기법(Sajik-dong) — 관용 표기는 생성기 OFFICIAL_EN 표',
    center_lat     DECIMAL(10, 7) NOT NULL COMMENT '동 안에 반드시 들어가는 대표점(이름표 · 가까운 동)',
    center_lng     DECIMAL(10, 7) NOT NULL,
    min_lat        DECIMAL(10, 7) NOT NULL,
    min_lng        DECIMAL(10, 7) NOT NULL,
    max_lat        DECIMAL(10, 7) NOT NULL,
    max_lng        DECIMAL(10, 7) NOT NULL,
    boundary       MULTIPOLYGON   NOT NULL SRID 4326 COMMENT '단순화한 경계(약 10 m)',
    source_version VARCHAR(20)    NOT NULL COMMENT '원천 판(ver20261001) — 다음 판에 없으면 is_active = 0',
    is_active      TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '0 = 개편으로 사라진 동(지우지 않는다 — 앱이 코드를 들고 있을 수 있다)',
    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_dongs_code (code),
    INDEX idx_admin_dongs_region (region_id, is_active),
    INDEX idx_admin_dongs_bbox (is_active, min_lat, max_lat),
    SPATIAL INDEX sp_admin_dongs_boundary (boundary),
    CONSTRAINT fk_admin_dongs_region FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '행정동(읍면동). 시군구 아래 3단 — regions 와 분리(2단 전제 코드 보호)';
