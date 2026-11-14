-- =============================================================================
-- V2__seed_master_data.sql — 초기 마스터 데이터 (DML)
--
-- ⚠ 프로덕션 반영 전 반드시 원본 대조가 필요한 값 (이 파일의 코드값은 검증되지 않았다):
--   1) banks.bank_code    → 금융결제원 표준 기관코드표
--      · 031은 2024년 대구은행 → iM뱅크 사명 변경분을 반영했다(코드 자체는 불변)
--   2) regions.code       → 행정안전부 법정동코드 (특히 강원특별자치도 51 / 전북특별자치도 52 —
--                            구 42 / 45에서 변경된 값이라 오래된 자료와 충돌한다)
--   3) countries.code     → ISO 3166-1 alpha-2. 아래는 국내 체류 외국인 상위 국적 위주 40개
--                            발췌이며 전체 249개는 원본에서 일괄 시드해야 한다
--   → HUMAN_TODO.md P0-2 참조. 은행 코드 하나가 틀리면 엉뚱한 계좌로 월세를 받는다.
--
-- 실행 순서: languages → countries → banks → 신분/문서/요건 → regions → 요청 옵션 → 커뮤니티
--   (translations 테이블이 languages.code에 FK를 걸고 있어 언어가 항상 먼저다)
-- =============================================================================


-- =============================================================================
-- 1. 언어
--    폴백은 en — 서비스 대상이 외국인이라 번역이 빈 자리에 한국어가 뜨면 화면이 죽는다.
-- =============================================================================
INSERT INTO languages (code, native_name, english_name, is_default, active, sort_order)
VALUES ('en', 'English', 'English', 1, 1, 1),
       ('ko', '한국어', 'Korean', 0, 1, 2);


-- =============================================================================
-- 2. 국가 (국적 축)
--    sort_order = 국내 체류 외국인 규모 순 — 국적 선택 화면 상단 노출용.
--    name_en이 정본이고 country_translations는 오버레이다(행이 없으면 name_en 폴백).
-- =============================================================================
INSERT INTO countries (code, name_en, flag_emoji, is_active, sort_order)
VALUES ('CN', 'China', '🇨🇳', 1, 1),
       ('VN', 'Vietnam', '🇻🇳', 1, 2),
       ('TH', 'Thailand', '🇹🇭', 1, 3),
       ('US', 'United States', '🇺🇸', 1, 4),
       ('UZ', 'Uzbekistan', '🇺🇿', 1, 5),
       ('PH', 'Philippines', '🇵🇭', 1, 6),
       ('NP', 'Nepal', '🇳🇵', 1, 7),
       ('RU', 'Russia', '🇷🇺', 1, 8),
       ('KH', 'Cambodia', '🇰🇭', 1, 9),
       ('MN', 'Mongolia', '🇲🇳', 1, 10),
       ('ID', 'Indonesia', '🇮🇩', 1, 11),
       ('MM', 'Myanmar', '🇲🇲', 1, 12),
       ('JP', 'Japan', '🇯🇵', 1, 13),
       ('KZ', 'Kazakhstan', '🇰🇿', 1, 14),
       ('LK', 'Sri Lanka', '🇱🇰', 1, 15),
       ('BD', 'Bangladesh', '🇧🇩', 1, 16),
       ('PK', 'Pakistan', '🇵🇰', 1, 17),
       ('IN', 'India', '🇮🇳', 1, 18),
       ('TW', 'Taiwan', '🇹🇼', 1, 19),
       ('HK', 'Hong Kong', '🇭🇰', 1, 20),
       ('CA', 'Canada', '🇨🇦', 1, 21),
       ('GB', 'United Kingdom', '🇬🇧', 1, 22),
       ('AU', 'Australia', '🇦🇺', 1, 23),
       ('NZ', 'New Zealand', '🇳🇿', 1, 24),
       ('IE', 'Ireland', '🇮🇪', 1, 25),
       ('FR', 'France', '🇫🇷', 1, 26),
       ('DE', 'Germany', '🇩🇪', 1, 27),
       ('IT', 'Italy', '🇮🇹', 1, 28),
       ('ES', 'Spain', '🇪🇸', 1, 29),
       ('NL', 'Netherlands', '🇳🇱', 1, 30),
       ('MY', 'Malaysia', '🇲🇾', 1, 31),
       ('SG', 'Singapore', '🇸🇬', 1, 32),
       ('KG', 'Kyrgyzstan', '🇰🇬', 1, 33),
       ('TR', 'Türkiye', '🇹🇷', 1, 34),
       ('EG', 'Egypt', '🇪🇬', 1, 35),
       ('NG', 'Nigeria', '🇳🇬', 1, 36),
       ('ZA', 'South Africa', '🇿🇦', 1, 37),
       ('BR', 'Brazil', '🇧🇷', 1, 38),
       ('MX', 'Mexico', '🇲🇽', 1, 39),
       ('KR', 'South Korea', '🇰🇷', 1, 40);

-- 한국어 국가명 오버레이 (en은 name_en 폴백이라 행을 만들지 않는다)
INSERT INTO country_translations (country_id, language_code, name)
SELECT c.id, 'ko', t.name_ko
FROM countries c
         JOIN (SELECT 'CN' code, '중국' name_ko
               UNION ALL SELECT 'VN', '베트남'
               UNION ALL SELECT 'TH', '태국'
               UNION ALL SELECT 'US', '미국'
               UNION ALL SELECT 'UZ', '우즈베키스탄'
               UNION ALL SELECT 'PH', '필리핀'
               UNION ALL SELECT 'NP', '네팔'
               UNION ALL SELECT 'RU', '러시아'
               UNION ALL SELECT 'KH', '캄보디아'
               UNION ALL SELECT 'MN', '몽골'
               UNION ALL SELECT 'ID', '인도네시아'
               UNION ALL SELECT 'MM', '미얀마'
               UNION ALL SELECT 'JP', '일본'
               UNION ALL SELECT 'KZ', '카자흐스탄'
               UNION ALL SELECT 'LK', '스리랑카'
               UNION ALL SELECT 'BD', '방글라데시'
               UNION ALL SELECT 'PK', '파키스탄'
               UNION ALL SELECT 'IN', '인도'
               UNION ALL SELECT 'TW', '대만'
               UNION ALL SELECT 'HK', '홍콩'
               UNION ALL SELECT 'CA', '캐나다'
               UNION ALL SELECT 'GB', '영국'
               UNION ALL SELECT 'AU', '호주'
               UNION ALL SELECT 'NZ', '뉴질랜드'
               UNION ALL SELECT 'IE', '아일랜드'
               UNION ALL SELECT 'FR', '프랑스'
               UNION ALL SELECT 'DE', '독일'
               UNION ALL SELECT 'IT', '이탈리아'
               UNION ALL SELECT 'ES', '스페인'
               UNION ALL SELECT 'NL', '네덜란드'
               UNION ALL SELECT 'MY', '말레이시아'
               UNION ALL SELECT 'SG', '싱가포르'
               UNION ALL SELECT 'KG', '키르기스스탄'
               UNION ALL SELECT 'TR', '튀르키예'
               UNION ALL SELECT 'EG', '이집트'
               UNION ALL SELECT 'NG', '나이지리아'
               UNION ALL SELECT 'ZA', '남아프리카공화국'
               UNION ALL SELECT 'BR', '브라질'
               UNION ALL SELECT 'MX', '멕시코'
               UNION ALL SELECT 'KR', '대한민국') t ON t.code = c.code;


-- =============================================================================
-- 3. 은행 (계약 수납 계좌 드롭다운)
--    name_ko = 관리자·담당자 화면, name_en = 유저 화면.
--    icon_file_id는 NULL로 둔다 — 아이콘은 관리자 콘솔에서 업로드 후 연결한다.
--    sort_order = 실제 사용 빈도 순(시중은행 → 인터넷은행 → 지방은행 → 상호금융 → 외국계).
--    is_active = 0인 행은 드롭다운에서 숨지만 과거 계약의 스냅샷은 그대로 유효하다.
-- =============================================================================
INSERT INTO banks (bank_code, name_ko, name_en, is_active, sort_order)
VALUES
    -- 시중은행
    ('004', '국민은행', 'KB Kookmin Bank', 1, 1),
    ('088', '신한은행', 'Shinhan Bank', 1, 2),
    ('020', '우리은행', 'Woori Bank', 1, 3),
    ('081', '하나은행', 'Hana Bank', 1, 4),
    ('011', '농협은행', 'NH NongHyup Bank', 1, 5),
    ('003', '기업은행', 'IBK Industrial Bank of Korea', 1, 6),
    -- 인터넷전문은행 (외국인 이용률이 빠르게 오르는 축)
    ('090', '카카오뱅크', 'KakaoBank', 1, 7),
    ('092', '토스뱅크', 'Toss Bank', 1, 8),
    ('089', '케이뱅크', 'K bank', 1, 9),
    -- 지방은행
    ('031', 'iM뱅크', 'iM Bank', 1, 10),
    ('032', '부산은행', 'Busan Bank', 1, 11),
    ('039', '경남은행', 'Kyongnam Bank', 1, 12),
    ('034', '광주은행', 'Gwangju Bank', 1, 13),
    ('037', '전북은행', 'Jeonbuk Bank', 1, 14),
    ('035', '제주은행', 'Jeju Bank', 1, 15),
    -- 상호금융·우체국
    ('045', '새마을금고', 'MG Community Credit Cooperatives', 1, 16),
    ('048', '신협', 'Credit Union', 1, 17),
    ('071', '우체국', 'Korea Post', 1, 18),
    ('007', '수협은행', 'Suhyup Bank', 1, 19),
    ('012', '지역농축협', 'NongHyup (Local)', 1, 20),
    ('064', '산림조합', 'Forestry Cooperatives', 1, 21),
    ('050', '상호저축은행', 'Mutual Savings Bank', 1, 22),
    -- 외국계·정책금융
    ('023', 'SC제일은행', 'SC First Bank', 1, 23),
    ('027', '한국씨티은행', 'Citibank Korea', 1, 24),
    ('002', '산업은행', 'KDB Korea Development Bank', 1, 25),
    ('054', 'HSBC은행', 'HSBC', 1, 26);


-- =============================================================================
-- 4. 체류 신분
--    ENUM이 아니라 테이블인 이유: 신분이 늘어도 코드가 하는 일은 같다
--    (요건 테이블을 읽고 → 필수 문서가 다 왔는지 검사 → 심사 큐에 올림).
--    따라서 신분 추가는 아래 INSERT 3개면 끝나고 배포가 필요 없다.
-- =============================================================================
INSERT INTO resident_statuses (code, icon_key, active, sort_order)
VALUES ('STUDENT', 'graduation-cap', 1, 1),
       ('WORKER', 'briefcase', 1, 2);

INSERT INTO resident_status_translations (resident_status_id, language_code, name, description)
SELECT s.id, t.lang, t.name, t.description
FROM resident_statuses s
         JOIN (SELECT 'STUDENT' code, 'en' lang, 'Student' name,
                      'Currently enrolled at a school or university in Korea' description
               UNION ALL SELECT 'STUDENT', 'ko', '유학생', '국내 학교·대학에 재학 중'
               UNION ALL SELECT 'WORKER', 'en', 'Worker',
                      'Currently employed at a company in Korea'
               UNION ALL SELECT 'WORKER', 'ko', '직장인', '국내 기업에 재직 중') t ON t.code = s.code;


-- =============================================================================
-- 5. 제출 문서 종류
--    is_sensitive = 1 → 공개 URL을 만들지 않고 presigned GET으로만 열람한다.
-- =============================================================================
INSERT INTO document_types (code, is_sensitive, max_files, active, sort_order)
VALUES ('PASSPORT', 1, 2, 1, 1),        -- 사진면 + 필요 시 서명면
       ('ARC', 1, 2, 1, 2),             -- 외국인등록증 앞/뒤
       ('ENROLLMENT_CERT', 0, 1, 1, 3), -- 재학증명서
       ('EMPLOYMENT_CERT', 0, 1, 1, 4); -- 재직증명서

INSERT INTO document_type_translations (document_type_id, language_code, name, guide_note)
SELECT d.id, t.lang, t.name, t.guide_note
FROM document_types d
         JOIN (SELECT 'PASSPORT' code, 'en' lang, 'Passport' name,
                      'Photo page only. Make sure all four corners are visible and the text is not blurred.' guide_note
               UNION ALL SELECT 'PASSPORT', 'ko', '여권',
                      '사진면만 촬영해 주세요. 네 모서리가 모두 보이고 글자가 흐리지 않아야 합니다.'
               UNION ALL SELECT 'ARC', 'en', 'Alien Registration Card',
                      'Both front and back are required. Upload them as two separate photos.'
               UNION ALL SELECT 'ARC', 'ko', '외국인등록증',
                      '앞면과 뒷면 모두 필요합니다. 각각 따로 촬영해 올려주세요.'
               UNION ALL SELECT 'ENROLLMENT_CERT', 'en', 'Certificate of Enrollment',
                      'Issued within the last 3 months. The school seal must be visible.'
               UNION ALL SELECT 'ENROLLMENT_CERT', 'ko', '재학증명서',
                      '최근 3개월 이내 발급본이어야 하며, 학교 직인이 보여야 합니다.'
               UNION ALL SELECT 'EMPLOYMENT_CERT', 'en', 'Certificate of Employment',
                      'Issued within the last 3 months. The company seal must be visible.'
               UNION ALL SELECT 'EMPLOYMENT_CERT', 'ko', '재직증명서',
                      '최근 3개월 이내 발급본이어야 하며, 회사 직인이 보여야 합니다.') t ON t.code = d.code;


-- =============================================================================
-- 6. 요건 매트릭스 (신분 × 문서)
--    여권·외국인등록증은 공통이고 신분별 증명서만 갈린다.
--    요건이 바뀌면 이 테이블 행만 조작한다 — 코드 변경도 배포도 없다.
-- =============================================================================
INSERT INTO resident_status_documents (resident_status_id, document_type_id, is_required, sort_order)
SELECT s.id, d.id, m.is_required, m.sort_order
FROM (SELECT 'STUDENT' s_code, 'PASSPORT' d_code, 1 is_required, 1 sort_order
      UNION ALL SELECT 'STUDENT', 'ARC', 1, 2
      UNION ALL SELECT 'STUDENT', 'ENROLLMENT_CERT', 1, 3
      UNION ALL SELECT 'WORKER', 'PASSPORT', 1, 1
      UNION ALL SELECT 'WORKER', 'ARC', 1, 2
      UNION ALL SELECT 'WORKER', 'EMPLOYMENT_CERT', 1, 3) m
         JOIN resident_statuses s ON s.code = m.s_code
         JOIN document_types d ON d.code = m.d_code;


-- =============================================================================
-- 7. 행정구역
--    ⚠ 시도 17개 + 서울 25개 자치구만 넣는다(착수용 최소 시드).
--       전국 시군구 약 250개는 행정안전부 법정동코드 원본에서 일괄 시드할 것.
--    is_active = 0 → 서비스 미개시 지역(선택 목록에서 숨김). 서울만 열어둔다.
--    → 실제 개시 지역은 HUMAN_TODO.md P1-5에서 확정한다.
-- =============================================================================
INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order)
VALUES (NULL, '11', '서울특별시', 'Seoul', 1, 1),
       (NULL, '41', '경기도', 'Gyeonggi-do', 0, 2),
       (NULL, '28', '인천광역시', 'Incheon', 0, 3),
       (NULL, '26', '부산광역시', 'Busan', 0, 4),
       (NULL, '27', '대구광역시', 'Daegu', 0, 5),
       (NULL, '30', '대전광역시', 'Daejeon', 0, 6),
       (NULL, '29', '광주광역시', 'Gwangju', 0, 7),
       (NULL, '31', '울산광역시', 'Ulsan', 0, 8),
       (NULL, '36', '세종특별자치시', 'Sejong', 0, 9),
       (NULL, '51', '강원특별자치도', 'Gangwon-do', 0, 10),
       (NULL, '43', '충청북도', 'Chungcheongbuk-do', 0, 11),
       (NULL, '44', '충청남도', 'Chungcheongnam-do', 0, 12),
       (NULL, '52', '전북특별자치도', 'Jeonbuk-do', 0, 13),
       (NULL, '46', '전라남도', 'Jeollanam-do', 0, 14),
       (NULL, '47', '경상북도', 'Gyeongsangbuk-do', 0, 15),
       (NULL, '48', '경상남도', 'Gyeongsangnam-do', 0, 16),
       (NULL, '50', '제주특별자치도', 'Jeju-do', 0, 17);

INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order)
SELECT p.id, g.code, g.name_ko, g.name_en, 1, g.sort_order
FROM regions p
         JOIN (SELECT '11110' code, '종로구' name_ko, 'Jongno-gu' name_en, 1 sort_order
               UNION ALL SELECT '11140', '중구', 'Jung-gu', 2
               UNION ALL SELECT '11170', '용산구', 'Yongsan-gu', 3
               UNION ALL SELECT '11200', '성동구', 'Seongdong-gu', 4
               UNION ALL SELECT '11215', '광진구', 'Gwangjin-gu', 5
               UNION ALL SELECT '11230', '동대문구', 'Dongdaemun-gu', 6
               UNION ALL SELECT '11260', '중랑구', 'Jungnang-gu', 7
               UNION ALL SELECT '11290', '성북구', 'Seongbuk-gu', 8
               UNION ALL SELECT '11305', '강북구', 'Gangbuk-gu', 9
               UNION ALL SELECT '11320', '도봉구', 'Dobong-gu', 10
               UNION ALL SELECT '11350', '노원구', 'Nowon-gu', 11
               UNION ALL SELECT '11380', '은평구', 'Eunpyeong-gu', 12
               UNION ALL SELECT '11410', '서대문구', 'Seodaemun-gu', 13
               UNION ALL SELECT '11440', '마포구', 'Mapo-gu', 14
               UNION ALL SELECT '11470', '양천구', 'Yangcheon-gu', 15
               UNION ALL SELECT '11500', '강서구', 'Gangseo-gu', 16
               UNION ALL SELECT '11530', '구로구', 'Guro-gu', 17
               UNION ALL SELECT '11545', '금천구', 'Geumcheon-gu', 18
               UNION ALL SELECT '11560', '영등포구', 'Yeongdeungpo-gu', 19
               UNION ALL SELECT '11590', '동작구', 'Dongjak-gu', 20
               UNION ALL SELECT '11620', '관악구', 'Gwanak-gu', 21
               UNION ALL SELECT '11650', '서초구', 'Seocho-gu', 22
               UNION ALL SELECT '11680', '강남구', 'Gangnam-gu', 23
               UNION ALL SELECT '11710', '송파구', 'Songpa-gu', 24
               UNION ALL SELECT '11740', '강동구', 'Gangdong-gu', 25) g
              ON p.code = '11';


-- =============================================================================
-- 8. 임대 요청 옵션 (2단 트리 + 번역)
--    ⚠ Figma 시안에서 화면 텍스트 추출에 실패해 항목 구성이 가정이다.
--       확정은 HUMAN_TODO.md P1-4. 항목 추가·삭제는 행 조작으로 끝나며 앱 배포가 필요 없다.
-- =============================================================================
INSERT INTO request_options (parent_id, code, selection_type, icon_key, active, sort_order)
VALUES (NULL, 'ROOM_TYPE', 'SINGLE', 'home', 1, 1),
       (NULL, 'FURNISHING', 'MULTI', 'sofa', 1, 2),
       (NULL, 'FACILITY', 'MULTI', 'building', 1, 3),
       (NULL, 'POLICY', 'MULTI', 'check-circle', 1, 4);

INSERT INTO request_options (parent_id, code, selection_type, icon_key, active, sort_order)
SELECT g.id, i.code, NULL, i.icon_key, 1, i.sort_order
FROM (SELECT 'ROOM_TYPE' grp, 'ONE_ROOM' code, 'square' icon_key, 1 sort_order
      UNION ALL SELECT 'ROOM_TYPE', 'TWO_ROOM', 'columns-2', 2
      UNION ALL SELECT 'ROOM_TYPE', 'THREE_ROOM_PLUS', 'columns-3', 3
      UNION ALL SELECT 'ROOM_TYPE', 'OFFICETEL', 'building-2', 4
      UNION ALL SELECT 'ROOM_TYPE', 'SHARE_HOUSE', 'users', 5
      UNION ALL SELECT 'FURNISHING', 'AIR_CONDITIONER', 'wind', 1
      UNION ALL SELECT 'FURNISHING', 'WASHING_MACHINE', 'washing-machine', 2
      UNION ALL SELECT 'FURNISHING', 'REFRIGERATOR', 'refrigerator', 3
      UNION ALL SELECT 'FURNISHING', 'COOKTOP', 'flame', 4
      UNION ALL SELECT 'FURNISHING', 'BED', 'bed', 5
      UNION ALL SELECT 'FURNISHING', 'DESK', 'lamp-desk', 6
      UNION ALL SELECT 'FURNISHING', 'WARDROBE', 'shirt', 7
      UNION ALL SELECT 'FACILITY', 'ELEVATOR', 'move-vertical', 1
      UNION ALL SELECT 'FACILITY', 'PARKING', 'car', 2
      UNION ALL SELECT 'FACILITY', 'CCTV', 'cctv', 3
      UNION ALL SELECT 'FACILITY', 'PARCEL_LOCKER', 'package', 4
      UNION ALL SELECT 'FACILITY', 'SEPARATE_KITCHEN', 'cooking-pot', 5
      UNION ALL SELECT 'POLICY', 'PET_ALLOWED', 'dog', 1
      UNION ALL SELECT 'POLICY', 'SMOKING_ALLOWED', 'cigarette', 2
      UNION ALL SELECT 'POLICY', 'SHORT_TERM_OK', 'calendar-clock', 3
      UNION ALL SELECT 'POLICY', 'FOREIGNER_REGISTRATION_SUPPORT', 'file-check', 4) i
         JOIN request_options g ON g.code = i.grp AND g.parent_id IS NULL;

-- 옵션 번역 (그룹 + 항목 전량). 행이 없으면 FE가 code를 그대로 노출하므로 반드시 채운다.
INSERT INTO request_option_translations (request_option_id, language_code, name)
SELECT o.id, t.lang, t.name
FROM request_options o
         JOIN (SELECT 'ROOM_TYPE' code, 'en' lang, 'Room type' name
               UNION ALL SELECT 'ROOM_TYPE', 'ko', '방 구조'
               UNION ALL SELECT 'FURNISHING', 'en', 'Furnishings'
               UNION ALL SELECT 'FURNISHING', 'ko', '옵션'
               UNION ALL SELECT 'FACILITY', 'en', 'Building facilities'
               UNION ALL SELECT 'FACILITY', 'ko', '편의시설'
               UNION ALL SELECT 'POLICY', 'en', 'Conditions'
               UNION ALL SELECT 'POLICY', 'ko', '조건'

               UNION ALL SELECT 'ONE_ROOM', 'en', 'Studio (one-room)'
               UNION ALL SELECT 'ONE_ROOM', 'ko', '원룸'
               UNION ALL SELECT 'TWO_ROOM', 'en', 'Two-room (1 bedroom)'
               UNION ALL SELECT 'TWO_ROOM', 'ko', '투룸'
               UNION ALL SELECT 'THREE_ROOM_PLUS', 'en', 'Three-room or larger'
               UNION ALL SELECT 'THREE_ROOM_PLUS', 'ko', '쓰리룸 이상'
               UNION ALL SELECT 'OFFICETEL', 'en', 'Officetel'
               UNION ALL SELECT 'OFFICETEL', 'ko', '오피스텔'
               UNION ALL SELECT 'SHARE_HOUSE', 'en', 'Share house'
               UNION ALL SELECT 'SHARE_HOUSE', 'ko', '쉐어하우스'

               UNION ALL SELECT 'AIR_CONDITIONER', 'en', 'Air conditioner'
               UNION ALL SELECT 'AIR_CONDITIONER', 'ko', '에어컨'
               UNION ALL SELECT 'WASHING_MACHINE', 'en', 'Washing machine'
               UNION ALL SELECT 'WASHING_MACHINE', 'ko', '세탁기'
               UNION ALL SELECT 'REFRIGERATOR', 'en', 'Refrigerator'
               UNION ALL SELECT 'REFRIGERATOR', 'ko', '냉장고'
               UNION ALL SELECT 'COOKTOP', 'en', 'Cooktop'
               UNION ALL SELECT 'COOKTOP', 'ko', '인덕션·가스레인지'
               UNION ALL SELECT 'BED', 'en', 'Bed'
               UNION ALL SELECT 'BED', 'ko', '침대'
               UNION ALL SELECT 'DESK', 'en', 'Desk'
               UNION ALL SELECT 'DESK', 'ko', '책상'
               UNION ALL SELECT 'WARDROBE', 'en', 'Wardrobe'
               UNION ALL SELECT 'WARDROBE', 'ko', '옷장'

               UNION ALL SELECT 'ELEVATOR', 'en', 'Elevator'
               UNION ALL SELECT 'ELEVATOR', 'ko', '엘리베이터'
               UNION ALL SELECT 'PARKING', 'en', 'Parking'
               UNION ALL SELECT 'PARKING', 'ko', '주차'
               UNION ALL SELECT 'CCTV', 'en', 'CCTV'
               UNION ALL SELECT 'CCTV', 'ko', 'CCTV'
               UNION ALL SELECT 'PARCEL_LOCKER', 'en', 'Parcel locker'
               UNION ALL SELECT 'PARCEL_LOCKER', 'ko', '무인택배함'
               UNION ALL SELECT 'SEPARATE_KITCHEN', 'en', 'Separate kitchen'
               UNION ALL SELECT 'SEPARATE_KITCHEN', 'ko', '분리형 주방'

               UNION ALL SELECT 'PET_ALLOWED', 'en', 'Pets allowed'
               UNION ALL SELECT 'PET_ALLOWED', 'ko', '반려동물 가능'
               UNION ALL SELECT 'SMOKING_ALLOWED', 'en', 'Smoking allowed'
               UNION ALL SELECT 'SMOKING_ALLOWED', 'ko', '흡연 가능'
               UNION ALL SELECT 'SHORT_TERM_OK', 'en', 'Short-term lease OK'
               UNION ALL SELECT 'SHORT_TERM_OK', 'ko', '단기 임대 가능'
               UNION ALL SELECT 'FOREIGNER_REGISTRATION_SUPPORT', 'en', 'Alien registration support'
               UNION ALL SELECT 'FOREIGNER_REGISTRATION_SUPPORT', 'ko', '외국인 등록 지원') t
              ON t.code = o.code;


-- =============================================================================
-- 9. 커뮤니티 카테고리
--    ⚠ 이 시드가 0건이면 글쓰기 자체가 불가능하다(운영 선행 조건).
--    is_anonymous = 1 (v1 전면 익명). ★ 글이 생긴 뒤에는 이 값을 바꿀 수 없다 —
--    익명→비익명 전환은 과거 익명 글의 작성자를 소급 노출시키는 사고다.
-- =============================================================================
INSERT INTO community_categories (uuid, code, is_anonymous, active, sort_order)
VALUES (UUID(), 'HOUSING', 1, 1, 1),
       (UUID(), 'VISA', 1, 1, 2),
       (UUID(), 'LIFE', 1, 1, 3),
       (UUID(), 'JOB', 1, 1, 4),
       (UUID(), 'FREE', 1, 1, 5);

INSERT INTO community_category_translations (category_id, language_code, name)
SELECT c.id, t.lang, t.name
FROM community_categories c
         JOIN (SELECT 'HOUSING' code, 'en' lang, 'Housing' name
               UNION ALL SELECT 'HOUSING', 'ko', '집 구하기'
               UNION ALL SELECT 'VISA', 'en', 'Visa & Immigration'
               UNION ALL SELECT 'VISA', 'ko', '비자·체류'
               UNION ALL SELECT 'LIFE', 'en', 'Living in Korea'
               UNION ALL SELECT 'LIFE', 'ko', '생활 정보'
               UNION ALL SELECT 'JOB', 'en', 'Jobs'
               UNION ALL SELECT 'JOB', 'ko', '구인·구직'
               UNION ALL SELECT 'FREE', 'en', 'Free Talk'
               UNION ALL SELECT 'FREE', 'ko', '자유게시판') t ON t.code = c.code;


-- =============================================================================
-- 시드하지 않은 것 (의도)
--   · banned_words          — 운영자가 신고 접수 시 즉시 등록하는 값. 선등록 대상이 아니다
--   · users(ADMIN 계정)     — 운영자 계정은 시드가 아니라 사람이 만든다. 시드로 만들면
--                              초기 비밀번호가 저장소에 남는다 (HUMAN_TODO.md P0-3)
--   · files / banks.icon    — 은행 아이콘은 관리자 콘솔 업로드 후 UPDATE로 연결
-- =============================================================================
