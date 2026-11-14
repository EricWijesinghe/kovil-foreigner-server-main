-- 매물 도메인. 외부 공인중개사가 광고를 올리고 유학생이 보고 중개사에게 직접 문의한다.
--
-- ★ 이 도메인은 **전대차와 다른 사업**이다. 전대차는 우리가 계약 당사자이고 DB에 두지 않지만
--   (오프라인 관리), 매물은 남의 물건을 광고하는 것이라 「공인중개사법」 표시·광고 의무가 붙는다.
--   그래서 사무소 정보가 매물에 반드시 따라붙어야 한다.

CREATE TABLE brokerage_offices
(
    id              BIGINT                                         NOT NULL AUTO_INCREMENT,
    uuid            CHAR(36)                                       NOT NULL COMMENT '외부 노출 식별자',

    reg_no          VARCHAR(20)                                    NOT NULL COMMENT '중개등록번호 지자체5-연도4-일련5. 사무소의 정체성 — 상호·주소·전화는 바뀌어도 이건 안 바뀐다',
    name            VARCHAR(100)                                   NOT NULL COMMENT '상호. 광고 법정 표시 항목',
    ceo_name        VARCHAR(50)                                    NOT NULL COMMENT '개업공인중개사 성명. 광고 법정 표시 항목',
    address         VARCHAR(255)                                   NOT NULL COMMENT '소재지. 광고 법정 표시 항목',
    tel             VARCHAR(30)                                    NOT NULL COMMENT '공시 연락처. 광고 법정 표시 항목 — 중개사가 고치지 못한다',
    biz_no          VARCHAR(12)                                    NULL COMMENT '사업자등록번호. 국세청 진위확인 대조 축',

    registry_status VARCHAR(20)                                    NULL COMMENT '레지스트리 영업상태 원문',
    verified_at     DATETIME(6)                                    NULL COMMENT '대조를 통과한 시각. 없으면 "그때는 영업중이었다"를 증명할 수 없다',

    -- ★ 이 값이 매물 노출을 지배한다. 매물마다 상태를 따라 내리는 배치가 아니라
    --   조회가 조인으로 거른다 — 배치는 잊히지만 조인은 안 잊힌다.
    status          ENUM ('PENDING','ACTIVE','SUSPENDED','CLOSED') NOT NULL DEFAULT 'PENDING',

    created_at      DATETIME(6)                                    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)                                    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bo_uuid (uuid),
    UNIQUE KEY uq_bo_reg_no (reg_no),
    INDEX idx_bo_status (status, id) COMMENT '승인 대기열'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '중개사무소. 광고의 법정 표시 주체 — 매물은 사람이 아니라 여기를 가리킨다';


CREATE TABLE broker_profiles
(
    id             BIGINT                   NOT NULL AUTO_INCREMENT,
    user_id        BIGINT                   NOT NULL,
    office_id      BIGINT                   NOT NULL,

    -- v1 은 대표자만 가입한다. 광고에 중개보조원을 명시할 수 없어, 대표자로 좁히면 그 문제가 사라진다.
    role_in_office ENUM ('CEO','ASSOCIATE') NOT NULL DEFAULT 'CEO',

    created_at     DATETIME(6)              NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)              NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bp_user (user_id) COMMENT '한 계정은 한 사무소에만',
    INDEX idx_bp_office (office_id, id),
    CONSTRAINT fk_bp_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bp_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '중개사 계정과 사무소의 연결';


CREATE TABLE listings
(
    id                     BIGINT                                                              NOT NULL AUTO_INCREMENT,
    uuid                   CHAR(36)                                                            NOT NULL,
    listing_no             VARCHAR(20)                                                         NOT NULL COMMENT '업무번호 LS-yyMMdd-RAND6. 문자 문의 본문에 박히는 번호',

    -- ★ 표시 주체는 사무소다. 올린 사람이 퇴사해도 광고의 법정 책임은 사무소에 남는다.
    office_id              BIGINT                                                              NOT NULL,
    created_by_user_id     BIGINT                                                              NOT NULL COMMENT '올린 사람. 표시 주체가 아니라 귀속·감사 축',

    category               ENUM ('ONE_TWO_ROOM','HOUSE_VILLA','OFFICETEL','APT')               NOT NULL COMMENT '탭. 필터 축이 탭마다 다르다',
    room_type              ENUM ('ONE_ROOM','TWO_ROOM','THREE_ROOM','OFFICETEL','APT')         NOT NULL,

    -- ★ '원룸(분리형)' '오피스텔(분리형,복층)' 의 괄호는 별도 값이 아니라 이 둘을 조립한 것이다.
    --   방종류를 30가지로 만들지 말 것 — 5 × 2 × 2 다.
    is_division            TINYINT(1)                                                          NOT NULL DEFAULT 0 COMMENT '분리형',
    is_duplex              TINYINT(1)                                                          NOT NULL DEFAULT 0 COMMENT '복층',

    title                  VARCHAR(100)                                                        NOT NULL,
    description            TEXT                                                                NULL COMMENT '중개사 자유 입력. 한국어다 — 번역본은 아래 번역 캐시 테이블이 들고 있다',
    -- ★ 번역 캐시의 무효화 축. description 이 바뀔 때만 올린다 —
    --   제목이나 가격을 고쳤는데 같이 올리면 멀쩡한 번역이 통째로 날아간다.
    --   ★★ 올리는 것을 잊으면 낡은 번역이 영원히 나간다. 조용한 버그라 테스트로 박는다.
    description_version    INT                                                                 NOT NULL DEFAULT 1,

    -- 전세는 전세금만, 월세는 보증금+월세. 목록이 가장 뜨거운 쿼리이고 가격이 반드시 들어가므로
    -- 테이블로 빼지 않는다. 실측한 다방 매물 7건 모두 가격 조합이 하나뿐이었다.
    selling_type           ENUM ('MONTHLY_RENT','LEASE')                                       NOT NULL COMMENT '매매(SELL)는 범위 밖 — 추가할 때 반드시 맨 뒤에',
    deposit                BIGINT                                                              NOT NULL COMMENT '월세면 보증금, 전세면 전세금. 원 단위',
    monthly_rent           BIGINT                                                              NOT NULL DEFAULT 0 COMMENT '전세는 0',

    -- 면적 셋. NULL 이면 화면에서 생략한다 — '전용/계약' '전용/공급' '전용만' 세 모양이 여기서 나온다.
    -- 검증은 애플리케이션: 전용 ≤ 공급 ≤ 계약. 다방은 안 막아서 전용 23m² / 공급 316.8m² 가 떠 있다.
    exclusive_area         DECIMAL(10, 2)                                                      NOT NULL COMMENT '전용면적',
    supply_area            DECIMAL(10, 2)                                                      NULL COMMENT '공급면적 — 주택·빌라 계열',
    contract_area          DECIMAL(10, 2)                                                      NULL COMMENT '계약면적 — 오피스텔·아파트 계열',

    -- ★ 반지하·옥탑은 층수로 표현이 안 된다. 필터의 실제 축이 이것이다.
    --   지상 1층 / 2층 이상은 floor 로 계산되므로 따로 저장하지 않는다.
    floor_type             ENUM ('GROUND','SEMI_BASEMENT','ROOFTOP')                           NOT NULL DEFAULT 'GROUND',
    -- 저장은 한다 — 필터가 정확하려면 서버는 알아야 한다.
    floor                  INT                                                                 NULL,
    -- ★★ 값이 있으면 화면에 '중층'으로 찍고 **응답에서 floor 를 뺀다.**
    --     중개사가 숨기기로 한 것을 우리가 흘리면 안 된다. DB 가 못 막으므로 테스트로 박는다.
    floor_band             ENUM ('LOW','MID','HIGH')                                           NULL,
    building_floor         INT                                                                 NULL COMMENT '건물 총층',
    basement_floor         INT                                                                 NULL,

    direction              ENUM ('N','NE','E','SE','S','SW','W','NW')                          NULL,
    -- 자유 문자열로 두면 '안방'이 한국어 그대로 외국인 화면에 나간다. 기준은 유한하므로 코드로 둔다.
    direction_base         ENUM ('MAIN_ROOM','LIVING_ROOM','MAIN_ENTRANCE')                    NULL COMMENT '방향 측정 기준. 기준이 없으면 방향은 주장일 뿐이다',
    heating                ENUM ('INDIVIDUAL','CENTRAL','DISTRICT')                            NULL,
    entrance_type          ENUM ('STAIR','CORRIDOR','COMPLEX')                                 NULL,

    can_parking            TINYINT(1)                                                          NOT NULL DEFAULT 0,
    parking_num            INT                                                                 NULL,
    has_elevator           TINYINT(1)                                                          NULL,
    household_num          INT                                                                 NULL,
    building_approval_date DATE                                                                NULL COMMENT '사용승인일. 필터 축이다',

    move_in_immediate      TINYINT(1)                                                          NOT NULL DEFAULT 0 COMMENT '즉시 입주는 날짜로 못 적는다',
    move_in_date           DATE                                                                NULL,

    -- ★ 외국인 입주 가능 여부는 **플랫폼의 전제**라 컬럼으로 두지 않는다(PO 확정).
    --   대신 아래 둘은 매물마다 실제로 다르다. 전입신고가 막히면 외국인등록증 주소지를 올릴 수
    --   없어 체류 행정이 멈춘다 — 유학생에게는 "외국인 가능"보다 이쪽이 치명적이다.
    resident_registration  ENUM ('POSSIBLE','IMPOSSIBLE','ASK')                                NOT NULL DEFAULT 'ASK' COMMENT '전입신고',
    deposit_insurance      ENUM ('POSSIBLE','IMPOSSIBLE','ASK')                                NOT NULL DEFAULT 'ASK' COMMENT '보증보험',
    is_short_lease         TINYINT(1)                                                          NOT NULL DEFAULT 0 COMMENT '단기 임대. 학기 단위로 움직이는 유학생의 주된 축',

    -- ★★ 좌표를 둘 둔다. 목록·지도 응답에는 display 만 싣는다.
    --     실좌표를 목록에 실으면 전수 수집으로 임대인 주소가 통째로 나간다.
    real_lat               DECIMAL(10, 7)                                                      NOT NULL,
    real_lng               DECIMAL(10, 7)                                                      NOT NULL,
    display_lat            DECIMAL(10, 7)                                                      NOT NULL COMMENT '난수 오프셋 적용. 바깥에 나가는 좌표',
    display_lng            DECIMAL(10, 7)                                                      NOT NULL,

    -- ★ regions 는 2단(시도/시군구)까지다. 동을 regions 에 넣으면
    --   findByIsActiveTrueOrderBySortOrderAscCodeAsc 가 활성 지역을 평면 반환하므로
    --   임대 요청 지역 선택 화면에 시도·시군구·동이 섞여 나온다. 그래서 동은 매물이 직접 들고 간다.
    region_id              BIGINT                                                              NULL COMMENT '시군구. 비어 있으면 게시할 수 없다(게시 관문이 요구) — 초안·내린 매물은 비어 있을 수 있다',
    dong_name              VARCHAR(50)                                                         NULL COMMENT '법정동 이름',
    address_jibun          VARCHAR(255)                                                        NOT NULL,
    address_road           VARCHAR(255)                                                        NULL,
    show_detail_address    TINYINT(1)                                                          NOT NULL DEFAULT 0 COMMENT '0 이면 동까지만 공개',

    -- ★ 사무소 공시 번호와 다를 수 있다(안심번호·대표번호). 비면 조회가 사무소 번호로 떨어뜨린다.
    --   합치면 법이 요구하는 공시 번호를 중개사가 마음대로 고치게 된다.
    contact_sms_number     VARCHAR(30)                                                         NULL,
    contact_call_number    VARCHAR(30)                                                         NULL,

    -- 되살릴 수 있는 것이라 상태 전이로 관리한다. 다만 허위매물 분쟁의 증거라 행은 지우지 않는다.
    -- ★ BLOCKED 는 **관리자 전용**이다. HIDDEN(중개사가 내림)과 가른 이유: 허위매물 신고로
    --   내렸는데 중개사가 publish 로 되올리면 차단이 의미가 없다. 중개사는 BLOCKED 를 못 푼다.
    --
    -- ★★ EXPIRED 와 ARCHIVED 를 가른 이유: EXPIRED 는 **아직 되살릴 수 있는 7일**이고
    --     ARCHIVED 는 그 창이 닫힌 것이다. 하나로 합치면 중개사 목록에서 "급히 연장해야 할 건"과
    --     "이미 끝난 건"이 섞여, 매물이 수백 개인 사무소는 급한 것을 못 찾는다.
    --     COMPLETED 로 보내지 않는 이유: 그건 '거래 완료'라 나중에 성약률 통계가 오염된다.
    status                 ENUM ('DRAFT','ACTIVE','RESERVED','COMPLETED','EXPIRED','HIDDEN','BLOCKED','ARCHIVED') NOT NULL DEFAULT 'DRAFT',

    -- ★ 광고는 만료된다. 만료가 없으면 1년 전에 나간 방이 영원히 떠 있고, 그게 곧 허위매물이다.
    posted_at              DATETIME(6)                                                         NULL,
    expires_at             DATETIME(6)                                                         NULL,

    view_count             BIGINT                                                              NOT NULL DEFAULT 0,
    -- 문의 버튼을 누른 횟수. 중개사 번호는 그 시점에만 내려주므로 이 값이 곧 번호 노출 횟수다 —
    -- 비정상적으로 큰 값은 번호 수집 시도의 신호가 된다.
    inquiry_count          BIGINT                                                              NOT NULL DEFAULT 0,
    -- ★ 탈퇴하면 그 사람의 찜을 지우고 **이 값도 같이 줄인다**(PO 확정 2026-10-05).
    --   안 줄이면 아무도 찜하지 않은 매물의 카운터가 남는다.
    favorite_count         BIGINT                                                              NOT NULL DEFAULT 0,
    -- 낙관적 잠금. 같은 매물을 중개사 수정 · 거래완료 · 만료 배치 · 지역 닫힘 · 이탈이 동시에 쓰면
    -- 나중 쓰기가 앞 쓰기를 통째로 덮었다(가격이 되돌아가 가격 이력과 어긋난다). 늦은 쪽을 409 로 거절한다.
    version                BIGINT                                                              NOT NULL DEFAULT 0,

    created_at             DATETIME(6)                                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)                                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ls_uuid (uuid),
    UNIQUE KEY uq_ls_no (listing_no),
    INDEX idx_ls_office (office_id, status) COMMENT '"이 중개사의 다른 매물" + 사무소 정지 시 일괄 차단',
    INDEX idx_ls_map (status, display_lat, display_lng) COMMENT 'bbox 조회의 주 축',
    INDEX idx_ls_region (region_id, status, id),
    INDEX idx_ls_price (selling_type, deposit, monthly_rent) COMMENT '가격 범위 필터',
    INDEX idx_ls_expire (status, expires_at) COMMENT '만료 배치',
    CONSTRAINT fk_ls_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ls_user FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ls_region FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '매물. 표시 주체는 office_id — created_by 가 아니다';


-- 관리비. 「중개대상물의 표시·광고 명시사항 세부기준」이 요구하는 구조다.
--
-- ★ 다방은 분기마다 **다른 필드**를 쓴다(정액 세부내역 / 기타부과 / 확인불가). 그래서
--   "정액관리비인데 데이터는 기타 칸에 들어가는" 상태가 생긴다. 여기서는 1행으로 평탄화해 그 함정을 없앤다.
CREATE TABLE listing_maintenance
(
    listing_id        BIGINT                                                                      NOT NULL,

    charge_type       ENUM ('FIXED_FEE','ETC_CHARGE','UNABLE_CHECK')                              NOT NULL,

    -- 정액관리비일 때만 의미가 있다.
    -- ★ 금액에서 파생하지 말 것. 실측에서 8만원인데 ABOVE(10만 이상)인 행을 봤다 —
    --   중개사가 고르는 값이지 계산되는 값이 아니다.
    fixed_detail_type ENUM ('WITH_DETAILS','BELOW_THRESHOLD','ABOVE_THRESHOLD_NO_DETAILS')        NULL,

    total_cost        BIGINT                                                                      NULL COMMENT 'UNABLE_CHECK 이면 NULL — 화면에 "확인불가"가 찍힌다',

    -- 세부내역을 못 적는 경우의 사유. 기타부과는 E01~E06·E98·E99, 확인불가는 U01~U04·U99 다.
    -- 둘을 나누지 않는 이유: charge_type 이 이미 어느 코드 집합인지 말해 준다.
    reason_code       VARCHAR(4)                                                                  NULL,
    reason_etc_desc   VARCHAR(255)                                                                NULL COMMENT 'E99·U99 일 때만',

    -- 무엇을 기준으로 한 금액인가. 상세보기 맨 아래 한 줄이 이것이다.
    standard_type     ENUM ('LAST_MONTH','LAST_THREE_MONTH_AVG','LAST_ONE_YEAR_AVG','ETC')        NULL,
    standard_etc_desc VARCHAR(255)                                                                NULL,

    -- ★ BaseEntity 를 상속한 엔티티는 이 두 칸을 **반드시** 요구한다. 없으면 Hibernate 가
    --   INSERT 에 넣어 보내고 MySQL 이 'Unknown column' 으로 거절한다 — 등록 자체가 실패한다.
    --   H2 create-drop 테스트는 엔티티로 테이블을 만들기 때문에 이 결함을 **절대 못 잡는다**.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id),
    CONSTRAINT fk_lm_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '관리비 1:1';


CREATE TABLE listing_maintenance_items
(
    id             BIGINT                                                                   NOT NULL AUTO_INCREMENT,
    listing_id     BIGINT                                                                   NOT NULL,
    item_code      ENUM ('COMMON','ELECTRIC','WATER','GAS','HEATING','INTERNET','TV','ETC')  NOT NULL,
    -- ★ 금액이 아니라 '실비'일 수 있다. 이 플래그가 합계 옆 "+ α" 의 정체다.
    is_actual_cost TINYINT(1)                                                               NOT NULL DEFAULT 0,
    cost           BIGINT                                                                   NULL COMMENT 'is_actual_cost = 1 이면 NULL',
    etc_desc       VARCHAR(255)                                                             NULL COMMENT 'ETC 일 때만',
    -- ★ BaseEntity 를 상속한 엔티티는 이 두 칸을 **반드시** 요구한다. 없으면 Hibernate 가
    --   INSERT 에 넣어 보내고 MySQL 이 'Unknown column' 으로 거절한다 — 등록 자체가 실패한다.
    --   H2 create-drop 테스트는 엔티티로 테이블을 만들기 때문에 이 결함을 **절대 못 잡는다**.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lmi (listing_id, item_code),
    CONSTRAINT fk_lmi_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '정액관리비 비목별 금액. 비목 8개는 법정 구분이다';


CREATE TABLE listing_maintenance_includes
(
    listing_id BIGINT                                                                  NOT NULL,
    item_code  ENUM ('COMMON','ELECTRIC','WATER','GAS','HEATING','INTERNET','TV','ETC') NOT NULL,
    -- ★ BaseEntity 를 상속한 엔티티는 이 두 칸을 **반드시** 요구한다. 없으면 Hibernate 가
    --   INSERT 에 넣어 보내고 MySQL 이 'Unknown column' 으로 거절한다 — 등록 자체가 실패한다.
    --   H2 create-drop 테스트는 엔티티로 테이블을 만들기 때문에 이 결함을 **절대 못 잡는다**.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id, item_code),
    CONSTRAINT fk_lmic_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '세부내역을 못 적을 때 "무엇이 포함되는지"만 고른 것';


-- ★★ 옵션과 안전시설을 **반드시 나눈다.** 다방은 두 목록의 번호 공간이 겹치는데 뜻이 다르다 —
--    옵션 1 = 세탁기, 안전시설 1 = 현관보안. 한 테이블에 몰면 조용히 섞인다.
--
-- ★ 코드 어휘는 request_options 와 맞춘다(AIR_CONDITIONER·CCTV·ELEVATOR·PARKING…).
--   그래야 "에어컨 원하는 요청" ↔ "에어컨 있는 매물" 매칭이 조인 한 번이 된다.
--
-- ★ 마스터 테이블을 두지 않는다. 이름과 아이콘을 앱이 가지기 때문이다 — DB 에만 행을 늘리면
--   앱에 이름도 아이콘도 없는 행이 생긴다(request_options.icon_key 가 지금 그 상태다).
CREATE TABLE listing_options
(
    listing_id BIGINT      NOT NULL,
    code       VARCHAR(40) NOT NULL,
    -- ★ BaseEntity 를 상속한 엔티티는 이 두 칸을 **반드시** 요구한다. 없으면 Hibernate 가
    --   INSERT 에 넣어 보내고 MySQL 이 'Unknown column' 으로 거절한다 — 등록 자체가 실패한다.
    --   H2 create-drop 테스트는 엔티티로 테이블을 만들기 때문에 이 결함을 **절대 못 잡는다**.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id, code),
    CONSTRAINT fk_lo_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '매물 옵션. 유효 코드 검증은 서버 enum';


CREATE TABLE listing_safeties
(
    listing_id BIGINT      NOT NULL,
    code       VARCHAR(40) NOT NULL,
    -- ★ BaseEntity 를 상속한 엔티티는 이 두 칸을 **반드시** 요구한다. 없으면 Hibernate 가
    --   INSERT 에 넣어 보내고 MySQL 이 'Unknown column' 으로 거절한다 — 등록 자체가 실패한다.
    --   H2 create-drop 테스트는 엔티티로 테이블을 만들기 때문에 이 결함을 **절대 못 잡는다**.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id, code),
    CONSTRAINT fk_lsf_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '보안·안전시설. 옵션과 번호 공간이 겹치므로 절대 합치지 않는다';


CREATE TABLE listing_images
(
    id         BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    file_id    BIGINT NOT NULL COMMENT '기존 file 도메인 재사용 — 생명주기·격리를 그대로 탄다',
    sort_order INT    NOT NULL,
    -- ★ BaseEntity 를 상속한 엔티티는 이 두 칸을 **반드시** 요구한다. 없으면 Hibernate 가
    --   INSERT 에 넣어 보내고 MySQL 이 'Unknown column' 으로 거절한다 — 등록 자체가 실패한다.
    --   H2 create-drop 테스트는 엔티티로 테이블을 만들기 때문에 이 결함을 **절대 못 잡는다**.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_li (listing_id, sort_order),
    INDEX idx_li_file (file_id),
    CONSTRAINT fk_li_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_li_file FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '매물 사진. 상한 10장은 애플리케이션에서 — 숫자는 바뀐다';


-- 찜. 유학생이 나중에 다시 보려고 담아 두는 것이다(다방의 하트).
--
-- ★ **유학생(USER) 기능이다.** 중개사는 쓰지 않는다 — LISTING_MANAGE 와 엮지 않는다.
--
-- ★★ 탈퇴하면 **지운다**(PO 확정 2026-10-05). "재가입은 되지만 이전 데이터는 볼 수 없다"는
--     기존 정책과 같은 축이다. 지울 때 listings.favorite_count 를 **같이 줄여야** 한다 —
--     카운터만 남으면 아무도 찜하지 않은 매물에 숫자가 붙는다.
CREATE TABLE listing_favorites
(
    user_id    BIGINT      NOT NULL,
    listing_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (user_id, listing_id),
    INDEX idx_lf_listing (listing_id) COMMENT '매물 하나의 찜 수 집계·탈퇴 정리',
    INDEX idx_lf_user (user_id, created_at) COMMENT "'내 찜 목록'은 최근에 담은 순",
    CONSTRAINT fk_lf_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lf_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '찜(하트). 탈퇴 시 삭제하고 카운터를 되돌린다';


-- 가격 변경 이력. **append-only** 다.
--
-- ★ 왜 필요한가: 「온라인 부동산 광고 자율규약」이 **"가격이 사실과 다른 매물"** 을 허위매물로
--   규정한다(다방도 참여사다). 신고가 들어오면 "언제 얼마에서 얼마로 바뀌었나"를 답할 수
--   있어야 하는데, listings 는 **현재 값만** 들고 있어 그 질문에 답하지 못한다.
--
-- ★★ 각 행은 **"그 시각의 가격"** 이다(변경분이 아니라 스냅샷). 게시 시점에 한 행을 넣고
--     이후 바뀔 때마다 한 행을 더한다 — 그래야 이력만 읽어도 전체 흐름이 복원된다.
--     변경분만 쌓으면 첫 가격을 알 수 없어 "얼마에서 바뀌었나"를 못 센다.
--
-- ★ selling_type 도 담는다. 월세 → 전세 전환은 금액 변경보다 큰 변화다.
CREATE TABLE listing_price_history
(
    id           BIGINT                        NOT NULL AUTO_INCREMENT,
    listing_id   BIGINT                        NOT NULL,

    selling_type ENUM ('MONTHLY_RENT','LEASE') NOT NULL,
    deposit      BIGINT                        NOT NULL COMMENT '그 시각의 보증금(전세면 전세금)',
    monthly_rent BIGINT                        NOT NULL COMMENT '그 시각의 월세. 전세는 0',

    changed_by   BIGINT                        NOT NULL COMMENT '바꾼 사람. 사무소에 계정이 여럿이면 누가 바꿨는지가 증거다',
    changed_at   DATETIME(6)                   NOT NULL,

    PRIMARY KEY (id),
    INDEX idx_lph_listing (listing_id, changed_at) COMMENT '한 매물의 가격 흐름',
    CONSTRAINT fk_lph_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_lph_user FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '가격 변경 이력(append-only). 허위매물 신고에 답하는 근거';


-- 상세설명 번역 캐시. **요청이 있을 때만** 번역한다(PO 확정 2026-10-05).
--
-- ★ 등록 시 전건 번역을 하지 않는 이유: 매물 수만 건 × 지원 언어만큼 번역비가 선불로 나가는데
--   실제로 읽히는 조합은 그중 일부다. 유저가 '번역하기'를 누른 조합만 만들어 두고 재사용한다.
--
-- ★★ 캐시 히트 요건은 (매물, 언어, **원문 버전**) 셋이다. 버전을 빼면 중개사가 설명을 고쳐도
--     옛 번역이 계속 나가고, 유학생은 **존재하지 않는 조건**을 읽게 된다.
--
-- ★ 번역이 없거나 실패해도 매물은 뜬다 — 원문으로 떨어진다. 번역은 편의이지 게이트가 아니다.
CREATE TABLE listing_description_translations
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    listing_id     BIGINT      NOT NULL,
    language_code  VARCHAR(10) NOT NULL,
    -- 번역 당시 원문의 버전. listings.description_version 과 다르면 캐시 미스다.
    source_version INT         NOT NULL,

    content        TEXT        NOT NULL,

    -- 어느 엔진이 만들었는지. 엔진을 바꾼 뒤 품질 문제가 생기면 이 칸으로만 추적된다.
    engine         VARCHAR(30) NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- 같은 조합을 두 번 만들지 않는다. 동시 요청 둘이 같은 순간을 통과해도 하나만 남는다.
    UNIQUE KEY uq_ldt (listing_id, language_code, source_version),
    CONSTRAINT fk_ldt_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_ldt_language FOREIGN KEY (language_code) REFERENCES languages (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '상세설명 번역 캐시. 키는 (매물, 언어, 원문 버전)';


-- ★★ 반드시 **맨 뒤**에 붙인다. ENUM 은 내부적으로 순번(1,2,3…)으로 저장되므로
--     중간에 끼우면 뒤 값들이 한 칸씩 밀려 **기존 행의 role 이 통째로 바뀐다** —
--     관리자가 하루아침에 중개사가 된다.
ALTER TABLE users
    MODIFY role ENUM ('USER','MANAGER','ADMIN','BROKER') NOT NULL DEFAULT 'USER'
        COMMENT 'USER < MANAGER(담당자) < ADMIN(관리자). BROKER = 외부 공인중개사(매물 등록 전용)';
