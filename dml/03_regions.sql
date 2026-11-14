-- =============================================================================
-- dml/03_regions.sql — 지역 마스터(시도 → 시군구, 법정동코드) · 생성 파일, 손으로 고치지 않는다
--
-- 만든 곳: dml/tools/gen_regions.py(규칙은 그 머리말) · 지역 장부 dml/tools/regions.tsv(다른 변환기가 주소 → 시군구에 쓴다)
-- 원천: 코드 · 국문 = 국토교통부_전국 법정동(data.go.kr 15063424) · 이용허락범위 제한 없음
--       파일 국토교통부_전국 법정동_20260630.csv · sha256 e5657d4b53a16f72e42e9c0d91e84ff2d647e70dc56b9408da6fd057d0da45c3
--       영문 = 국가데이터처 「한국행정구역분류」 연계표(통계분류포털) · 파일 한국행정구역분류_2026.7.10.기준_20260714095603.xlsx · sha256 699d33c57828f8b2f7b2546360e6d65667f3d339d641f50ca757840a3f4b4a4c
-- 행 수: 시도 16 · 시군구 256(일반구가 있는 시 13곳은 시 행 대신 일반구)
--        서울특별시 25 · 경기도 47 · 인천광역시 11 · 부산광역시 16 · 대구광역시 9 · 대전광역시 5 · 전남광주통합특별시 27 · 울산광역시 5 · 세종특별자치시 1 · 강원특별자치도 18 · 충청북도 14 · 충청남도 16 · 전북특별자치도 15 · 경상북도 23 · 경상남도 22 · 제주특별자치도 2
-- 닫는 옛 코드: 29(광주광역시) · 46(전라남도)
--
-- 멱등: 코드 upsert. 다시 돌려도 이름만 고친다 — 이미 있는 행의 열림(is_active) · 순서(sort_order)는 관리자 몫이라 건드리지 않는다.
--       새 행은 열린 채로 들어간다(전국 서비스 D-96). 원천에서 사라진 코드(생성기의 RETIRED 표)만 닫는다(지우지 않는다) —
--       관리자가 만든 원천 밖 행은 그대로 둔다.
-- 실행: mysql --default-character-set=utf8mb4 <DB> < dml/03_regions.sql
-- =============================================================================

INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order)
VALUES
    (NULL, '11', '서울특별시', 'Seoul', 1, 1),
    (NULL, '41', '경기도', 'Gyeonggi-do', 1, 2),
    (NULL, '28', '인천광역시', 'Incheon', 1, 3),
    (NULL, '26', '부산광역시', 'Busan', 1, 4),
    (NULL, '27', '대구광역시', 'Daegu', 1, 5),
    (NULL, '30', '대전광역시', 'Daejeon', 1, 6),
    (NULL, '12', '전남광주통합특별시', 'Jeonnam-Gwangju', 1, 7),
    (NULL, '31', '울산광역시', 'Ulsan', 1, 8),
    (NULL, '36', '세종특별자치시', 'Sejong-si', 1, 9),
    (NULL, '51', '강원특별자치도', 'Gangwon-do', 1, 10),
    (NULL, '43', '충청북도', 'Chungcheongbuk-do', 1, 11),
    (NULL, '44', '충청남도', 'Chungcheongnam-do', 1, 12),
    (NULL, '52', '전북특별자치도', 'Jeonbuk-do', 1, 13),
    (NULL, '47', '경상북도', 'Gyeongsangbuk-do', 1, 14),
    (NULL, '48', '경상남도', 'Gyeongsangnam-do', 1, 15),
    (NULL, '50', '제주특별자치도', 'Jeju-do', 1, 16)
ON DUPLICATE KEY UPDATE name_ko = VALUES(name_ko),
                        name_en = VALUES(name_en);

-- ── 시군구(부모 = 같은 앞 2자리 시도)
INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order)
SELECT p.id, g.code, g.name_ko, g.name_en, 1, g.sort_order
FROM regions p
         JOIN (SELECT '11' parent_code, '11110' code, '종로구' name_ko, 'Jongno-gu' name_en, 1 sort_order
               UNION ALL SELECT '11', '11140', '중구', 'Jung-gu', 2
               UNION ALL SELECT '11', '11170', '용산구', 'Yongsan-gu', 3
               UNION ALL SELECT '11', '11200', '성동구', 'Seongdong-gu', 4
               UNION ALL SELECT '11', '11215', '광진구', 'Gwangjin-gu', 5
               UNION ALL SELECT '11', '11230', '동대문구', 'Dongdaemun-gu', 6
               UNION ALL SELECT '11', '11260', '중랑구', 'Jungnang-gu', 7
               UNION ALL SELECT '11', '11290', '성북구', 'Seongbuk-gu', 8
               UNION ALL SELECT '11', '11305', '강북구', 'Gangbuk-gu', 9
               UNION ALL SELECT '11', '11320', '도봉구', 'Dobong-gu', 10
               UNION ALL SELECT '11', '11350', '노원구', 'Nowon-gu', 11
               UNION ALL SELECT '11', '11380', '은평구', 'Eunpyeong-gu', 12
               UNION ALL SELECT '11', '11410', '서대문구', 'Seodaemun-gu', 13
               UNION ALL SELECT '11', '11440', '마포구', 'Mapo-gu', 14
               UNION ALL SELECT '11', '11470', '양천구', 'Yangcheon-gu', 15
               UNION ALL SELECT '11', '11500', '강서구', 'Gangseo-gu', 16
               UNION ALL SELECT '11', '11530', '구로구', 'Guro-gu', 17
               UNION ALL SELECT '11', '11545', '금천구', 'Geumcheon-gu', 18
               UNION ALL SELECT '11', '11560', '영등포구', 'Yeongdeungpo-gu', 19
               UNION ALL SELECT '11', '11590', '동작구', 'Dongjak-gu', 20
               UNION ALL SELECT '11', '11620', '관악구', 'Gwanak-gu', 21
               UNION ALL SELECT '11', '11650', '서초구', 'Seocho-gu', 22
               UNION ALL SELECT '11', '11680', '강남구', 'Gangnam-gu', 23
               UNION ALL SELECT '11', '11710', '송파구', 'Songpa-gu', 24
               UNION ALL SELECT '11', '11740', '강동구', 'Gangdong-gu', 25
               UNION ALL SELECT '12', '12110', '목포시', 'Mokpo-si', 1
               UNION ALL SELECT '12', '12130', '여수시', 'Yeosu-si', 2
               UNION ALL SELECT '12', '12150', '순천시', 'Suncheon-si', 3
               UNION ALL SELECT '12', '12170', '나주시', 'Naju-si', 4
               UNION ALL SELECT '12', '12190', '광양시', 'Gwangyang-si', 5
               UNION ALL SELECT '12', '12210', '동구', 'Dong-gu', 6
               UNION ALL SELECT '12', '12240', '서구', 'Seo-gu', 7
               UNION ALL SELECT '12', '12270', '남구', 'Nam-gu', 8
               UNION ALL SELECT '12', '12300', '북구', 'Buk-gu', 9
               UNION ALL SELECT '12', '12330', '광산구', 'Gwangsan-gu', 10
               UNION ALL SELECT '12', '12710', '담양군', 'Damyang-gun', 11
               UNION ALL SELECT '12', '12720', '곡성군', 'Gokseong-gun', 12
               UNION ALL SELECT '12', '12730', '구례군', 'Gurye-gun', 13
               UNION ALL SELECT '12', '12740', '고흥군', 'Goheung-gun', 14
               UNION ALL SELECT '12', '12750', '보성군', 'Boseong-gun', 15
               UNION ALL SELECT '12', '12760', '화순군', 'Hwasun-gun', 16
               UNION ALL SELECT '12', '12770', '장흥군', 'Jangheung-gun', 17
               UNION ALL SELECT '12', '12780', '강진군', 'Gangjin-gun', 18
               UNION ALL SELECT '12', '12790', '해남군', 'Haenam-gun', 19
               UNION ALL SELECT '12', '12800', '영암군', 'Yeongam-gun', 20
               UNION ALL SELECT '12', '12810', '무안군', 'Muan-gun', 21
               UNION ALL SELECT '12', '12820', '함평군', 'Hampyeong-gun', 22
               UNION ALL SELECT '12', '12830', '영광군', 'Yeonggwang-gun', 23
               UNION ALL SELECT '12', '12840', '장성군', 'Jangseong-gun', 24
               UNION ALL SELECT '12', '12850', '완도군', 'Wando-gun', 25
               UNION ALL SELECT '12', '12860', '진도군', 'Jindo-gun', 26
               UNION ALL SELECT '12', '12870', '신안군', 'Sinan-gun', 27
               UNION ALL SELECT '26', '26110', '중구', 'Jung-gu', 1
               UNION ALL SELECT '26', '26140', '서구', 'Seo-gu', 2
               UNION ALL SELECT '26', '26170', '동구', 'Dong-gu', 3
               UNION ALL SELECT '26', '26200', '영도구', 'Yeongdo-gu', 4
               UNION ALL SELECT '26', '26230', '부산진구', 'Busanjin-gu', 5
               UNION ALL SELECT '26', '26260', '동래구', 'Dongnae-gu', 6
               UNION ALL SELECT '26', '26290', '남구', 'Nam-gu', 7
               UNION ALL SELECT '26', '26320', '북구', 'Buk-gu', 8
               UNION ALL SELECT '26', '26350', '해운대구', 'Haeundae-gu', 9
               UNION ALL SELECT '26', '26380', '사하구', 'Saha-gu', 10
               UNION ALL SELECT '26', '26410', '금정구', 'Geumjeong-gu', 11
               UNION ALL SELECT '26', '26440', '강서구', 'Gangseo-gu', 12
               UNION ALL SELECT '26', '26470', '연제구', 'Yeonje-gu', 13
               UNION ALL SELECT '26', '26500', '수영구', 'Suyeong-gu', 14
               UNION ALL SELECT '26', '26530', '사상구', 'Sasang-gu', 15
               UNION ALL SELECT '26', '26710', '기장군', 'Gijang-gun', 16
               UNION ALL SELECT '27', '27110', '중구', 'Jung-gu', 1
               UNION ALL SELECT '27', '27140', '동구', 'Dong-gu', 2
               UNION ALL SELECT '27', '27170', '서구', 'Seo-gu', 3
               UNION ALL SELECT '27', '27200', '남구', 'Nam-gu', 4
               UNION ALL SELECT '27', '27230', '북구', 'Buk-gu', 5
               UNION ALL SELECT '27', '27260', '수성구', 'Suseong-gu', 6
               UNION ALL SELECT '27', '27290', '달서구', 'Dalseo-gu', 7
               UNION ALL SELECT '27', '27710', '달성군', 'Dalseong-gun', 8
               UNION ALL SELECT '27', '27720', '군위군', 'Gunwi-gun', 9
               UNION ALL SELECT '28', '28125', '제물포구', 'Jemulpo-gu', 1
               UNION ALL SELECT '28', '28155', '영종구', 'Yeongjong-gu', 2
               UNION ALL SELECT '28', '28177', '미추홀구', 'Michuhol-gu', 3
               UNION ALL SELECT '28', '28185', '연수구', 'Yeonsu-gu', 4
               UNION ALL SELECT '28', '28200', '남동구', 'Namdong-gu', 5
               UNION ALL SELECT '28', '28237', '부평구', 'Bupyeong-gu', 6
               UNION ALL SELECT '28', '28245', '계양구', 'Gyeyang-gu', 7
               UNION ALL SELECT '28', '28275', '서해구', 'Seohae-gu', 8
               UNION ALL SELECT '28', '28290', '검단구', 'Geomdan-gu', 9
               UNION ALL SELECT '28', '28710', '강화군', 'Ganghwa-gun', 10
               UNION ALL SELECT '28', '28720', '옹진군', 'Ongjin-gun', 11
               UNION ALL SELECT '30', '30110', '동구', 'Dong-gu', 1
               UNION ALL SELECT '30', '30140', '중구', 'Jung-gu', 2
               UNION ALL SELECT '30', '30170', '서구', 'Seo-gu', 3
               UNION ALL SELECT '30', '30200', '유성구', 'Yuseong-gu', 4
               UNION ALL SELECT '30', '30230', '대덕구', 'Daedeok-gu', 5
               UNION ALL SELECT '31', '31110', '중구', 'Jung-gu', 1
               UNION ALL SELECT '31', '31140', '남구', 'Nam-gu', 2
               UNION ALL SELECT '31', '31170', '동구', 'Dong-gu', 3
               UNION ALL SELECT '31', '31200', '북구', 'Buk-gu', 4
               UNION ALL SELECT '31', '31710', '울주군', 'Ulju-gun', 5
               UNION ALL SELECT '36', '36110', '세종시', 'Sejong-si', 1
               UNION ALL SELECT '41', '41111', '수원시 장안구', 'Suwon-si Jangan-gu', 1
               UNION ALL SELECT '41', '41113', '수원시 권선구', 'Suwon-si Gwonseon-gu', 2
               UNION ALL SELECT '41', '41115', '수원시 팔달구', 'Suwon-si Paldal-gu', 3
               UNION ALL SELECT '41', '41117', '수원시 영통구', 'Suwon-si Yeongtong-gu', 4
               UNION ALL SELECT '41', '41131', '성남시 수정구', 'Seongnam-si Sujeong-gu', 5
               UNION ALL SELECT '41', '41133', '성남시 중원구', 'Seongnam-si Jungwon-gu', 6
               UNION ALL SELECT '41', '41135', '성남시 분당구', 'Seongnam-si Bundang-gu', 7
               UNION ALL SELECT '41', '41150', '의정부시', 'Uijeongbu-si', 8
               UNION ALL SELECT '41', '41171', '안양시 만안구', 'Anyang-si Manan-gu', 9
               UNION ALL SELECT '41', '41173', '안양시 동안구', 'Anyang-si Dongan-gu', 10
               UNION ALL SELECT '41', '41192', '부천시 원미구', 'Bucheon-si Wonmi-gu', 11
               UNION ALL SELECT '41', '41194', '부천시 소사구', 'Bucheon-si Sosa-gu', 12
               UNION ALL SELECT '41', '41196', '부천시 오정구', 'Bucheon-si Ojeong-gu', 13
               UNION ALL SELECT '41', '41210', '광명시', 'Gwangmyeong-si', 14
               UNION ALL SELECT '41', '41220', '평택시', 'Pyeongtaek-si', 15
               UNION ALL SELECT '41', '41250', '동두천시', 'Dongducheon-si', 16
               UNION ALL SELECT '41', '41271', '안산시 상록구', 'Ansan-si Sangnok-gu', 17
               UNION ALL SELECT '41', '41273', '안산시 단원구', 'Ansan-si Danwon-gu', 18
               UNION ALL SELECT '41', '41281', '고양시 덕양구', 'Goyang-si Deogyang-gu', 19
               UNION ALL SELECT '41', '41285', '고양시 일산동구', 'Goyang-si Ilsandong-gu', 20
               UNION ALL SELECT '41', '41287', '고양시 일산서구', 'Goyang-si Ilsanseo-gu', 21
               UNION ALL SELECT '41', '41290', '과천시', 'Gwacheon-si', 22
               UNION ALL SELECT '41', '41310', '구리시', 'Guri-si', 23
               UNION ALL SELECT '41', '41360', '남양주시', 'Namyangju-si', 24
               UNION ALL SELECT '41', '41370', '오산시', 'Osan-si', 25
               UNION ALL SELECT '41', '41390', '시흥시', 'Siheung-si', 26
               UNION ALL SELECT '41', '41410', '군포시', 'Gunpo-si', 27
               UNION ALL SELECT '41', '41430', '의왕시', 'Uiwang-si', 28
               UNION ALL SELECT '41', '41450', '하남시', 'Hanam-si', 29
               UNION ALL SELECT '41', '41461', '용인시 처인구', 'Yongin-si Cheoin-gu', 30
               UNION ALL SELECT '41', '41463', '용인시 기흥구', 'Yongin-si Giheung-gu', 31
               UNION ALL SELECT '41', '41465', '용인시 수지구', 'Yongin-si Suji-gu', 32
               UNION ALL SELECT '41', '41480', '파주시', 'Paju-si', 33
               UNION ALL SELECT '41', '41500', '이천시', 'Icheon-si', 34
               UNION ALL SELECT '41', '41550', '안성시', 'Anseong-si', 35
               UNION ALL SELECT '41', '41570', '김포시', 'Gimpo-si', 36
               UNION ALL SELECT '41', '41591', '화성시 만세구', 'Hwaseong-si Manse-gu', 37
               UNION ALL SELECT '41', '41593', '화성시 효행구', 'Hwaseong-si Hyohaeng-gu', 38
               UNION ALL SELECT '41', '41595', '화성시 병점구', 'Hwaseong-si Byeongjeom-gu', 39
               UNION ALL SELECT '41', '41597', '화성시 동탄구', 'Hwaseong-si Dongtan-gu', 40
               UNION ALL SELECT '41', '41610', '광주시', 'Gwangju-si', 41
               UNION ALL SELECT '41', '41630', '양주시', 'Yangju-si', 42
               UNION ALL SELECT '41', '41650', '포천시', 'Pocheon-si', 43
               UNION ALL SELECT '41', '41670', '여주시', 'Yeoju-si', 44
               UNION ALL SELECT '41', '41800', '연천군', 'Yeoncheon-gun', 45
               UNION ALL SELECT '41', '41820', '가평군', 'Gapyeong-gun', 46
               UNION ALL SELECT '41', '41830', '양평군', 'Yangpyeong-gun', 47
               UNION ALL SELECT '43', '43111', '청주시 상당구', 'Cheongju-si Sangdang-gu', 1
               UNION ALL SELECT '43', '43112', '청주시 서원구', 'Cheongju-si Seowon-gu', 2
               UNION ALL SELECT '43', '43113', '청주시 흥덕구', 'Cheongju-si Heungdeok-gu', 3
               UNION ALL SELECT '43', '43114', '청주시 청원구', 'Cheongju-si Cheongwon-gu', 4
               UNION ALL SELECT '43', '43130', '충주시', 'Chungju-si', 5
               UNION ALL SELECT '43', '43150', '제천시', 'Jecheon-si', 6
               UNION ALL SELECT '43', '43720', '보은군', 'Boeun-gun', 7
               UNION ALL SELECT '43', '43730', '옥천군', 'Okcheon-gun', 8
               UNION ALL SELECT '43', '43740', '영동군', 'Yeongdong-gun', 9
               UNION ALL SELECT '43', '43745', '증평군', 'Jeungpyeong-gun', 10
               UNION ALL SELECT '43', '43750', '진천군', 'Jincheon-gun', 11
               UNION ALL SELECT '43', '43760', '괴산군', 'Goesan-gun', 12
               UNION ALL SELECT '43', '43770', '음성군', 'Eumseong-gun', 13
               UNION ALL SELECT '43', '43800', '단양군', 'Danyang-gun', 14
               UNION ALL SELECT '44', '44131', '천안시 동남구', 'Cheonan-si Dongnam-gu', 1
               UNION ALL SELECT '44', '44133', '천안시 서북구', 'Cheonan-si Sebuk-gu', 2
               UNION ALL SELECT '44', '44150', '공주시', 'Gongju-si', 3
               UNION ALL SELECT '44', '44180', '보령시', 'Boryeong-si', 4
               UNION ALL SELECT '44', '44200', '아산시', 'Asan-si', 5
               UNION ALL SELECT '44', '44210', '서산시', 'Seosan-si', 6
               UNION ALL SELECT '44', '44230', '논산시', 'Nonsan-si', 7
               UNION ALL SELECT '44', '44250', '계룡시', 'Gyeryong-si', 8
               UNION ALL SELECT '44', '44270', '당진시', 'Dangjin-si', 9
               UNION ALL SELECT '44', '44710', '금산군', 'Geumsan-gun', 10
               UNION ALL SELECT '44', '44760', '부여군', 'Buyeo-gun', 11
               UNION ALL SELECT '44', '44770', '서천군', 'Seocheon-gun', 12
               UNION ALL SELECT '44', '44790', '청양군', 'Cheongyang-gun', 13
               UNION ALL SELECT '44', '44800', '홍성군', 'Hongseong-gun', 14
               UNION ALL SELECT '44', '44810', '예산군', 'Yesan-gun', 15
               UNION ALL SELECT '44', '44825', '태안군', 'Taean-gun', 16
               UNION ALL SELECT '47', '47111', '포항시 남구', 'Pohang-si Nam-gu', 1
               UNION ALL SELECT '47', '47113', '포항시 북구', 'Pohang-si Buk-gu', 2
               UNION ALL SELECT '47', '47130', '경주시', 'Gyeongju-si', 3
               UNION ALL SELECT '47', '47150', '김천시', 'Gimcheon-si', 4
               UNION ALL SELECT '47', '47170', '안동시', 'Andong-si', 5
               UNION ALL SELECT '47', '47190', '구미시', 'Gumi-si', 6
               UNION ALL SELECT '47', '47210', '영주시', 'Yeongju-si', 7
               UNION ALL SELECT '47', '47230', '영천시', 'Yeongcheon-si', 8
               UNION ALL SELECT '47', '47250', '상주시', 'Sangju-si', 9
               UNION ALL SELECT '47', '47280', '문경시', 'Mungyeong-si', 10
               UNION ALL SELECT '47', '47290', '경산시', 'Gyeongsan-si', 11
               UNION ALL SELECT '47', '47730', '의성군', 'Uiseong-gun', 12
               UNION ALL SELECT '47', '47750', '청송군', 'Cheongsong-gun', 13
               UNION ALL SELECT '47', '47760', '영양군', 'Yeongyang-gun', 14
               UNION ALL SELECT '47', '47770', '영덕군', 'Yeongdeok-gun', 15
               UNION ALL SELECT '47', '47820', '청도군', 'Cheongdo-gun', 16
               UNION ALL SELECT '47', '47830', '고령군', 'Goryeong-gun', 17
               UNION ALL SELECT '47', '47840', '성주군', 'Seongju-gun', 18
               UNION ALL SELECT '47', '47850', '칠곡군', 'Chilgok-gun', 19
               UNION ALL SELECT '47', '47900', '예천군', 'Yecheon-gun', 20
               UNION ALL SELECT '47', '47920', '봉화군', 'Bonghwa-gun', 21
               UNION ALL SELECT '47', '47930', '울진군', 'Uljin-gun', 22
               UNION ALL SELECT '47', '47940', '울릉군', 'Ulleung-gun', 23
               UNION ALL SELECT '48', '48121', '창원시 의창구', 'Changwon-si Uichang-gu', 1
               UNION ALL SELECT '48', '48123', '창원시 성산구', 'Changwon-si seongsan-gu', 2
               UNION ALL SELECT '48', '48125', '창원시 마산합포구', 'Changwon-si Masanhappo-gu', 3
               UNION ALL SELECT '48', '48127', '창원시 마산회원구', 'Changwon-si Masanhoewon-gu', 4
               UNION ALL SELECT '48', '48129', '창원시 진해구', 'Changwon-si Jinhae-gu', 5
               UNION ALL SELECT '48', '48170', '진주시', 'Jinju-si', 6
               UNION ALL SELECT '48', '48220', '통영시', 'Tongyeong-si', 7
               UNION ALL SELECT '48', '48240', '사천시', 'Sacheon-si', 8
               UNION ALL SELECT '48', '48250', '김해시', 'Gimhae-si', 9
               UNION ALL SELECT '48', '48270', '밀양시', 'Miryang-si', 10
               UNION ALL SELECT '48', '48310', '거제시', 'Geoje-si', 11
               UNION ALL SELECT '48', '48330', '양산시', 'Yangsan-si', 12
               UNION ALL SELECT '48', '48720', '의령군', 'Uiryeong-gun', 13
               UNION ALL SELECT '48', '48730', '함안군', 'Haman-gun', 14
               UNION ALL SELECT '48', '48740', '창녕군', 'Changnyeong-gun', 15
               UNION ALL SELECT '48', '48820', '고성군', 'Goseong-gun', 16
               UNION ALL SELECT '48', '48840', '남해군', 'Namhae-gun', 17
               UNION ALL SELECT '48', '48850', '하동군', 'Hadong-gun', 18
               UNION ALL SELECT '48', '48860', '산청군', 'Sancheong-gun', 19
               UNION ALL SELECT '48', '48870', '함양군', 'Hamyang-gun', 20
               UNION ALL SELECT '48', '48880', '거창군', 'Geochang-gun', 21
               UNION ALL SELECT '48', '48890', '합천군', 'Hapcheon-gun', 22
               UNION ALL SELECT '50', '50110', '제주시', 'Jeju-si', 1
               UNION ALL SELECT '50', '50130', '서귀포시', 'Seogwipo-si', 2
               UNION ALL SELECT '51', '51110', '춘천시', 'Chuncheon-si', 1
               UNION ALL SELECT '51', '51130', '원주시', 'Wonju-si', 2
               UNION ALL SELECT '51', '51150', '강릉시', 'Gangneung-si', 3
               UNION ALL SELECT '51', '51170', '동해시', 'Donghae-si', 4
               UNION ALL SELECT '51', '51190', '태백시', 'Taebaek-si', 5
               UNION ALL SELECT '51', '51210', '속초시', 'Sokcho-si', 6
               UNION ALL SELECT '51', '51230', '삼척시', 'Samcheok-si', 7
               UNION ALL SELECT '51', '51720', '홍천군', 'Hongcheon-gun', 8
               UNION ALL SELECT '51', '51730', '횡성군', 'Hoengseong-gun', 9
               UNION ALL SELECT '51', '51750', '영월군', 'Yeongwol-gun', 10
               UNION ALL SELECT '51', '51760', '평창군', 'Pyeongchang-gun', 11
               UNION ALL SELECT '51', '51770', '정선군', 'Jeongseon-gun', 12
               UNION ALL SELECT '51', '51780', '철원군', 'Cheorwon-gun', 13
               UNION ALL SELECT '51', '51790', '화천군', 'Hwacheon-gun', 14
               UNION ALL SELECT '51', '51800', '양구군', 'Yanggu-gun', 15
               UNION ALL SELECT '51', '51810', '인제군', 'Inje-gun', 16
               UNION ALL SELECT '51', '51820', '고성군', 'Goseong-gun', 17
               UNION ALL SELECT '51', '51830', '양양군', 'Yangyang-gun', 18
               UNION ALL SELECT '52', '52111', '전주시 완산구', 'Jeonju-si Wansan-gu', 1
               UNION ALL SELECT '52', '52113', '전주시 덕진구', 'Jeonju-si Deokjin-gu', 2
               UNION ALL SELECT '52', '52130', '군산시', 'Gunsan-si', 3
               UNION ALL SELECT '52', '52140', '익산시', 'Iksan-si', 4
               UNION ALL SELECT '52', '52180', '정읍시', 'Jeongeup-si', 5
               UNION ALL SELECT '52', '52190', '남원시', 'Namwon-si', 6
               UNION ALL SELECT '52', '52210', '김제시', 'Gimje-si', 7
               UNION ALL SELECT '52', '52710', '완주군', 'Wanju-gun', 8
               UNION ALL SELECT '52', '52720', '진안군', 'Jinan-gun', 9
               UNION ALL SELECT '52', '52730', '무주군', 'Muju-gun', 10
               UNION ALL SELECT '52', '52740', '장수군', 'Jangsu-gun', 11
               UNION ALL SELECT '52', '52750', '임실군', 'Imsil-gun', 12
               UNION ALL SELECT '52', '52770', '순창군', 'Sunchang-gun', 13
               UNION ALL SELECT '52', '52790', '고창군', 'Gochang-gun', 14
               UNION ALL SELECT '52', '52800', '부안군', 'Buan-gun', 15) g
              ON p.code = g.parent_code
ON DUPLICATE KEY UPDATE name_ko = VALUES(name_ko),
                        name_en = VALUES(name_en);

-- ── 원천에서 사라진 코드(RETIRED 표)는 닫는다(지우지 않는다) — 관리자가 만든 원천 밖 행은 건드리지 않는다
UPDATE regions
SET is_active = 0
WHERE is_active = 1
  AND code IN ('29', '46');
