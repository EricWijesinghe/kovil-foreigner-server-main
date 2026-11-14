-- =============================================================================
-- dml/02_countries.sql — 국가 마스터 전체 시드 (ISO 3166-1 alpha-2 · 249개국)
--
-- 검증 (2026-08-21):
--   · code / 영문명: ISO 3166-1 공식 배정 코드 249개 데이터셋과 대조 (개수·코드 전수 일치)
--   · 표시용 영문명은 통용 표기(Vietnam, South Korea 등) — ISO 장식 표기("Viet Nam",
--     "Korea, Republic of")를 화면용으로 정리했다. 기존 V2 40개국 표기는 그대로다.
--   · 국기 이모지는 코드에서 기계 파생(Regional Indicator 두 글자) — 수기 오타가 불가능하다.
--   · 한국어 국가명은 통용 표기이며(외교부 고시 표기와 대체로 일치) 실무 검수 대상.
--
-- 멱등: 자연키(code) upsert. 재실행 시 명칭·이모지만 갱신하고
--       is_active·sort_order(운영 큐레이션 값)는 기존 행에서 보존한다.
-- 순서: V2의 상위 40개국(국내 체류 규모순 1~40) 유지, 나머지 209개국은 영문명 알파벳순 101~.
-- 선행: V2까지 적용된 DB (테이블·languages 존재). 실행: mysql < dml/02_countries.sql
-- =============================================================================

INSERT INTO countries (code, name_en, flag_emoji, is_active, sort_order)
VALUES
    ('CN', 'China', '🇨🇳', 1, 1),
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
    ('KR', 'South Korea', '🇰🇷', 1, 40),
    ('AF', 'Afghanistan', '🇦🇫', 1, 101),
    ('AX', 'Åland Islands', '🇦🇽', 1, 102),
    ('AL', 'Albania', '🇦🇱', 1, 103),
    ('DZ', 'Algeria', '🇩🇿', 1, 104),
    ('AS', 'American Samoa', '🇦🇸', 1, 105),
    ('AD', 'Andorra', '🇦🇩', 1, 106),
    ('AO', 'Angola', '🇦🇴', 1, 107),
    ('AI', 'Anguilla', '🇦🇮', 1, 108),
    ('AQ', 'Antarctica', '🇦🇶', 1, 109),
    ('AG', 'Antigua and Barbuda', '🇦🇬', 1, 110),
    ('AR', 'Argentina', '🇦🇷', 1, 111),
    ('AM', 'Armenia', '🇦🇲', 1, 112),
    ('AW', 'Aruba', '🇦🇼', 1, 113),
    ('AT', 'Austria', '🇦🇹', 1, 114),
    ('AZ', 'Azerbaijan', '🇦🇿', 1, 115),
    ('BS', 'Bahamas', '🇧🇸', 1, 116),
    ('BH', 'Bahrain', '🇧🇭', 1, 117),
    ('BB', 'Barbados', '🇧🇧', 1, 118),
    ('BY', 'Belarus', '🇧🇾', 1, 119),
    ('BE', 'Belgium', '🇧🇪', 1, 120),
    ('BZ', 'Belize', '🇧🇿', 1, 121),
    ('BJ', 'Benin', '🇧🇯', 1, 122),
    ('BM', 'Bermuda', '🇧🇲', 1, 123),
    ('BT', 'Bhutan', '🇧🇹', 1, 124),
    ('BO', 'Bolivia', '🇧🇴', 1, 125),
    ('BQ', 'Bonaire, Sint Eustatius and Saba', '🇧🇶', 1, 126),
    ('BA', 'Bosnia and Herzegovina', '🇧🇦', 1, 127),
    ('BW', 'Botswana', '🇧🇼', 1, 128),
    ('BV', 'Bouvet Island', '🇧🇻', 1, 129),
    ('IO', 'British Indian Ocean Territory', '🇮🇴', 1, 130),
    ('VG', 'British Virgin Islands', '🇻🇬', 1, 131),
    ('BN', 'Brunei', '🇧🇳', 1, 132),
    ('BG', 'Bulgaria', '🇧🇬', 1, 133),
    ('BF', 'Burkina Faso', '🇧🇫', 1, 134),
    ('BI', 'Burundi', '🇧🇮', 1, 135),
    ('CV', 'Cabo Verde', '🇨🇻', 1, 136),
    ('CM', 'Cameroon', '🇨🇲', 1, 137),
    ('KY', 'Cayman Islands', '🇰🇾', 1, 138),
    ('CF', 'Central African Republic', '🇨🇫', 1, 139),
    ('TD', 'Chad', '🇹🇩', 1, 140),
    ('CL', 'Chile', '🇨🇱', 1, 141),
    ('CX', 'Christmas Island', '🇨🇽', 1, 142),
    ('CC', 'Cocos Islands', '🇨🇨', 1, 143),
    ('CO', 'Colombia', '🇨🇴', 1, 144),
    ('KM', 'Comoros', '🇰🇲', 1, 145),
    ('CG', 'Congo', '🇨🇬', 1, 146),
    ('CK', 'Cook Islands', '🇨🇰', 1, 147),
    ('CR', 'Costa Rica', '🇨🇷', 1, 148),
    ('CI', 'Côte d''Ivoire', '🇨🇮', 1, 149),
    ('HR', 'Croatia', '🇭🇷', 1, 150),
    ('CU', 'Cuba', '🇨🇺', 1, 151),
    ('CW', 'Curaçao', '🇨🇼', 1, 152),
    ('CY', 'Cyprus', '🇨🇾', 1, 153),
    ('CZ', 'Czechia', '🇨🇿', 1, 154),
    ('DK', 'Denmark', '🇩🇰', 1, 155),
    ('DJ', 'Djibouti', '🇩🇯', 1, 156),
    ('DM', 'Dominica', '🇩🇲', 1, 157),
    ('DO', 'Dominican Republic', '🇩🇴', 1, 158),
    ('CD', 'DR Congo', '🇨🇩', 1, 159),
    ('EC', 'Ecuador', '🇪🇨', 1, 160),
    ('SV', 'El Salvador', '🇸🇻', 1, 161),
    ('GQ', 'Equatorial Guinea', '🇬🇶', 1, 162),
    ('ER', 'Eritrea', '🇪🇷', 1, 163),
    ('EE', 'Estonia', '🇪🇪', 1, 164),
    ('SZ', 'Eswatini', '🇸🇿', 1, 165),
    ('ET', 'Ethiopia', '🇪🇹', 1, 166),
    ('FK', 'Falkland Islands', '🇫🇰', 1, 167),
    ('FO', 'Faroe Islands', '🇫🇴', 1, 168),
    ('FJ', 'Fiji', '🇫🇯', 1, 169),
    ('FI', 'Finland', '🇫🇮', 1, 170),
    ('GF', 'French Guiana', '🇬🇫', 1, 171),
    ('PF', 'French Polynesia', '🇵🇫', 1, 172),
    ('TF', 'French Southern Territories', '🇹🇫', 1, 173),
    ('GA', 'Gabon', '🇬🇦', 1, 174),
    ('GM', 'Gambia', '🇬🇲', 1, 175),
    ('GE', 'Georgia', '🇬🇪', 1, 176),
    ('GH', 'Ghana', '🇬🇭', 1, 177),
    ('GI', 'Gibraltar', '🇬🇮', 1, 178),
    ('GR', 'Greece', '🇬🇷', 1, 179),
    ('GL', 'Greenland', '🇬🇱', 1, 180),
    ('GD', 'Grenada', '🇬🇩', 1, 181),
    ('GP', 'Guadeloupe', '🇬🇵', 1, 182),
    ('GU', 'Guam', '🇬🇺', 1, 183),
    ('GT', 'Guatemala', '🇬🇹', 1, 184),
    ('GG', 'Guernsey', '🇬🇬', 1, 185),
    ('GN', 'Guinea', '🇬🇳', 1, 186),
    ('GW', 'Guinea-Bissau', '🇬🇼', 1, 187),
    ('GY', 'Guyana', '🇬🇾', 1, 188),
    ('HT', 'Haiti', '🇭🇹', 1, 189),
    ('HM', 'Heard Island and McDonald Islands', '🇭🇲', 1, 190),
    ('HN', 'Honduras', '🇭🇳', 1, 191),
    ('HU', 'Hungary', '🇭🇺', 1, 192),
    ('IS', 'Iceland', '🇮🇸', 1, 193),
    ('IR', 'Iran', '🇮🇷', 1, 194),
    ('IQ', 'Iraq', '🇮🇶', 1, 195),
    ('IM', 'Isle of Man', '🇮🇲', 1, 196),
    ('IL', 'Israel', '🇮🇱', 1, 197),
    ('JM', 'Jamaica', '🇯🇲', 1, 198),
    ('JE', 'Jersey', '🇯🇪', 1, 199),
    ('JO', 'Jordan', '🇯🇴', 1, 200),
    ('KE', 'Kenya', '🇰🇪', 1, 201),
    ('KI', 'Kiribati', '🇰🇮', 1, 202),
    ('KW', 'Kuwait', '🇰🇼', 1, 203),
    ('LA', 'Laos', '🇱🇦', 1, 204),
    ('LV', 'Latvia', '🇱🇻', 1, 205),
    ('LB', 'Lebanon', '🇱🇧', 1, 206),
    ('LS', 'Lesotho', '🇱🇸', 1, 207),
    ('LR', 'Liberia', '🇱🇷', 1, 208),
    ('LY', 'Libya', '🇱🇾', 1, 209),
    ('LI', 'Liechtenstein', '🇱🇮', 1, 210),
    ('LT', 'Lithuania', '🇱🇹', 1, 211),
    ('LU', 'Luxembourg', '🇱🇺', 1, 212),
    ('MO', 'Macao', '🇲🇴', 1, 213),
    ('MG', 'Madagascar', '🇲🇬', 1, 214),
    ('MW', 'Malawi', '🇲🇼', 1, 215),
    ('MV', 'Maldives', '🇲🇻', 1, 216),
    ('ML', 'Mali', '🇲🇱', 1, 217),
    ('MT', 'Malta', '🇲🇹', 1, 218),
    ('MH', 'Marshall Islands', '🇲🇭', 1, 219),
    ('MQ', 'Martinique', '🇲🇶', 1, 220),
    ('MR', 'Mauritania', '🇲🇷', 1, 221),
    ('MU', 'Mauritius', '🇲🇺', 1, 222),
    ('YT', 'Mayotte', '🇾🇹', 1, 223),
    ('FM', 'Micronesia', '🇫🇲', 1, 224),
    ('MD', 'Moldova', '🇲🇩', 1, 225),
    ('MC', 'Monaco', '🇲🇨', 1, 226),
    ('ME', 'Montenegro', '🇲🇪', 1, 227),
    ('MS', 'Montserrat', '🇲🇸', 1, 228),
    ('MA', 'Morocco', '🇲🇦', 1, 229),
    ('MZ', 'Mozambique', '🇲🇿', 1, 230),
    ('NA', 'Namibia', '🇳🇦', 1, 231),
    ('NR', 'Nauru', '🇳🇷', 1, 232),
    ('NC', 'New Caledonia', '🇳🇨', 1, 233),
    ('NI', 'Nicaragua', '🇳🇮', 1, 234),
    ('NE', 'Niger', '🇳🇪', 1, 235),
    ('NU', 'Niue', '🇳🇺', 1, 236),
    ('NF', 'Norfolk Island', '🇳🇫', 1, 237),
    ('KP', 'North Korea', '🇰🇵', 1, 238),
    ('MK', 'North Macedonia', '🇲🇰', 1, 239),
    ('MP', 'Northern Mariana Islands', '🇲🇵', 1, 240),
    ('NO', 'Norway', '🇳🇴', 1, 241),
    ('OM', 'Oman', '🇴🇲', 1, 242),
    ('PW', 'Palau', '🇵🇼', 1, 243),
    ('PS', 'Palestine', '🇵🇸', 1, 244),
    ('PA', 'Panama', '🇵🇦', 1, 245),
    ('PG', 'Papua New Guinea', '🇵🇬', 1, 246),
    ('PY', 'Paraguay', '🇵🇾', 1, 247),
    ('PE', 'Peru', '🇵🇪', 1, 248),
    ('PN', 'Pitcairn Islands', '🇵🇳', 1, 249),
    ('PL', 'Poland', '🇵🇱', 1, 250),
    ('PT', 'Portugal', '🇵🇹', 1, 251),
    ('PR', 'Puerto Rico', '🇵🇷', 1, 252),
    ('QA', 'Qatar', '🇶🇦', 1, 253),
    ('RE', 'Réunion', '🇷🇪', 1, 254),
    ('RO', 'Romania', '🇷🇴', 1, 255),
    ('RW', 'Rwanda', '🇷🇼', 1, 256),
    ('BL', 'Saint Barthélemy', '🇧🇱', 1, 257),
    ('SH', 'Saint Helena', '🇸🇭', 1, 258),
    ('KN', 'Saint Kitts and Nevis', '🇰🇳', 1, 259),
    ('LC', 'Saint Lucia', '🇱🇨', 1, 260),
    ('MF', 'Saint Martin', '🇲🇫', 1, 261),
    ('PM', 'Saint Pierre and Miquelon', '🇵🇲', 1, 262),
    ('VC', 'Saint Vincent and the Grenadines', '🇻🇨', 1, 263),
    ('WS', 'Samoa', '🇼🇸', 1, 264),
    ('SM', 'San Marino', '🇸🇲', 1, 265),
    ('ST', 'Sao Tome and Principe', '🇸🇹', 1, 266),
    ('SA', 'Saudi Arabia', '🇸🇦', 1, 267),
    ('SN', 'Senegal', '🇸🇳', 1, 268),
    ('RS', 'Serbia', '🇷🇸', 1, 269),
    ('SC', 'Seychelles', '🇸🇨', 1, 270),
    ('SL', 'Sierra Leone', '🇸🇱', 1, 271),
    ('SX', 'Sint Maarten', '🇸🇽', 1, 272),
    ('SK', 'Slovakia', '🇸🇰', 1, 273),
    ('SI', 'Slovenia', '🇸🇮', 1, 274),
    ('SB', 'Solomon Islands', '🇸🇧', 1, 275),
    ('SO', 'Somalia', '🇸🇴', 1, 276),
    ('GS', 'South Georgia and the South Sandwich Islands', '🇬🇸', 1, 277),
    ('SS', 'South Sudan', '🇸🇸', 1, 278),
    ('SD', 'Sudan', '🇸🇩', 1, 279),
    ('SR', 'Suriname', '🇸🇷', 1, 280),
    ('SJ', 'Svalbard and Jan Mayen', '🇸🇯', 1, 281),
    ('SE', 'Sweden', '🇸🇪', 1, 282),
    ('CH', 'Switzerland', '🇨🇭', 1, 283),
    ('SY', 'Syria', '🇸🇾', 1, 284),
    ('TJ', 'Tajikistan', '🇹🇯', 1, 285),
    ('TZ', 'Tanzania', '🇹🇿', 1, 286),
    ('TL', 'Timor-Leste', '🇹🇱', 1, 287),
    ('TG', 'Togo', '🇹🇬', 1, 288),
    ('TK', 'Tokelau', '🇹🇰', 1, 289),
    ('TO', 'Tonga', '🇹🇴', 1, 290),
    ('TT', 'Trinidad and Tobago', '🇹🇹', 1, 291),
    ('TN', 'Tunisia', '🇹🇳', 1, 292),
    ('TM', 'Turkmenistan', '🇹🇲', 1, 293),
    ('TC', 'Turks and Caicos Islands', '🇹🇨', 1, 294),
    ('TV', 'Tuvalu', '🇹🇻', 1, 295),
    ('VI', 'U.S. Virgin Islands', '🇻🇮', 1, 296),
    ('UG', 'Uganda', '🇺🇬', 1, 297),
    ('UA', 'Ukraine', '🇺🇦', 1, 298),
    ('AE', 'United Arab Emirates', '🇦🇪', 1, 299),
    ('UM', 'United States Minor Outlying Islands', '🇺🇲', 1, 300),
    ('UY', 'Uruguay', '🇺🇾', 1, 301),
    ('VU', 'Vanuatu', '🇻🇺', 1, 302),
    ('VA', 'Vatican City', '🇻🇦', 1, 303),
    ('VE', 'Venezuela', '🇻🇪', 1, 304),
    ('WF', 'Wallis and Futuna', '🇼🇫', 1, 305),
    ('EH', 'Western Sahara', '🇪🇭', 1, 306),
    ('YE', 'Yemen', '🇾🇪', 1, 307),
    ('ZM', 'Zambia', '🇿🇲', 1, 308),
    ('ZW', 'Zimbabwe', '🇿🇼', 1, 309)
ON DUPLICATE KEY UPDATE name_en    = VALUES(name_en),
                        flag_emoji = VALUES(flag_emoji);

-- ── 한국어 국가명 오버레이 (관리자·담당자 화면용. en은 name_en 폴백이라 행을 만들지 않는다)
INSERT INTO country_translations (country_id, language_code, name)
SELECT c.id, 'ko', t.name_ko
FROM countries c
         JOIN (
               SELECT 'AD' code, '안도라' name_ko
               UNION ALL SELECT 'AE', '아랍에미리트'
               UNION ALL SELECT 'AF', '아프가니스탄'
               UNION ALL SELECT 'AG', '앤티가 바부다'
               UNION ALL SELECT 'AI', '앵귈라'
               UNION ALL SELECT 'AL', '알바니아'
               UNION ALL SELECT 'AM', '아르메니아'
               UNION ALL SELECT 'AO', '앙골라'
               UNION ALL SELECT 'AQ', '남극'
               UNION ALL SELECT 'AR', '아르헨티나'
               UNION ALL SELECT 'AS', '아메리칸사모아'
               UNION ALL SELECT 'AT', '오스트리아'
               UNION ALL SELECT 'AU', '호주'
               UNION ALL SELECT 'AW', '아루바'
               UNION ALL SELECT 'AX', '올란드 제도'
               UNION ALL SELECT 'AZ', '아제르바이잔'
               UNION ALL SELECT 'BA', '보스니아 헤르체고비나'
               UNION ALL SELECT 'BB', '바베이도스'
               UNION ALL SELECT 'BD', '방글라데시'
               UNION ALL SELECT 'BE', '벨기에'
               UNION ALL SELECT 'BF', '부르키나파소'
               UNION ALL SELECT 'BG', '불가리아'
               UNION ALL SELECT 'BH', '바레인'
               UNION ALL SELECT 'BI', '부룬디'
               UNION ALL SELECT 'BJ', '베냉'
               UNION ALL SELECT 'BL', '생바르텔레미'
               UNION ALL SELECT 'BM', '버뮤다'
               UNION ALL SELECT 'BN', '브루나이'
               UNION ALL SELECT 'BO', '볼리비아'
               UNION ALL SELECT 'BQ', '카리브 네덜란드'
               UNION ALL SELECT 'BR', '브라질'
               UNION ALL SELECT 'BS', '바하마'
               UNION ALL SELECT 'BT', '부탄'
               UNION ALL SELECT 'BV', '부베섬'
               UNION ALL SELECT 'BW', '보츠와나'
               UNION ALL SELECT 'BY', '벨라루스'
               UNION ALL SELECT 'BZ', '벨리즈'
               UNION ALL SELECT 'CA', '캐나다'
               UNION ALL SELECT 'CC', '코코스 제도'
               UNION ALL SELECT 'CD', '콩고민주공화국'
               UNION ALL SELECT 'CF', '중앙아프리카공화국'
               UNION ALL SELECT 'CG', '콩고'
               UNION ALL SELECT 'CH', '스위스'
               UNION ALL SELECT 'CI', '코트디부아르'
               UNION ALL SELECT 'CK', '쿡 제도'
               UNION ALL SELECT 'CL', '칠레'
               UNION ALL SELECT 'CM', '카메룬'
               UNION ALL SELECT 'CN', '중국'
               UNION ALL SELECT 'CO', '콜롬비아'
               UNION ALL SELECT 'CR', '코스타리카'
               UNION ALL SELECT 'CU', '쿠바'
               UNION ALL SELECT 'CV', '카보베르데'
               UNION ALL SELECT 'CW', '퀴라소'
               UNION ALL SELECT 'CX', '크리스마스섬'
               UNION ALL SELECT 'CY', '키프로스'
               UNION ALL SELECT 'CZ', '체코'
               UNION ALL SELECT 'DE', '독일'
               UNION ALL SELECT 'DJ', '지부티'
               UNION ALL SELECT 'DK', '덴마크'
               UNION ALL SELECT 'DM', '도미니카'
               UNION ALL SELECT 'DO', '도미니카공화국'
               UNION ALL SELECT 'DZ', '알제리'
               UNION ALL SELECT 'EC', '에콰도르'
               UNION ALL SELECT 'EE', '에스토니아'
               UNION ALL SELECT 'EG', '이집트'
               UNION ALL SELECT 'EH', '서사하라'
               UNION ALL SELECT 'ER', '에리트레아'
               UNION ALL SELECT 'ES', '스페인'
               UNION ALL SELECT 'ET', '에티오피아'
               UNION ALL SELECT 'FI', '핀란드'
               UNION ALL SELECT 'FJ', '피지'
               UNION ALL SELECT 'FK', '포클랜드 제도'
               UNION ALL SELECT 'FM', '미크로네시아'
               UNION ALL SELECT 'FO', '페로 제도'
               UNION ALL SELECT 'FR', '프랑스'
               UNION ALL SELECT 'GA', '가봉'
               UNION ALL SELECT 'GB', '영국'
               UNION ALL SELECT 'GD', '그레나다'
               UNION ALL SELECT 'GE', '조지아'
               UNION ALL SELECT 'GF', '프랑스령 기아나'
               UNION ALL SELECT 'GG', '건지'
               UNION ALL SELECT 'GH', '가나'
               UNION ALL SELECT 'GI', '지브롤터'
               UNION ALL SELECT 'GL', '그린란드'
               UNION ALL SELECT 'GM', '감비아'
               UNION ALL SELECT 'GN', '기니'
               UNION ALL SELECT 'GP', '과들루프'
               UNION ALL SELECT 'GQ', '적도기니'
               UNION ALL SELECT 'GR', '그리스'
               UNION ALL SELECT 'GS', '사우스조지아 사우스샌드위치 제도'
               UNION ALL SELECT 'GT', '과테말라'
               UNION ALL SELECT 'GU', '괌'
               UNION ALL SELECT 'GW', '기니비사우'
               UNION ALL SELECT 'GY', '가이아나'
               UNION ALL SELECT 'HK', '홍콩'
               UNION ALL SELECT 'HM', '허드 맥도널드 제도'
               UNION ALL SELECT 'HN', '온두라스'
               UNION ALL SELECT 'HR', '크로아티아'
               UNION ALL SELECT 'HT', '아이티'
               UNION ALL SELECT 'HU', '헝가리'
               UNION ALL SELECT 'ID', '인도네시아'
               UNION ALL SELECT 'IE', '아일랜드'
               UNION ALL SELECT 'IL', '이스라엘'
               UNION ALL SELECT 'IM', '맨섬'
               UNION ALL SELECT 'IN', '인도'
               UNION ALL SELECT 'IO', '영국령 인도양 지역'
               UNION ALL SELECT 'IQ', '이라크'
               UNION ALL SELECT 'IR', '이란'
               UNION ALL SELECT 'IS', '아이슬란드'
               UNION ALL SELECT 'IT', '이탈리아'
               UNION ALL SELECT 'JE', '저지'
               UNION ALL SELECT 'JM', '자메이카'
               UNION ALL SELECT 'JO', '요르단'
               UNION ALL SELECT 'JP', '일본'
               UNION ALL SELECT 'KE', '케냐'
               UNION ALL SELECT 'KG', '키르기스스탄'
               UNION ALL SELECT 'KH', '캄보디아'
               UNION ALL SELECT 'KI', '키리바시'
               UNION ALL SELECT 'KM', '코모로'
               UNION ALL SELECT 'KN', '세인트키츠 네비스'
               UNION ALL SELECT 'KP', '북한'
               UNION ALL SELECT 'KR', '대한민국'
               UNION ALL SELECT 'KW', '쿠웨이트'
               UNION ALL SELECT 'KY', '케이맨 제도'
               UNION ALL SELECT 'KZ', '카자흐스탄'
               UNION ALL SELECT 'LA', '라오스'
               UNION ALL SELECT 'LB', '레바논'
               UNION ALL SELECT 'LC', '세인트루시아'
               UNION ALL SELECT 'LI', '리히텐슈타인'
               UNION ALL SELECT 'LK', '스리랑카'
               UNION ALL SELECT 'LR', '라이베리아'
               UNION ALL SELECT 'LS', '레소토'
               UNION ALL SELECT 'LT', '리투아니아'
               UNION ALL SELECT 'LU', '룩셈부르크'
               UNION ALL SELECT 'LV', '라트비아'
               UNION ALL SELECT 'LY', '리비아'
               UNION ALL SELECT 'MA', '모로코'
               UNION ALL SELECT 'MC', '모나코'
               UNION ALL SELECT 'MD', '몰도바'
               UNION ALL SELECT 'ME', '몬테네그로'
               UNION ALL SELECT 'MF', '생마르탱'
               UNION ALL SELECT 'MG', '마다가스카르'
               UNION ALL SELECT 'MH', '마셜 제도'
               UNION ALL SELECT 'MK', '북마케도니아'
               UNION ALL SELECT 'ML', '말리'
               UNION ALL SELECT 'MM', '미얀마'
               UNION ALL SELECT 'MN', '몽골'
               UNION ALL SELECT 'MO', '마카오'
               UNION ALL SELECT 'MP', '북마리아나 제도'
               UNION ALL SELECT 'MQ', '마르티니크'
               UNION ALL SELECT 'MR', '모리타니'
               UNION ALL SELECT 'MS', '몬트세랫'
               UNION ALL SELECT 'MT', '몰타'
               UNION ALL SELECT 'MU', '모리셔스'
               UNION ALL SELECT 'MV', '몰디브'
               UNION ALL SELECT 'MW', '말라위'
               UNION ALL SELECT 'MX', '멕시코'
               UNION ALL SELECT 'MY', '말레이시아'
               UNION ALL SELECT 'MZ', '모잠비크'
               UNION ALL SELECT 'NA', '나미비아'
               UNION ALL SELECT 'NC', '뉴칼레도니아'
               UNION ALL SELECT 'NE', '니제르'
               UNION ALL SELECT 'NF', '노퍽섬'
               UNION ALL SELECT 'NG', '나이지리아'
               UNION ALL SELECT 'NI', '니카라과'
               UNION ALL SELECT 'NL', '네덜란드'
               UNION ALL SELECT 'NO', '노르웨이'
               UNION ALL SELECT 'NP', '네팔'
               UNION ALL SELECT 'NR', '나우루'
               UNION ALL SELECT 'NU', '니우에'
               UNION ALL SELECT 'NZ', '뉴질랜드'
               UNION ALL SELECT 'OM', '오만'
               UNION ALL SELECT 'PA', '파나마'
               UNION ALL SELECT 'PE', '페루'
               UNION ALL SELECT 'PF', '프랑스령 폴리네시아'
               UNION ALL SELECT 'PG', '파푸아뉴기니'
               UNION ALL SELECT 'PH', '필리핀'
               UNION ALL SELECT 'PK', '파키스탄'
               UNION ALL SELECT 'PL', '폴란드'
               UNION ALL SELECT 'PM', '생피에르 미클롱'
               UNION ALL SELECT 'PN', '핏케언 제도'
               UNION ALL SELECT 'PR', '푸에르토리코'
               UNION ALL SELECT 'PS', '팔레스타인'
               UNION ALL SELECT 'PT', '포르투갈'
               UNION ALL SELECT 'PW', '팔라우'
               UNION ALL SELECT 'PY', '파라과이'
               UNION ALL SELECT 'QA', '카타르'
               UNION ALL SELECT 'RE', '레위니옹'
               UNION ALL SELECT 'RO', '루마니아'
               UNION ALL SELECT 'RS', '세르비아'
               UNION ALL SELECT 'RU', '러시아'
               UNION ALL SELECT 'RW', '르완다'
               UNION ALL SELECT 'SA', '사우디아라비아'
               UNION ALL SELECT 'SB', '솔로몬 제도'
               UNION ALL SELECT 'SC', '세이셸'
               UNION ALL SELECT 'SD', '수단'
               UNION ALL SELECT 'SE', '스웨덴'
               UNION ALL SELECT 'SG', '싱가포르'
               UNION ALL SELECT 'SH', '세인트헬레나'
               UNION ALL SELECT 'SI', '슬로베니아'
               UNION ALL SELECT 'SJ', '스발바르 얀마옌'
               UNION ALL SELECT 'SK', '슬로바키아'
               UNION ALL SELECT 'SL', '시에라리온'
               UNION ALL SELECT 'SM', '산마리노'
               UNION ALL SELECT 'SN', '세네갈'
               UNION ALL SELECT 'SO', '소말리아'
               UNION ALL SELECT 'SR', '수리남'
               UNION ALL SELECT 'SS', '남수단'
               UNION ALL SELECT 'ST', '상투메 프린시페'
               UNION ALL SELECT 'SV', '엘살바도르'
               UNION ALL SELECT 'SX', '신트마르턴'
               UNION ALL SELECT 'SY', '시리아'
               UNION ALL SELECT 'SZ', '에스와티니'
               UNION ALL SELECT 'TC', '터크스 케이커스 제도'
               UNION ALL SELECT 'TD', '차드'
               UNION ALL SELECT 'TF', '프랑스령 남방 지역'
               UNION ALL SELECT 'TG', '토고'
               UNION ALL SELECT 'TH', '태국'
               UNION ALL SELECT 'TJ', '타지키스탄'
               UNION ALL SELECT 'TK', '토켈라우'
               UNION ALL SELECT 'TL', '동티모르'
               UNION ALL SELECT 'TM', '투르크메니스탄'
               UNION ALL SELECT 'TN', '튀니지'
               UNION ALL SELECT 'TO', '통가'
               UNION ALL SELECT 'TR', '튀르키예'
               UNION ALL SELECT 'TT', '트리니다드 토바고'
               UNION ALL SELECT 'TV', '투발루'
               UNION ALL SELECT 'TW', '대만'
               UNION ALL SELECT 'TZ', '탄자니아'
               UNION ALL SELECT 'UA', '우크라이나'
               UNION ALL SELECT 'UG', '우간다'
               UNION ALL SELECT 'UM', '미국령 군소 제도'
               UNION ALL SELECT 'US', '미국'
               UNION ALL SELECT 'UY', '우루과이'
               UNION ALL SELECT 'UZ', '우즈베키스탄'
               UNION ALL SELECT 'VA', '바티칸'
               UNION ALL SELECT 'VC', '세인트빈센트 그레나딘'
               UNION ALL SELECT 'VE', '베네수엘라'
               UNION ALL SELECT 'VG', '영국령 버진아일랜드'
               UNION ALL SELECT 'VI', '미국령 버진아일랜드'
               UNION ALL SELECT 'VN', '베트남'
               UNION ALL SELECT 'VU', '바누아투'
               UNION ALL SELECT 'WF', '왈리스 푸투나'
               UNION ALL SELECT 'WS', '사모아'
               UNION ALL SELECT 'YE', '예멘'
               UNION ALL SELECT 'YT', '마요트'
               UNION ALL SELECT 'ZA', '남아프리카공화국'
               UNION ALL SELECT 'ZM', '잠비아'
               UNION ALL SELECT 'ZW', '짐바브웨') t ON t.code = c.code
ON DUPLICATE KEY UPDATE name = VALUES(name);
