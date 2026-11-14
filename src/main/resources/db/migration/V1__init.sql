-- =============================================================================
-- V1__init.sql — wonjoomin-foreigner 초기 스키마
--
-- 한국 거주(예정) 외국인 대상 부동산 임대 플랫폼.
-- 임대인 ↔ 플랫폼사 전대차는 오프라인 관리(DB 도메인 없음), 플랫폼사 ↔ 외국인 임대차만 담는다.
--
-- 표기 규약
--   · 모든 테이블 utf8mb4 / utf8mb4_0900_ai_ci — VARCHAR FK(language_code)가 성립하려면 필수
--   · 외부 노출 식별자는 uuid, DB PK(BIGINT)는 응답에 노출하지 않는다
--   · 시각은 DATETIME(6), 날짜만 필요한 값은 DATE
--   · 동시전이가 존재하는 테이블에만 version(낙관적 락)
--   · soft delete(deleted_at)는 "되살릴 수 있는 것"에만 — 심사·계약·수납 기록에는 없다
--   · 값 추가가 예정된 축은 ENUM이 아니라 VARCHAR (ALTER 회피)
--
-- 생성 순서: 언어/국가 → 회원 → 파일 → 외국인 인증 → 임대 요청 → 계약 → 월세 → 알림 → 커뮤니티
--
-- 시드는 별도: V2__seed_master_data.sql
-- =============================================================================


-- =============================================================================
-- D0-1. 공통 — 언어 · 국가
-- =============================================================================
CREATE TABLE languages
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(10) NOT NULL COMMENT 'ko|en|ja|zh|ne — BCP47 서브셋. 번역 테이블이 FK로 참조하는 자연키',
    native_name  VARCHAR(50) NOT NULL COMMENT '"한국어" "English" — 언어 선택 화면은 항상 그 언어로 표기한다',
    english_name VARCHAR(50) NOT NULL COMMENT '관리자 화면 표기용',
    is_default   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1 = 번역 누락 시 폴백. 정확히 한 행만 1(서비스 가드)',
    active       TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 = 선택 목록에서 숨김(번역이 덜 찬 언어의 사전 등록용)',
    sort_order   INT         NOT NULL DEFAULT 0,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_languages_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '지원 언어 마스터. 언어 추가 = 행 추가 + 번역 채우기(배포 불필요)';


CREATE TABLE countries
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       CHAR(2)      NOT NULL COMMENT 'ISO 3166-1 alpha-2. 국적의 기계 축',
    name_en    VARCHAR(100) NOT NULL COMMENT '영문명이 정본. 번역 행이 없으면 이 값으로 폴백',
    flag_emoji VARCHAR(16)  NULL COMMENT '국기 이모지 — 200개 국기 이미지를 호스팅하지 않기 위한 선택',
    is_active  TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '주요 출신국 상단 고정용',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_countries_code (code),
    INDEX idx_countries_active (is_active, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '국가 마스터(국적 축). ISO 전체를 시드하고 노출은 is_active로 큐레이션';


CREATE TABLE country_translations
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    country_id    BIGINT       NOT NULL,
    language_code VARCHAR(10)  NOT NULL,
    name          VARCHAR(100) NOT NULL COMMENT '"베트남" / "Việt Nam"',
    PRIMARY KEY (id),
    UNIQUE KEY uq_ct_pair (country_id, language_code),
    CONSTRAINT fk_ct_country  FOREIGN KEY (country_id) REFERENCES countries (id) ON DELETE CASCADE,
    CONSTRAINT fk_ct_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '국가명 번역 오버레이. 행이 없으면 countries.name_en으로 폴백(전량 채움 의무 없음)';


-- =============================================================================
-- D1. 회원 · 인증
--
-- CI를 쓰지 않는다(외국인 대상). 계정 동일성 축은 users.phone_hash UNIQUE —
-- 한국 번호 필수(통신사 실명 개통 전제)가 그 신뢰의 근거다.
-- 소셜 최초 로그인도 휴대폰 인증을 강제하고, 이미 있는 번호면 기존 계정에 수단을 붙인다.
--
-- ★ 닉네임·프로필사진은 v1 커뮤니티가 전면 익명이라 어디에도 노출되지 않지만 컬럼과 발급은
--   유지한다(PO 확정): 익명·비익명 게시판이 공존할 수 있고, 컬럼만 두고 비워두면 전환일에
--   전 회원 백필이 필요해진다. 닉네임은 가입 트랜잭션에서 발급한다.
-- =============================================================================

CREATE TABLE users
(
    id                     BIGINT                                  NOT NULL AUTO_INCREMENT,
    uuid                   CHAR(36)                                NOT NULL COMMENT '외부 노출 식별자(JWT sub)',
    name                   VARCHAR(100)                            NULL COMMENT '표시용 이름(가입 입력). 계약 명의의 정본이 아니다 — 법적 명의는 foreigner_verifications.full_name',
    nickname               VARCHAR(50)                             NULL COMMENT '커뮤니티 표시명(비익명 게시판용). 가입 시 자동 발급 — NULL은 발급 실패의 흔적이지 정상 상태가 아니다',
    nickname_normalized    VARCHAR(50)                             NULL COMMENT '유사중복 판정 축(소문자). 유니크는 반드시 이 컬럼에',
    is_nickname_custom     TINYINT(1)                              NOT NULL DEFAULT 0 COMMENT '1 = 사용자 입력, 0 = 자동 생성. 금칙어 소급 검사 대상 선별 축',
    nickname_changed_at    DATETIME(6)                             NULL COMMENT '최근 변경 시각. 변경 빈도 제한을 나중에 걸기 위한 축 — 없으면 소급 불가',
    phone                  VARCHAR(255)                            NULL COMMENT 'AES-256-GCM 암호문. NULL = 탈퇴 익명화',
    phone_hash             VARCHAR(255)                            NULL COMMENT 'HMAC-SHA256. 1인 1계정 판정 축(CI 대체). NULL = 탈퇴로 해제되어 재가입 가능',
    phone_verified_at      DATETIME(6)                             NULL COMMENT 'NULL이면 임대 요청 이전 단계도 진입 불가',
    email                  VARCHAR(255)                            NULL COMMENT '연락·알림용 스냅샷. 로그인 식별자의 정본은 user_auth.identifier — Apple 릴레이 이메일 때문에 UNIQUE를 걸지 않는다',
    nationality_country_id BIGINT                                  NULL COMMENT '국적. 가입 시 선택(선택사항), 인증 승인 시 심사값으로 확정',
    preferred_language     VARCHAR(10)                             NOT NULL DEFAULT 'en' COMMENT 'UI 언어 + 알림 문구 언어의 축',
    profile_image_url      VARCHAR(500)                            NULL COMMENT '비익명 게시판·마이페이지용. v1은 업로드 UI 미노출(아무도 볼 수 없는 사진을 올리라 할 수 없다)',
    status                 ENUM ('ACTIVE','SUSPENDED','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE' COMMENT 'WITHDRAWN 전환 시 PII 익명화 + phone_hash 해제',
    role                   ENUM ('USER','MANAGER','ADMIN')         NOT NULL DEFAULT 'USER' COMMENT 'USER < MANAGER(담당자) < ADMIN(관리자). 테이블이 아니라 ENUM인 이유: 값마다 코드의 권한 분기가 다르다',
    created_at             DATETIME(6)                             NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)                             NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at             DATETIME(6)                             NULL COMMENT 'soft delete. 계약·수납 이력 보존을 위해 하드 삭제 금지',
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_uuid (uuid),
    UNIQUE KEY uq_users_phone_hash (phone_hash),
    UNIQUE KEY uq_users_nickname_normalized (nickname_normalized),
    INDEX idx_users_role (role) COMMENT '담당자·관리자 목록(회원 관리 화면)',
    CONSTRAINT fk_users_country  FOREIGN KEY (nationality_country_id) REFERENCES countries (id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_language FOREIGN KEY (preferred_language) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '회원. 신원 앵커는 phone_hash(CI 미사용), 로그인 수단은 user_auth가 소유';


CREATE TABLE user_auth
(
    id            BIGINT                                     NOT NULL AUTO_INCREMENT,
    user_id       BIGINT                                     NOT NULL,
    auth_type     ENUM ('LOCAL','APPLE','GOOGLE','FACEBOOK') NOT NULL,
    identifier    VARCHAR(255)                               NOT NULL COMMENT 'LOCAL=이메일 / 소셜=provider sub. Apple sub는 앱 번들 단위로 고정 — 번들ID가 바뀌면 계정이 끊긴다',
    password_hash VARCHAR(255)                               NULL COMMENT 'BCrypt. LOCAL만 값이 있고 소셜은 NULL',
    created_at    DATETIME(6)                                NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at    DATETIME(6)                                NULL COMMENT '수단 해제(soft delete). 탈퇴 시엔 하드 삭제 — 유니크 슬롯 해제 + PII 제거',
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_auth_type_identifier (auth_type, identifier),
    INDEX idx_user_auth_user (user_id),
    CONSTRAINT fk_user_auth_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '로그인 수단 (1 user : N auth). 같은 번호로 여러 수단을 붙이는 계정 통합이 존재 이유';


-- Redis 소유: 인증번호 6자리(TTL 3분) · 시도 횟수 · 재발송 쿨다운 · 일일 발송 상한
-- DB 소유: 인증 성공 사실만. 행의 존재 자체가 인증 성공을 뜻한다 —
--          실패를 남기면 대량 발송 공격이 그대로 테이블 증식이 된다.
CREATE TABLE phone_verifications
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL COMMENT '가입 전 인증이면 NULL — 가입 완료 시 연결. NULL 잔존 행은 미완가입의 흔적',
    phone       VARCHAR(255) NOT NULL COMMENT 'AES-256-GCM 암호문',
    phone_hash  VARCHAR(255) NOT NULL COMMENT 'users.phone_hash와 같은 키로 계산해야 대조가 성립',
    purpose     VARCHAR(30)  NOT NULL COMMENT 'SIGNUP|LOGIN|PHONE_CHANGE|PASSWORD_RESET',
    verified_at DATETIME(6)  NOT NULL COMMENT '인증번호 일치 시각',
    consumed_at DATETIME(6)  NULL COMMENT '이 인증으로 가입·변경이 완료된 시각. NULL = 미소비 → 재사용 차단 판정 축',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_pv_hash_purpose (phone_hash, purpose, id) COMMENT '"이 번호가 방금 인증됐나" 조회 축',
    CONSTRAINT fk_pv_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '휴대폰 인증 성공 이력(coolsms). DB에 인증번호를 저장하지 않는다';


CREATE TABLE refresh_tokens
(
    id              BIGINT                              NOT NULL AUTO_INCREMENT,
    user_id         BIGINT                              NOT NULL,
    token_hash      VARCHAR(255)                        NOT NULL COMMENT 'HMAC-SHA256(원문). 원문은 저장하지 않으며 조회도 해시로 한다',
    status          ENUM ('ACTIVE','REVOKED','EXPIRED') NOT NULL DEFAULT 'ACTIVE',
    issued_at       DATETIME(6)                         NOT NULL,
    expires_at      DATETIME(6)                         NOT NULL,
    revoked_at      DATETIME(6)                         NULL,
    parent_token_id BIGINT                              NULL COMMENT '회전 계보(탈취 감사용). NULL = 로그인으로 처음 발급',
    created_at      DATETIME(6)                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rt_token_hash (token_hash),
    INDEX idx_rt_user_status (user_id, status),
    INDEX idx_rt_expires (expires_at, status),
    CONSTRAINT fk_rt_user   FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_parent FOREIGN KEY (parent_token_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '불투명 리프레시 토큰. 원문 미저장, 회전 계보만 남긴다';


CREATE TABLE device_tokens
(
    id           BIGINT                       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)                     NOT NULL,
    user_id      BIGINT                       NOT NULL,
    token        VARCHAR(512)                 NOT NULL COMMENT 'FCM 토큰. 전역 유니크 — 기기 재등록 시 소유자를 이전한다',
    platform     ENUM ('IOS','ANDROID','WEB') NOT NULL,
    last_used_at DATETIME(6)                  NULL,
    created_at   DATETIME(6)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dt_uuid (uuid),
    UNIQUE KEY uq_dt_token (token),
    INDEX idx_dt_user (user_id),
    CONSTRAINT fk_dt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'FCM 디바이스 토큰. 월세 청구일 푸시의 발송 대상 축';


CREATE TABLE user_consents
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    consent_type VARCHAR(30) NOT NULL COMMENT 'TERMS_OF_SERVICE|PRIVACY_POLICY|PRIVACY_THIRD_PARTY|MARKETING',
    agreed       TINYINT(1)  NOT NULL COMMENT '철회도 새 행(agreed=0)으로 남긴다(append-only) — (user_id, type)의 최신 행이 현재 상태',
    version      VARCHAR(20) NULL COMMENT '동의한 약관 버전 — 개정 시 재동의 판정 축',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_uc_user_type (user_id, consent_type, id),
    CONSTRAINT fk_uc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '약관·마케팅 동의 이력(전자상거래·개보법 보존 의무)';


-- =============================================================================
-- D0-2. 공통 — 파일 (users 뒤에 온다: uploader_id FK)
--
-- 참조 방식이 두 갈래인 것은 의도다:
--   · 민감(인증문서·계약서·입주확인서) → file_id FK + 공개 URL 미생성(presigned GET)
--   · 비민감(커뮤니티 이미지·프로필·은행 아이콘) → URL 문자열 / 공개 URL 사용
-- =============================================================================

CREATE TABLE files
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid          CHAR(36)     NOT NULL,
    storage_key   VARCHAR(500) NOT NULL COMMENT '스토리지 내 경로. 벤더가 바뀌어도 키 체계는 불변',
    original_name VARCHAR(255) NULL,
    content_type  VARCHAR(100) NOT NULL,
    byte_size     BIGINT       NOT NULL COMMENT 'presign 후 confirm 시 실측값(클라이언트 신고값 신뢰 금지)',
    purpose       VARCHAR(30)  NOT NULL COMMENT 'VERIFICATION_DOC|MOVE_IN_PHOTO|COMMUNITY_IMAGE|PROFILE_IMAGE|BANK_ICON. 계약서는 받지 않는다(업로드 경로 없음)',
    uploader_id   BIGINT       NULL COMMENT '업로드 주체. NULL = 시스템/시드',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DELETED — DELETED는 정리 배치가 deleted/ 프리픽스로 격리 이동(즉시 파기 안 함)',
    is_moved      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'deleted/ 이동 완료 마킹 — 정리 배치 멱등 축',
    file_url      VARCHAR(500) NULL COMMENT '공개 URL 스냅샷. 민감 파일은 공개 URL을 만들지 않는다 = NULL, 매 조회 시 단기 presigned GET',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_files_uuid (uuid),
    INDEX idx_files_cleanup (status, is_moved) COMMENT '정리 배치 스캔 축',
    CONSTRAINT fk_files_uploader FOREIGN KEY (uploader_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '업로드 파일 원장. presign(temp/, DB 무기록) → confirm(실측 검증 + 확정 이동 + ACTIVE 기록)';


-- =============================================================================
-- D2. 외국인 인증
--
-- 요건을 코드가 아니라 데이터로 소유한다. 신분이 늘어도 코드가 하는 일은 같다:
-- "요건 테이블을 읽고 → 필수 문서가 다 왔는지 검사 → 심사 큐에 올림".
-- 따라서 신분 추가 = INSERT 몇 줄이고 배포가 필요 없다.
-- 신청 건은 이력보존형: 재신청 = 새 행, 최신 행 = 현재 상태, 심사 대기 중 재신청은 409.
-- =============================================================================

CREATE TABLE resident_statuses
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(30) NOT NULL COMMENT 'STUDENT|WORKER|… 기계 축. 코드가 이 값으로 분기하면 설계 위반',
    icon_key   VARCHAR(40) NULL COMMENT 'FE 아이콘 키 — 신분 추가 시 앱 배포를 피하는 축',
    sort_order INT         NOT NULL DEFAULT 0,
    active     TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 = 신규 신청에서 숨김(기존 승인 건은 유지)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rs_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '체류 신분 마스터. ENUM이 아닌 이유: 신분 추가가 코드 분기를 만들지 않기 때문';


CREATE TABLE resident_status_translations
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    resident_status_id BIGINT       NOT NULL,
    language_code      VARCHAR(10)  NOT NULL,
    name               VARCHAR(100) NOT NULL COMMENT '"유학생" / "Student"',
    description        VARCHAR(255) NULL COMMENT '선택 화면 보조 설명',
    PRIMARY KEY (id),
    UNIQUE KEY uq_rst_pair (resident_status_id, language_code),
    CONSTRAINT fk_rst_status   FOREIGN KEY (resident_status_id) REFERENCES resident_statuses (id) ON DELETE CASCADE,
    CONSTRAINT fk_rst_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE document_types
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(40) NOT NULL COMMENT 'PASSPORT|ARC|ENROLLMENT_CERT|EMPLOYMENT_CERT|…',
    is_sensitive TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1 = 신분증류. 보관기간·접근로그 정책의 축',
    max_files    TINYINT     NOT NULL DEFAULT 1 COMMENT '여권 앞·뒤처럼 복수장 허용 문서의 상한',
    sort_order   INT         NOT NULL DEFAULT 0,
    active       TINYINT(1)  NOT NULL DEFAULT 1,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dt_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '제출 문서 종류 마스터. 요건 행에 라벨을 두지 않고 마스터로 뺀 이유: 여권 등은 모든 신분이 공유한다';


CREATE TABLE document_type_translations
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    document_type_id BIGINT       NOT NULL,
    language_code    VARCHAR(10)  NOT NULL,
    name             VARCHAR(100) NOT NULL COMMENT '"재학증명서" / "Certificate of Enrollment"',
    guide_note       VARCHAR(500) NULL COMMENT '촬영 가이드 — 반려율을 좌우하는 문구',
    PRIMARY KEY (id),
    UNIQUE KEY uq_dtt_pair (document_type_id, language_code),
    CONSTRAINT fk_dtt_doc      FOREIGN KEY (document_type_id) REFERENCES document_types (id) ON DELETE CASCADE,
    CONSTRAINT fk_dtt_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE resident_status_documents
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    resident_status_id BIGINT      NOT NULL,
    document_type_id   BIGINT      NOT NULL,
    is_required        TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 = 선택 제출(있으면 심사 가속)',
    sort_order         INT         NOT NULL DEFAULT 0 COMMENT '제출 화면 노출 순서',
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rsd_pair (resident_status_id, document_type_id),
    INDEX idx_rsd_status (resident_status_id, sort_order) COMMENT '제출 화면 요건 조회 축',
    CONSTRAINT fk_rsd_status FOREIGN KEY (resident_status_id) REFERENCES resident_statuses (id) ON DELETE CASCADE,
    CONSTRAINT fk_rsd_doc    FOREIGN KEY (document_type_id) REFERENCES document_types (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '신분별 요구 문서 매트릭스. 요건 변경 = 이 테이블 행 조작(배포 불필요)이 설계 전제';


CREATE TABLE foreigner_verifications
(
    id                      BIGINT                                        NOT NULL AUTO_INCREMENT,
    uuid                    CHAR(36)                                      NOT NULL,
    user_id                 BIGINT                                        NOT NULL,
    resident_status_id      BIGINT                                        NOT NULL,
    resident_status_code    VARCHAR(30)                                   NOT NULL COMMENT '제출 시점 스냅샷 — 마스터가 바뀌어도 심사 근거 보존',
    required_document_codes JSON                                          NOT NULL COMMENT '제출 시점 요건 스냅샷 — "그때 무엇을 요구했나"의 감사 축',

    nationality_country_id  BIGINT                                        NOT NULL,
    full_name               VARCHAR(100)                                  NOT NULL COMMENT '여권 영문 성명 — 계약 명의의 정본',
    birth_date              DATE                                          NOT NULL,

    status                  ENUM ('PENDING_REVIEW','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING_REVIEW',
    reject_reason           VARCHAR(255)                                  NULL COMMENT 'REJECTED 시 필수(v1 자유 텍스트)',
    reviewed_by             BIGINT                                        NULL COMMENT '심사자(role=ADMIN). NULL = 미심사 행',
    reviewed_at             DATETIME(6)                                   NULL,
    expires_at              DATE                                          NULL COMMENT '재인증 요구 시점(심사자 입력). NULL = 무기한',

    version                 BIGINT                                        NOT NULL DEFAULT 0 COMMENT '사용자 재제출 vs 관리자 심사 동시전이 차단',
    created_at              DATETIME(6)                                   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)                                   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_fv_uuid (uuid),
    INDEX idx_fv_user (user_id, id) COMMENT '유저별 최신 행 조회(임대 요청 게이트의 핵심 쿼리)',
    INDEX idx_fv_status (status, id) COMMENT '관리자 심사 대기열',
    CONSTRAINT fk_fv_user     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fv_status   FOREIGN KEY (resident_status_id) REFERENCES resident_statuses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fv_country  FOREIGN KEY (nationality_country_id) REFERENCES countries (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fv_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '외국인 인증 신청 건. soft delete 없음 — 심사 감사 기록이라 삭제 개념이 없다(반려 후 새 행만)';

-- (미결) 중복 인증 탐지를 도입하려면 아래 두 줄이면 된다. PO 미확정이라 V1 미포함.
--   ALTER TABLE foreigner_verifications
--       ADD COLUMN full_name_normalized VARCHAR(100) NOT NULL AFTER full_name,
--       ADD INDEX idx_fv_identity (nationality_country_id, full_name_normalized, birth_date);


CREATE TABLE foreigner_verification_documents
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    verification_id    BIGINT      NOT NULL,
    document_type_id   BIGINT      NOT NULL,
    document_type_code VARCHAR(40) NOT NULL COMMENT '스냅샷 — 마스터 변경과 무관하게 "무엇을 냈는지" 고정',
    file_id            BIGINT      NOT NULL COMMENT 'files.purpose = VERIFICATION_DOC. 공개 URL 없음',
    sort_order         INT         NOT NULL DEFAULT 0 COMMENT '복수장 문서(여권 앞·뒤)의 순서',
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_fvd_file (file_id) COMMENT '한 파일 = 한 제출물(재제출은 재업로드) — 감사 무결성',
    INDEX idx_fvd_verification (verification_id, sort_order),
    CONSTRAINT fk_fvd_verification FOREIGN KEY (verification_id) REFERENCES foreigner_verifications (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fvd_doc          FOREIGN KEY (document_type_id) REFERENCES document_types (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fvd_file         FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '인증 제출 문서';


-- =============================================================================
-- D3. 임대 요청
--
-- 값(범위·날짜)은 컬럼, 항목(체크박스)은 테이블. 항목은 늘어날 게 확실하고
-- 코드가 값마다 분기하지 않으므로 데이터로 소유한다.
-- =============================================================================

CREATE TABLE regions
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id  BIGINT       NULL COMMENT 'NULL = 시도(1단). 값 있음 = 시군구(2단)',
    code       VARCHAR(10)  NOT NULL COMMENT '법정동코드 (시도 2자리 / 시군구 5자리)',
    name_ko    VARCHAR(50)  NOT NULL,
    name_en    VARCHAR(100) NOT NULL COMMENT '지역명은 로마자 표기가 국제 통용이라 번역 테이블 없이 ko/en 인라인',
    is_active  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = 서비스 미개시 지역(선택 목록에서 숨김)',
    sort_order INT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_regions_code (code),
    INDEX idx_regions_parent (parent_id, sort_order),
    CONSTRAINT fk_regions_parent FOREIGN KEY (parent_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '행정구역 마스터(시도/시군구). 동 단위 수요는 rental_requests.preferred_area_note가 흡수한다';


CREATE TABLE request_options
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    parent_id      BIGINT      NULL COMMENT 'NULL = 그룹(1단). 값 있음 = 항목(2단). 깊이 2 고정 — 3단은 서비스가 거부',
    code           VARCHAR(40) NOT NULL COMMENT 'ROOM_TYPE / ONE_ROOM / AIR_CONDITIONER … 기계 축',
    selection_type VARCHAR(10) NULL COMMENT '그룹 행에만 값: SINGLE | MULTI. 항목 행은 NULL',
    icon_key       VARCHAR(40) NULL COMMENT 'FE 아이콘 키 — 항목 추가 시 앱 배포를 피하는 축',
    sort_order     INT         NOT NULL DEFAULT 0,
    active         TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 = 신규 요청에서 숨김(기존 요청의 선택은 유지 표시)',
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ro_code (code),
    INDEX idx_ro_parent (parent_id, sort_order) COMMENT '요청 화면 렌더링 축',
    CONSTRAINT fk_ro_parent FOREIGN KEY (parent_id) REFERENCES request_options (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '임대 요청 선택 항목 마스터(2단 트리). 항목 추가 = 행 추가가 설계 전제';


CREATE TABLE request_option_translations
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    request_option_id BIGINT       NOT NULL,
    language_code     VARCHAR(10)  NOT NULL,
    name              VARCHAR(100) NOT NULL COMMENT '"원룸" / "Studio"',
    PRIMARY KEY (id),
    UNIQUE KEY uq_rot_pair (request_option_id, language_code),
    CONSTRAINT fk_rot_option   FOREIGN KEY (request_option_id) REFERENCES request_options (id) ON DELETE CASCADE,
    CONSTRAINT fk_rot_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE rental_requests
(
    id                  BIGINT                                                            NOT NULL AUTO_INCREMENT,
    uuid                CHAR(36)                                                          NOT NULL,
    request_number      VARCHAR(20)                                                       NOT NULL COMMENT '업무번호 RQ-yyMMdd-RAND6 — 담당자가 전화·메신저로 부르는 이름',
    user_id             BIGINT                                                            NOT NULL,
    verification_id     BIGINT                                                            NOT NULL COMMENT '게이트 통과 근거. 이후 인증이 만료돼도 이력은 근거를 잃지 않는다',
    assigned_manager_id BIGINT                                                            NULL COMMENT '담당자. NULL = 미배정. v1은 전체 담당자가 모든 요청 접근',

    status              ENUM ('SUBMITTED','IN_PROGRESS','CONTRACTED','CANCELED','CLOSED') NOT NULL DEFAULT 'SUBMITTED',

    move_in_date        DATE                                                              NOT NULL COMMENT '입주 희망일',
    move_in_flexible    TINYINT(1)                                                        NOT NULL DEFAULT 0 COMMENT '1 = 날짜 조정 가능(매칭 폭이 크게 달라지는 축)',
    lease_months        SMALLINT                                                          NULL COMMENT '희망 계약 기간(개월). NULL = 미정',
    deposit_min         BIGINT                                                            NULL COMMENT '보증금 하한(원). NULL = 하한 없음',
    deposit_max         BIGINT                                                            NULL,
    monthly_rent_min    BIGINT                                                            NULL,
    monthly_rent_max    BIGINT                                                            NULL,
    household_size      TINYINT                                                           NULL COMMENT '거주 인원 — 전대차 매물 매칭의 실질 제약',

    preferred_area_note VARCHAR(255)                                                      NULL COMMENT '"건대입구역 도보 10분" — 시군구만으로는 안 잡히는 실수요',
    request_note        TEXT                                                              NULL COMMENT '유저 자유 요청사항(다국어 원문 그대로 — 번역하지 않는다)',
    manager_memo        VARCHAR(500)                                                      NULL COMMENT '내부 메모. 유저 응답에 절대 포함하지 않는다',
    closed_reason       VARCHAR(255)                                                      NULL COMMENT 'CANCELED/CLOSED 전환 사유',

    version             BIGINT                                                            NOT NULL DEFAULT 0 COMMENT '유저 수정 vs 담당자 상태 변경 동시전이 차단',
    created_at          DATETIME(6)                                                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)                                                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    closed_at           DATETIME(6)                                                       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_rr_uuid (uuid),
    UNIQUE KEY uq_rr_number (request_number),
    INDEX idx_rr_user (user_id, id) COMMENT '유저별 요청 목록(최신순)',
    INDEX idx_rr_status (status, id) COMMENT '담당자 처리 대기열',
    INDEX idx_rr_manager (assigned_manager_id, status),
    CONSTRAINT fk_rr_user         FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rr_verification FOREIGN KEY (verification_id) REFERENCES foreigner_verifications (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rr_manager      FOREIGN KEY (assigned_manager_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '임대 요청. 계약 체결 시 CONTRACTED로 전이하고 contracts가 이 행을 참조한다';

-- (미결) 유저당 진행 중 요청 1건 제한을 걸려면 아래 두 줄. PO 미확정이라 V1 미포함.
--   ALTER TABLE rental_requests
--       ADD COLUMN active_guard BIGINT GENERATED ALWAYS AS
--           (CASE WHEN status IN ('SUBMITTED','IN_PROGRESS') THEN user_id END) VIRTUAL,
--       ADD UNIQUE KEY uq_rr_active_per_user (active_guard);


CREATE TABLE rental_request_regions
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    rental_request_id BIGINT      NOT NULL,
    region_id         BIGINT      NOT NULL COMMENT '시군구 행만 참조(시도 행 선택은 서비스가 거부)',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rrr_pair (rental_request_id, region_id),
    INDEX idx_rrr_region (region_id) COMMENT '지역별 수요 집계(어디에 매물을 잡아야 하는지)',
    CONSTRAINT fk_rrr_request FOREIGN KEY (rental_request_id) REFERENCES rental_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_rrr_region  FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '희망 지역(다중). CASCADE인 이유: 요청 수정 시 통째로 갈아끼우는 값이라 독립 수명이 없다';


CREATE TABLE rental_request_options
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    rental_request_id BIGINT      NOT NULL,
    request_option_id BIGINT      NOT NULL COMMENT '항목 행만 참조(그룹 행 선택은 서비스가 거부)',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rro_pair (rental_request_id, request_option_id),
    INDEX idx_rro_option (request_option_id) COMMENT '옵션별 수요 집계',
    CONSTRAINT fk_rro_request FOREIGN KEY (rental_request_id) REFERENCES rental_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_rro_option  FOREIGN KEY (request_option_id) REFERENCES request_options (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '선택 옵션(다중)';


-- =============================================================================
-- D4. 계약
--
-- verification_id는 NOT NULL(명의는 인증에서 나온다), rental_request_id는 NULL 허용
-- (유입 경로일 뿐 — 워크인 때문에 가짜 요청을 만들게 하지 않는다).
-- DRAFT → ACTIVE 전이가 월세 회차 전량 생성 + 임대 요청 CONTRACTED 전이의 트리거다.
-- =============================================================================

CREATE TABLE banks
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    bank_code    VARCHAR(10) NOT NULL COMMENT '금융결제원 기관코드 — 자연키',
    name_ko      VARCHAR(50) NOT NULL COMMENT '관리자·담당자 화면 전용 표기',
    name_en      VARCHAR(50) NOT NULL COMMENT '유저 화면 전용 표기',
    icon_file_id BIGINT      NULL COMMENT '은행 아이콘(files.purpose = BANK_ICON). 비민감이라 공개 URL 사용',
    is_active    TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '합병·폐업 시 숨김(행은 영구 보존 — 과거 계약이 코드를 물고 있다)',
    sort_order   INT         NOT NULL DEFAULT 0,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_banks_code (bank_code),
    INDEX idx_banks_active (is_active, sort_order),
    CONSTRAINT fk_banks_icon FOREIGN KEY (icon_file_id) REFERENCES files (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '은행 마스터. 이름이 2개인 이유: 유저(외국인)는 영문, 운영자는 국문으로 봐야 서로 일이 된다. 번역 테이블이 아닌 이유: 언어가 늘어도 은행명은 ko/en 이상으로 늘지 않는다';


CREATE TABLE contracts
(
    id                       BIGINT                                                         NOT NULL AUTO_INCREMENT,
    uuid                     CHAR(36)                                                       NOT NULL,
    contract_number          VARCHAR(20)                                                    NOT NULL COMMENT '업무번호 CT-yyMMdd-RAND6',

    tenant_user_id           BIGINT                                                         NOT NULL,
    verification_id          BIGINT                                                         NOT NULL COMMENT '계약 명의의 출처. 신원 확인 없는 임대차는 성립하지 않으므로 NOT NULL',
    rental_request_id        BIGINT                                                         NULL COMMENT '유입 경로. NULL = 워크인·소개',
    registered_by            BIGINT                                                         NOT NULL COMMENT '등록 담당자(role MANAGER 이상). 담당자 귀속 축',
    renewed_from_contract_id BIGINT                                                         NULL COMMENT '연장 계보. 연장 = 새 계약 행 + 이전 계약 COMPLETED',

    status                   ENUM ('DRAFT','ACTIVE','TERMINATING','TERMINATED','COMPLETED') NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT = 작성 중(유저 비노출·대장 미생성) / TERMINATING = 해지 승인·종료일 도래 전 / COMPLETED = 만기 종료',

    tenant_name              VARCHAR(100)                                                   NOT NULL COMMENT '계약 명의(여권 영문명). foreigner_verifications.full_name 복사 스냅샷',
    road_address             VARCHAR(255)                                                   NOT NULL,
    detail_address           VARCHAR(255)                                                   NULL COMMENT '동·호수',
    region_id                BIGINT                                                         NULL COMMENT '시군구. 수요 통계에서 rental_request_regions와 짝을 이룬다',

    monthly_rent             BIGINT                                                         NOT NULL COMMENT '월세(원). 보증금·관리비 없음 — 관리비는 후속 ALTER로 무파괴 추가 가능',

    payment_day              TINYINT                                                        NOT NULL COMMENT '납부일 1~31. 해당 월에 없는 날은 말일로 클램프(매달 원본 값에서 재계산)',
    start_date               DATE                                                           NOT NULL,
    end_date                 DATE                                                           NOT NULL COMMENT '해지 승인 시 effective_date로 앞당긴다 — 대장 회차의 상한이므로 정본이다',

    bank_code                VARCHAR(10)                                                    NOT NULL,
    bank_name_ko             VARCHAR(50)                                                    NOT NULL COMMENT '스냅샷(은행이 합병돼도 계약서 표기는 불변)',
    bank_name_en             VARCHAR(50)                                                    NOT NULL COMMENT '스냅샷 — 유저 화면은 이 값을 쓴다',
    account_number           VARCHAR(50)                                                    NOT NULL COMMENT '우리 수취 계좌라 평문 보관(계약마다 다름)',
    account_holder           VARCHAR(50)                                                    NOT NULL COMMENT '예금주명 — 유저가 이체 전 대조하는 값',

    special_terms            TEXT                                                           NULL COMMENT '특약사항(계약서 원문 발췌). 번역하지 않는다',
    manager_memo             VARCHAR(500)                                                   NULL COMMENT '내부 메모. 유저 응답에 절대 포함하지 않는다',
    closed_reason            VARCHAR(255)                                                   NULL COMMENT '종료 사유 스냅샷(만기·중도해지 공통). 해지 상세는 contract_terminations가 소유',

    version                  BIGINT                                                         NOT NULL DEFAULT 0,
    created_at               DATETIME(6)                                                    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)                                                    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    activated_at             DATETIME(6)                                                    NULL COMMENT 'ACTIVE 전이 시각 = 대장이 만들어진 시점',
    closed_at                DATETIME(6)                                                    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_contracts_uuid (uuid),
    UNIQUE KEY uq_contracts_number (contract_number),
    UNIQUE KEY uq_contracts_request (rental_request_id) COMMENT '한 요청에서 계약 2건이 나올 수 없다. NULL 중복은 허용되므로 워크인은 무제한',
    INDEX idx_contracts_tenant (tenant_user_id, status) COMMENT '내 계약 조회(홈 월세 카드 진입 축)',
    INDEX idx_contracts_expiry (status, end_date) COMMENT '만기 자동 종료 배치 + TERMINATING 도래 배치의 공용 스캔 축',
    INDEX idx_contracts_manager (registered_by, id),
    INDEX idx_contracts_region (region_id),
    CONSTRAINT fk_contracts_tenant       FOREIGN KEY (tenant_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_contracts_verification FOREIGN KEY (verification_id) REFERENCES foreigner_verifications (id) ON DELETE RESTRICT,
    CONSTRAINT fk_contracts_request      FOREIGN KEY (rental_request_id) REFERENCES rental_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_contracts_manager      FOREIGN KEY (registered_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_contracts_renewed_from FOREIGN KEY (renewed_from_contract_id) REFERENCES contracts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_contracts_region       FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '임대차 계약. soft delete 없음 — 계약은 법적 사실이라 삭제 개념이 없다(오등록은 TERMINATED + 사유)';


-- 유저 발의와 담당자 직권을 한 테이블로 모은다. 경로를 나누면
-- "담당자가 그냥 해지한 계약"의 사유와 시점이 어디에도 남지 않는다.
CREATE TABLE contract_terminations
(
    id               BIGINT                                            NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)                                          NOT NULL,
    contract_id      BIGINT                                            NOT NULL,
    request_type     ENUM ('TENANT_REQUEST','MANAGER_INITIATED')       NOT NULL
        COMMENT 'TENANT_REQUEST = PENDING으로 시작 / MANAGER_INITIATED = 생성과 동시에 APPROVED',
    requested_by     BIGINT                                            NOT NULL COMMENT '발의자(임차인 또는 담당자)',
    reason           VARCHAR(500)                                      NOT NULL COMMENT '해지 사유. 유저 발의면 유저 원문(번역하지 않는다)',
    desired_end_date DATE                                              NOT NULL COMMENT '희망 종료일(발의자 입력)',

    status           ENUM ('PENDING','APPROVED','REJECTED','CANCELED') NOT NULL DEFAULT 'PENDING'
        COMMENT 'CANCELED = 발의자가 요청 철회(PENDING에서만 가능)',
    effective_date   DATE                                              NULL COMMENT '승인 시 확정된 실제 종료일. 승인 트랜잭션이 contracts.end_date를 앞당기고 미도래 회차를 삭제한다',
    processed_by     BIGINT                                            NULL,
    processed_at     DATETIME(6)                                       NULL,
    reject_reason    VARCHAR(255)                                      NULL COMMENT 'REJECTED 시 필수 — 유저에게 그대로 노출된다',
    admin_memo       VARCHAR(500)                                      NULL COMMENT '내부 메모',

    active_guard     BIGINT GENERATED ALWAYS AS
                         (CASE WHEN status = 'PENDING' THEN contract_id END) VIRTUAL
        COMMENT '진행 중 해지 요청 1건 제한의 DB 축 — 유저 더블탭·중복 제출을 409로 만든다',

    version          BIGINT                                            NOT NULL DEFAULT 0 COMMENT '유저 철회 vs 담당자 승인 동시전이 차단',
    created_at       DATETIME(6)                                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)                                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cterm_uuid (uuid),
    UNIQUE KEY uq_cterm_active (active_guard),
    INDEX idx_cterm_contract (contract_id, id) COMMENT '계약별 해지 이력(거절 후 재요청이 쌓인다)',
    INDEX idx_cterm_status (status, id) COMMENT '담당자 처리 대기열',
    CONSTRAINT fk_cterm_contract  FOREIGN KEY (contract_id) REFERENCES contracts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cterm_requester FOREIGN KEY (requested_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cterm_processor FOREIGN KEY (processed_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '중도 해지 원장. 거절되면 계약은 그대로 살아있고 행만 이력으로 남는다 — 재요청은 새 행';


CREATE TABLE move_in_inspections
(
    id              BIGINT                      NOT NULL AUTO_INCREMENT,
    uuid            CHAR(36)                    NOT NULL,
    contract_id     BIGINT                      NOT NULL,
    inspection_type ENUM ('MOVE_IN','MOVE_OUT') NOT NULL DEFAULT 'MOVE_IN'
        COMMENT 'v1은 MOVE_IN만 운용. 퇴거 확인서는 같은 구조라 값만 추가하면 되도록 열어둔다',
    inspected_by    BIGINT                      NOT NULL COMMENT '작성 담당자',
    overall_note    TEXT                        NULL COMMENT '총평. 담당자가 쓰므로 한국어일 수 있다 — 영문 병기를 운영 가이드로 권고',
    version         BIGINT                      NOT NULL DEFAULT 0,
    created_at      DATETIME(6)                 NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '이 값이 곧 현장 점검일이다(별도 컬럼 없음)',
    updated_at      DATETIME(6)                 NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_mi_uuid (uuid),
    UNIQUE KEY uq_mi_contract_type (contract_id, inspection_type) COMMENT '계약당 유형별 1건 — 재작성이 아니라 수정이다',
    CONSTRAINT fk_mi_contract  FOREIGN KEY (contract_id) REFERENCES contracts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mi_inspector FOREIGN KEY (inspected_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '입주 상태 확인서. 담당자가 등록하면 즉시 유저에게 노출되고, 이후 수정은 ADMIN만 + 이력 필수';


CREATE TABLE move_in_inspection_photos
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    inspection_id BIGINT       NOT NULL,
    file_id       BIGINT       NOT NULL COMMENT 'files.purpose = MOVE_IN_PHOTO. 공개 URL 없음',
    area_code     VARCHAR(30)  NOT NULL COMMENT 'LIVING_ROOM|KITCHEN|BATHROOM|BEDROOM|ENTRANCE|BALCONY|APPLIANCE|ETC — 고정 목록이라 표시명은 앱 i18n 번들이 소유',
    has_damage    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = 입주 시점에 이미 있던 하자. 퇴거 정산의 방어 축',
    note          VARCHAR(255) NULL COMMENT '"싱크대 하단 도장 벗겨짐" 같은 구체 기술',
    sort_order    INT          NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_mip_file (file_id),
    INDEX idx_mip_inspection (inspection_id, area_code, sort_order),
    INDEX idx_mip_damage (inspection_id, has_damage) COMMENT '하자 목록만 추리는 조회',
    CONSTRAINT fk_mip_inspection FOREIGN KEY (inspection_id) REFERENCES move_in_inspections (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mip_file       FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '확인서 사진. has_damage가 "입주 전부터 있던 하자"를 고정한다 — 이게 이 테이블의 존재 이유';


CREATE TABLE move_in_inspection_revisions
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    inspection_id   BIGINT       NOT NULL,
    revised_by      BIGINT       NOT NULL COMMENT '수정자(role = ADMIN)',
    reason          VARCHAR(500) NOT NULL COMMENT '왜 고쳤는가(사람이 입력, 필수). 이유 없는 수정은 허용하지 않는다',
    before_snapshot JSON         NOT NULL COMMENT '수정 직전 전체 상태를 기계가 자동 캡처 { overallNote, photos:[...] }',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_mir_inspection (inspection_id, id) COMMENT '확인서별 수정 타임라인',
    CONSTRAINT fk_mir_inspection FOREIGN KEY (inspection_id) REFERENCES move_in_inspections (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mir_reviser    FOREIGN KEY (revised_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'append-only. 행이 없으면 "최초 등록 그대로"라는 뜻이고 그 자체가 증거다';


-- =============================================================================
-- D5. 월세 — 청구(rent_billings)와 수납(rent_payments) 분리
--
-- 분리 이유: (1) 의미론 — "담당자가 확인한 행위" ≠ "돈이 들어온 사실"(미납 확정도 확인
-- 행위지만 수납은 없다). (2) 카드 결제 확장 — 1 청구 : N 수납(부분 납부·재시도·취소)이
-- 필요해진다. 이 분리가 카드 결제 대비의 구조적 준비 전부이고 나머지는 전부 컬럼 추가다.
--
-- 두 수단의 차이는 "누가 PAID를 찍는가" 하나뿐이다:
--   계좌이체 → 사람(checked_by 채움) / 카드 → 시스템(checked_by NULL, PG 콜백)
-- =============================================================================

CREATE TABLE rent_billings
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    uuid           CHAR(36)     NOT NULL,
    contract_id    BIGINT       NOT NULL,

    billing_cycle  CHAR(7)      NOT NULL COMMENT '논리 회차 YYYY-MM(due_date 기준 월). 푸시 멱등 키의 재료',
    due_date       DATE         NOT NULL COMMENT '실제 납부일(말일 클램프 적용). 정렬·"오늘 이후 미노출" 필터의 축',
    installment_no SMALLINT     NOT NULL COMMENT '1부터. 연장 계약에서는 다시 1로 시작',
    amount         BIGINT       NOT NULL COMMENT '청구액 스냅샷. 미도래 PENDING 회차만 갱신 대상',

    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING("?") | PAID("O") | OVERDUE("X"). ENUM이 아닌 이유: 카드 도입 시 PROCESSING·FAILED가 값만 추가된다',
    paid_amount    BIGINT       NOT NULL DEFAULT 0 COMMENT '유효 수납 합계 캐시. 정본은 rent_payments — 취소가 나면 재계산',

    checked_by     BIGINT       NULL COMMENT '수기 확인 담당자. 카드 자동 확정 건은 NULL — 사람이 만졌는지가 이 컬럼으로 판별된다',
    checked_at     DATETIME(6)  NULL COMMENT '확인(또는 재확인) 시각. 되돌림 시 덮어쓴다',
    memo           VARCHAR(255) NULL COMMENT '"세입자 연락 두절" 같은 회차 단위 메모(입금 대조 메모는 rent_payments 소유)',

    version        BIGINT       NOT NULL DEFAULT 0 COMMENT '담당자 동시 체크 + PG 콜백 경합 차단',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rb_uuid (uuid),
    UNIQUE KEY uq_rb_contract_cycle (contract_id, billing_cycle) COMMENT '계약당 월 1회차. 재생성·중복 생성의 DB 방어선',
    INDEX idx_rb_contract_due (contract_id, due_date) COMMENT '유저 대장 조회의 정본 축(due_date <= CURDATE() + 최신순)',
    INDEX idx_rb_pending (status, due_date) COMMENT '담당자 미확인 대기열 + 납부일 도래 알림 배치',
    CONSTRAINT fk_rb_contract FOREIGN KEY (contract_id) REFERENCES contracts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rb_checker  FOREIGN KEY (checked_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '월세 청구서. 수단과 무관하게 "이 달에 얼마를 받아야 하는가"만 담는다';


CREATE TABLE rent_payments
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)     NOT NULL,
    billing_id   BIGINT       NOT NULL,
    contract_id  BIGINT       NOT NULL COMMENT '비정규화 — 계약별 수납 조회가 billing 조인 없이 끝난다',

    method       VARCHAR(20)  NOT NULL COMMENT 'BANK_TRANSFER | CARD(v2). 수단은 청구가 아니라 수납의 속성 — 회차마다 달라질 수 있고 청구 시점엔 미정이다',
    amount       BIGINT       NOT NULL COMMENT '월세 원금분. 카드 수수료·플랫폼 수수료는 v2에서 별도 컬럼 — 원금과 섞으면 청구액 대조가 깨진다',
    status       VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMED'
        COMMENT 'v1은 CONFIRMED만(담당자 통장 대조). v2 카드: PENDING → PAID | FAILED | CANCELED',
    paid_at      DATE         NOT NULL COMMENT '통장 입금일 또는 카드 승인일. 확인일과 다르다 — 늦게 확인해도 입금일은 실제 날짜여야 한다',

    confirmed_by BIGINT       NULL COMMENT '수기 확인 담당자. 카드 결제 건은 항상 NULL',
    confirmed_at DATETIME(6)  NULL,
    memo         VARCHAR(255) NULL COMMENT '"타인 명의 입금(홍길동)" 같은 대조 메모',

    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rp_uuid (uuid),
    INDEX idx_rp_billing (billing_id, id) COMMENT '청구별 수납 이력 + paid_amount 재계산',
    INDEX idx_rp_contract (contract_id, paid_at) COMMENT '계약별 수납 타임라인',
    CONSTRAINT fk_rp_billing   FOREIGN KEY (billing_id) REFERENCES rent_billings (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rp_contract  FOREIGN KEY (contract_id) REFERENCES contracts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rp_confirmer FOREIGN KEY (confirmed_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '월세 수납 원장. wonjoomin의 payments(1):rent_payments(1)은 이 구조의 부분집합이라 그대로 얹힌다';


-- =============================================================================
-- D6. 알림
--
-- 모든 알림은 outbox를 지난다. 이벤트 트랜잭션은 INSERT까지만 하고
-- 외부 호출(FCM)은 커밋 후 워커가 한다 — 롤백된 트랜잭션의 푸시는 되돌릴 수 없다.
-- 문구는 template_code + params로 저장한다: 유저가 언어를 바꾸면 과거 알림도 그 언어로 보인다.
-- =============================================================================

CREATE TABLE notification_outbox
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid          CHAR(36)     NOT NULL,
    user_id       BIGINT       NOT NULL COMMENT '수신자',
    noti_type     VARCHAR(50)  NOT NULL COMMENT 'RENT_DUE|RENT_OVERDUE|VERIFICATION_APPROVED|…',
    dedupe_key    VARCHAR(150) NOT NULL COMMENT '멱등 축. "RENT_DUE:contract:123:2026-08" — 타입마다 다른 키 조합을 문자열 하나로 흡수',
    template_code VARCHAR(60)  NOT NULL COMMENT '메시지 번들 키. 문구 정본은 서버 리소스가 소유',
    params        JSON         NULL COMMENT '치환 값. 숫자·날짜 포맷은 렌더 시 로케일이 결정',
    deep_link     VARCHAR(255) NULL COMMENT '앱 내 이동 경로. NULL = 알림함에만 남고 이동 없음',

    scheduled_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발송 예정 시각. 워커는 이 값이 지난 행만 집는다',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING → SENT | FAILED | SKIPPED. SKIPPED = 카테고리 OFF 또는 기기 토큰 0개(재시도 안 하되 기록은 남긴다)',
    attempt_count INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500) NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_no_uuid (uuid),
    UNIQUE KEY uq_no_dedupe (dedupe_key) COMMENT '중복 발송의 DB 방어선 — 배치 이중 실행·재기동에도 1건',
    INDEX idx_no_pickup (status, scheduled_at) COMMENT '워커 폴링 축',
    CONSTRAINT fk_no_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '알림 발송 큐';


CREATE TABLE user_notifications
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid          CHAR(36)     NOT NULL,
    user_id       BIGINT       NOT NULL,
    noti_type     VARCHAR(50)  NOT NULL,
    template_code VARCHAR(60)  NOT NULL COMMENT '렌더된 문자열이 아니라 키를 저장 — 언어 전환이 과거 알림에 소급된다',
    params        JSON         NULL,
    deep_link     VARCHAR(255) NULL,
    read_at       DATETIME(6)  NULL COMMENT 'NULL = 미읽음. 뱃지 카운트의 축',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_un_uuid (uuid),
    INDEX idx_un_user (user_id, id) COMMENT '알림함 목록(최신순)',
    INDEX idx_un_unread (user_id, read_at) COMMENT '미읽음 뱃지 카운트',
    CONSTRAINT fk_un_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '인앱 알림함. 푸시를 놓치거나 꺼둔 유저의 복구 경로 — 푸시가 유일한 전달 수단이면 안 된다';


CREATE TABLE push_notifications
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    outbox_id     BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(255) NOT NULL COMMENT '실제 FCM에 보낸 문자열 스냅샷. 알림함과 달리 여기는 "무엇을 보냈나"의 사실이다',
    body          VARCHAR(500) NOT NULL,
    language_code VARCHAR(10)  NOT NULL COMMENT '발송 당시 유저 언어 — 문구가 이상하다는 CS의 첫 확인 지점',
    device_count  INT          NOT NULL COMMENT '대상 기기 수(발송 시점)',
    success_count INT          NOT NULL COMMENT 'FCM 성공 수. device_count와 다르면 만료 토큰 정리 대상',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_pn_user (user_id, created_at),
    INDEX idx_pn_outbox (outbox_id),
    CONSTRAINT fk_pn_outbox FOREIGN KEY (outbox_id) REFERENCES notification_outbox (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pn_user   FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'FCM 발송 이력. 기기별 결과가 아니라 논리 알림당 1행 요약';


CREATE TABLE user_notification_settings
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    category   VARCHAR(30) NOT NULL COMMENT 'COMMUNITY | MARKETING. CONTRACT(월세·계약·인증)는 서비스 핵심이라 토글 대상이 아니다 — 행 자체를 만들지 않는다',
    enabled    TINYINT(1)  NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_uns_pair (user_id, category),
    CONSTRAINT fk_uns_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '알림 수신 설정. 행이 없으면 기본 ON — 가입 시 행을 만들지 않아도 동작한다';


-- =============================================================================
-- D7. 커뮤니티
--
-- ★ v1은 전면 익명이다. 익명은 전송 계층의 문제이지 저장의 문제가 아니다 —
--   posts/comments의 user_id FK는 그대로 유지하고(권한 판정·내 글 조회·차단·신고 처리에 필요)
--   응답 DTO에서만 제외한다. 나가는 것은 anonymousNo · isAuthor · isMine 셋뿐.
--
-- 신고·차단은 App Store·Google Play의 UGC 필수 요건이라 v1에 포함한다.
-- 조회수는 반드시 Redis 버퍼 + 배치 delta 가산으로 올린다(인기 글 한 행에 쓰기 락이 몰린다).
-- =============================================================================

CREATE TABLE community_categories
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)    NOT NULL,
    code         VARCHAR(40) NOT NULL COMMENT 'HOUSING|VISA|LIFE|JOB|FREE … 기계 축. 표시명은 translations',
    is_anonymous TINYINT(1)  NOT NULL DEFAULT 1
        COMMENT '1 = 익명 게시판(작성자 정보 미전송 + 별칭 부여) / 0 = 닉네임·프로필 노출. 익명·비익명 공존의 축. v1은 전부 1. ★ 글이 하나라도 있으면 변경 금지(서비스 가드) — 익명→비익명 전환은 과거 익명 글의 작성자를 소급 노출시키는 사고다',
    sort_order   INT         NOT NULL DEFAULT 0,
    active       TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 = 목록·글쓰기에서 숨김(기존 글은 유지 노출). soft delete 없음 — 재생성 시 유니크 충돌 함정 회피',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cc_uuid (uuid),
    UNIQUE KEY uq_cc_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '커뮤니티 카테고리. 시드 0건이면 글쓰기 불가 — 시드가 운영 선행 조건이다';


CREATE TABLE community_category_translations
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    category_id   BIGINT      NOT NULL,
    language_code VARCHAR(10) NOT NULL,
    name          VARCHAR(50) NOT NULL COMMENT '"집 구하기" / "Housing"',
    PRIMARY KEY (id),
    UNIQUE KEY uq_cct_pair (category_id, language_code),
    CONSTRAINT fk_cct_category FOREIGN KEY (category_id) REFERENCES community_categories (id) ON DELETE CASCADE,
    CONSTRAINT fk_cct_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE community_tags
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    name            VARCHAR(30) NOT NULL COMMENT '표시 원문(# 제외 저장). 최초 등록자의 표기를 유지',
    name_normalized VARCHAR(30) NOT NULL COMMENT '소문자 + 공백 제거. 유니크는 반드시 이 컬럼에 — "Visa"·"visa"·"VISA"가 한 태그여야 한다',
    usage_count     BIGINT      NOT NULL DEFAULT 0 COMMENT '인기 태그 집계 캐시. 글 작성·삭제 트랜잭션에서 증감',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ctag_normalized (name_normalized),
    INDEX idx_ctag_popular (usage_count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '해시태그 마스터. 관리자가 아니라 사용자 글쓰기가 행을 만든다 — 금칙어 검사는 등록 시점에';


CREATE TABLE community_posts
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid          CHAR(36)     NOT NULL,
    category_id   BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL COMMENT '★ 익명 게시판이어도 유지한다 — 권한 판정·내 글 조회·차단·신고 처리의 축. 응답 DTO에서만 뺀다',
    language_code VARCHAR(10)  NOT NULL COMMENT '작성 시점 유저 언어 스냅샷. "내 언어 글만 보기" 필터의 축 — 다국어 목록이 섞이면 절반이 읽을 수 없는 글이 된다',

    title         VARCHAR(150) NOT NULL,
    body          TEXT         NOT NULL COMMENT '자유 텍스트(줄바꿈 보존, 5000자 서비스 가드)',

    view_count    BIGINT       NOT NULL DEFAULT 0 COMMENT 'Redis 버퍼 집계분을 배치가 delta 가산 — 매 조회 UPDATE 금지. 최대 1분 지연 허용',
    like_count    INT          NOT NULL DEFAULT 0 COMMENT '캐시. 정본은 community_post_likes',
    comment_count INT          NOT NULL DEFAULT 0 COMMENT '캐시. 갱신 경로가 작성·삭제·블라인드뿐이라 트랜잭션 증감으로 충분',

    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | HIDDEN(관리자 블라인드). 작성자 삭제는 deleted_at',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cp_uuid (uuid),
    INDEX idx_cp_list (deleted_at, status, category_id, id) COMMENT '목록 기본 축(카테고리별 최신순 — id가 생성순이라 정렬 타이브레이커 겸용)',
    INDEX idx_cp_user (user_id, id) COMMENT 'FK 지지 + 내 글 + 차단 필터의 작성자 축',
    INDEX idx_cp_language (language_code, deleted_at, status, id) COMMENT '언어 필터 목록',
    CONSTRAINT fk_cp_category FOREIGN KEY (category_id) REFERENCES community_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cp_user     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cp_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '커뮤니티 글';


-- 글 단위 익명 별칭 (에브리타임 방식). 저장하는 이유는 페이지네이션 —
-- 조회마다 계산하면 2페이지의 "익명3"이 1페이지의 "익명3"과 달라진다.
CREATE TABLE community_post_aliases
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    post_id      BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL COMMENT '서버 내부 전용. 어떤 응답에도 나가지 않는다',
    anonymous_no SMALLINT    NOT NULL COMMENT '0 = 글 작성자(글 생성 시 미리 발급). 1부터 = 댓글 참여자 등장 순',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpa_user (post_id, user_id) COMMENT '한 글에서 한 사람은 항상 같은 번호',
    UNIQUE KEY uq_cpa_no (post_id, anonymous_no) COMMENT '동시 첫 댓글 경합을 DB가 막는다 — 충돌 시 번호 재발급 후 재시도',
    CONSTRAINT fk_cpa_post FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cpa_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '글 단위 익명 별칭. 익명 카테고리(is_anonymous = 1)에서만 발급된다';


CREATE TABLE community_post_images
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    post_id    BIGINT       NOT NULL,
    image_url  VARCHAR(500) NOT NULL COMMENT '비민감 콘텐츠라 URL 참조. 수명은 files 정리 배치가 URL 기준으로 관리',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT 'sort_order = 0 이 목록 썸네일 — JSON 배열이면 이 조회가 SQL로 안 된다',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_cpi_post (post_id, sort_order),
    CONSTRAINT fk_cpi_post FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '글 첨부 이미지(≤5장 서비스 가드). CASCADE인 이유: 글과 독립된 수명이 없다';


CREATE TABLE community_post_tags
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL,
    tag_id     BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpt_pair (post_id, tag_id),
    INDEX idx_cpt_tag (tag_id, post_id) COMMENT '태그 클릭 → 글 목록 조회 축(JSON으로는 불가능했던 것)',
    CONSTRAINT fk_cpt_post FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cpt_tag  FOREIGN KEY (tag_id) REFERENCES community_tags (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '글–태그 매핑(글당 ≤10개 서비스 가드)';


CREATE TABLE community_comments
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    uuid              CHAR(36)      NOT NULL,
    post_id           BIGINT        NOT NULL,
    parent_comment_id BIGINT        NULL COMMENT 'NULL = 최상위 댓글. 깊이 2 고정 — 부모가 이미 자식이면 서비스가 거부',
    user_id           BIGINT        NOT NULL COMMENT '★ 익명이어도 유지 — 응답 DTO에서만 뺀다',
    anonymous_no      SMALLINT      NULL COMMENT '작성 시점 별칭 번호(community_post_aliases 복사 — 목록에서 조인 회피). 0 = 글쓴이. NULL = 비익명 카테고리라 별칭이 없음',
    body              VARCHAR(1000) NOT NULL,
    like_count        INT           NOT NULL DEFAULT 0 COMMENT '캐시. 정본은 community_comment_likes',
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | HIDDEN(관리자 블라인드)',
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at        DATETIME(6)   NULL COMMENT '자식 대댓글이 있으면 목록에서 "삭제된 댓글" 자리를 남긴다(스레드가 끊기면 대화가 안 읽힌다)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_cm_uuid (uuid),
    INDEX idx_cm_thread (post_id, parent_comment_id, id) COMMENT '스레드 정렬 축 — 최상위 작성순, 그 아래 자식 작성순',
    INDEX idx_cm_user (user_id, id) COMMENT 'FK 지지 + 차단 필터 축',
    CONSTRAINT fk_cm_post   FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cm_parent FOREIGN KEY (parent_comment_id) REFERENCES community_comments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cm_user   FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '댓글(깊이 2). comment_count의 정본 — 캐시가 틀어지면 재계산으로 복구';


CREATE TABLE community_post_likes
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpl_pair (post_id, user_id) COMMENT '멱등의 DB 축 — 동시 더블탭도 1행(취소는 하드 delete, 좋아요는 이력이 아니다)',
    INDEX idx_cpl_user (user_id, id) COMMENT '내가 좋아요한 글',
    CONSTRAINT fk_cpl_post FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cpl_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE community_comment_likes
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    comment_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ccl_pair (comment_id, user_id),
    INDEX idx_ccl_user (user_id, id),
    CONSTRAINT fk_ccl_comment FOREIGN KEY (comment_id) REFERENCES community_comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_ccl_user    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- 다형 참조 대신 nullable FK 2개(정확히 하나만 채움 — 서비스 가드): FK 정합이 유지되고
-- 대상별 중복 신고 유니크가 자연스럽다(nullable 컬럼의 유니크는 NULL 중복을 허용하므로 안전).
CREATE TABLE community_reports
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    uuid        CHAR(36)     NOT NULL,
    reporter_id BIGINT       NOT NULL,
    post_id     BIGINT       NULL COMMENT '글 신고면 채움. comment_id와 배타',
    comment_id  BIGINT       NULL COMMENT '댓글 신고면 채움',
    reason_code VARCHAR(30)  NOT NULL COMMENT 'SPAM|ABUSE|SEXUAL|ILLEGAL|PRIVACY|SCAM|ETC — SCAM은 부동산 사기 글 대응',
    detail      VARCHAR(500) NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING → RESOLVED(블라인드 집행) | DISMISSED(기각). 처리 후 불변',
    resolved_by BIGINT       NULL,
    resolved_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_crep_uuid (uuid),
    UNIQUE KEY uq_crep_reporter_post (reporter_id, post_id) COMMENT '같은 글 중복 신고 차단(409의 DB 축)',
    UNIQUE KEY uq_crep_reporter_comment (reporter_id, comment_id),
    INDEX idx_crep_status (status, id) COMMENT '관리자 처리 대기열',
    CONSTRAINT fk_crep_reporter FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_crep_post     FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_crep_comment  FOREIGN KEY (comment_id) REFERENCES community_comments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_crep_resolver FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '신고 접수 원장. 처리(블라인드)는 대상의 status가 담당 — 신고는 이력, 노출 판정은 대상 소유';


-- 익명이어도 차단은 성립한다: 클라이언트가 계정이 아니라 글·댓글을 지목하고
-- (POST /community/posts/{uuid}/block-author) 서버가 내부에서 user_id로 변환한다.
CREATE TABLE user_blocks
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    blocker_id      BIGINT      NOT NULL,
    blocked_user_id BIGINT      NOT NULL COMMENT '자기 자신 차단은 서비스 가드(400)',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ub_pair (blocker_id, blocked_user_id) COMMENT 'PUT 멱등의 DB 축',
    CONSTRAINT fk_ub_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ub_blocked FOREIGN KEY (blocked_user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '사용자 차단. 하드 delete(해제는 이력이 아니다). 효과는 목록 쿼리 필터 — 단방향: 차단자에게만 숨긴다';


CREATE TABLE banned_words
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    word       VARCHAR(50) NOT NULL COMMENT '소문자 정규화 저장 — 검사도 normalized 값에 contains로 수행',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bw_word (word)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '금칙어(운영자 관리). 커스텀 닉네임 + 태그 등록 시점 검사용. 자동 생성 닉네임의 위생은 빌드 타임 blocklist + CI 조합 테스트가 담당';


-- =============================================================================
-- 인프라 — 스케줄러 분산락
-- 만기 종료 배치 · TERMINATING 도래 배치 · 월세 알림 배치 · 조회수 flush ·
-- outbox 워커가 다중 인스턴스에서 중복 실행되면 안 된다.
-- =============================================================================

CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
