CREATE TABLE saved_areas
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    uuid        CHAR(36)      NOT NULL,
    user_id     BIGINT        NOT NULL,
    name        VARCHAR(20)   NOT NULL COMMENT '사용자가 붙인 이름 — 앞뒤 공백을 뺀 1~20자, 같은 이름 허용',
    place_label VARCHAR(40)   NULL COMMENT '앱이 지도 SDK 로 얻은 동 이름 — 서버는 저장만 한다',
    polygon     VARCHAR(2000) NOT NULL COMMENT '정규화한 구역(위도:경도,…) — 목록 API 의 polygon 과 같은 형식',
    filters     VARCHAR(2000) NOT NULL COMMENT '저장할 때의 목록 필터 JSON — 검색에 쓰지 않고 그대로 돌려준다',
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_sa_uuid (uuid),
    INDEX idx_sa_user_created (user_id, created_at) COMMENT '내 목록(최근 순) · 개수',
    CONSTRAINT fk_sa_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '저장한 구역(계정당 20개). 탈퇴하면 탈퇴 서비스가 지운다 — users 는 소프트 삭제라 FK CASCADE 가 돌지 않는다';