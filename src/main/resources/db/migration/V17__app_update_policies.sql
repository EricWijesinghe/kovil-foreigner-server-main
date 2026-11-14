-- =============================================================================
-- V17__app_update_policies.sql — 앱 버전 정책의 정본을 설정(env)에서 DB로
--
-- 권장 안내(latest)는 앱 릴리스마다 올려야 하는 값이라 env 방식은 릴리스마다 서버
-- 재배포를 강제한다. 강제 업데이트는 긴급 상황에 켜는 스위치인데 그 순간 재배포를
-- 만지게 해서는 안 된다 — 관리자 웹에서 즉시 바꾼다(PO 확정 2026-08-26).
--
-- 시드는 1.0.0/1.0.0/URL 없음 = 아무도 강제하지 않는 상태. URL이 비어 있는 동안은
-- 어떤 판정도 발동하지 않는다(보낼 곳 없는 강제 화면 = 락아웃).
-- =============================================================================

CREATE TABLE app_update_policies (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    platform              VARCHAR(16)  NOT NULL COMMENT 'IOS | ANDROID',
    min_supported_version VARCHAR(32)  NOT NULL COMMENT '이 미만은 FORCE(강제 화면)',
    latest_version        VARCHAR(32)  NOT NULL COMMENT '이 미만은 RECOMMEND(안내 1회)',
    store_url             VARCHAR(512) NOT NULL DEFAULT '' COMMENT '비어 있으면 어떤 판정도 발동하지 않는다',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_update_policies_platform (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO app_update_policies (platform, min_supported_version, latest_version, store_url)
VALUES ('IOS', '1.0.0', '1.0.0', ''), ('ANDROID', '1.0.0', '1.0.0', '');
