CREATE TABLE subway_lines
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(20)  NOT NULL COMMENT '노선 코드(SEL-2 · GYEONGUI-JUNGANG …) — 행은 dml/05 가 채운다',
    name_ko    VARCHAR(50)  NOT NULL,
    name_en    VARCHAR(100) NOT NULL,
    color      CHAR(7)      NOT NULL COMMENT '#RRGGBB — PO 제공 표',
    is_active  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = 원천에서 사라짐(지우지 않는다)',
    sort_order INT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_sl_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '지하철 노선(승객이 보는 노선 — 원천 노선번호와 1:1 이 아니다). 행은 dml/05_subway.sql 이 채운다';

CREATE TABLE subway_stations
(
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    code       VARCHAR(16)    NOT NULL COMMENT '역 코드 ST0001 … — 장부(dml/tools/subway_station_codes.tsv)가 고정, 재사용 금지',
    name_ko    VARCHAR(50)    NOT NULL,
    name_en    VARCHAR(100)   NOT NULL,
    lat        DECIMAL(10, 7) NOT NULL,
    lng        DECIMAL(10, 7) NOT NULL,
    region_id  BIGINT         NULL COMMENT '시군구 — regions 에 있을 때만(지금 서울 25개 구)',
    is_active  TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '0 = 원천에서 사라짐(지우지 않는다 — 앱이 코드를 들고 있을 수 있다)',
    created_at DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ss_code (code),
    INDEX idx_ss_map (is_active, lat, lng) COMMENT '지도 영역 조회',
    INDEX idx_ss_region (region_id),
    CONSTRAINT fk_ss_region FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '지하철역(환승역은 한 행 — 원천의 노선별 행을 묶었다). 행은 dml/05_subway.sql 이 채운다';

CREATE TABLE subway_station_lines
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    station_id BIGINT      NOT NULL,
    line_id    BIGINT      NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0 COMMENT 'lines[] 순서(마커의 색 점 순서)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ssl_pair (station_id, line_id),
    INDEX idx_ssl_line (line_id) COMMENT '노선 FK 인덱스',
    CONSTRAINT fk_ssl_station FOREIGN KEY (station_id) REFERENCES subway_stations (id) ON DELETE CASCADE,
    CONSTRAINT fk_ssl_line FOREIGN KEY (line_id) REFERENCES subway_lines (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '역이 지나는 노선(환승역은 여러 행). dml/05 가 배포마다 이번 역들의 것을 통째로 다시 맞춘다';