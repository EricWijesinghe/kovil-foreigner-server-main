-- =============================================================================
-- dml/05_subway.sql — 지하철 노선 · 역 · 역-노선(전국 도시철도) · 생성 파일, 손으로 고치지 않는다
--
-- 만든 곳: dml/tools/gen_subway.py(규칙은 그 머리말) · 역 코드 장부 dml/tools/subway_station_codes.tsv
-- 원천: 「전국도시철도역사정보표준데이터」 칸 구성 · PO 제공 파일 · 받은 곳 · 이용 조건 확인 중(포털 판본 15013205 은 이용허락범위 제한 없음 — HUMAN_TODO #72)
--       파일 전체_도시철도역사정보_20260630.xlsx · sha256 cdf1d84a7e5c898b2aacd622783ba8ba9af35c40bee0561dc97d55ce8e063f94 · 데이터기준일자 2022-04-27 ~ 2026-06-25
-- 노선색: PO 제공 표(2026-10-12)
-- 행 수: 노선 35 · 역 934(서울 306 · 경기 241 · 인천 86 · 부산 122 · 대구 87 · 광주 20 · 대전 22 · 울산 6 · 강원 6 · 충남 11 · 경북 10 · 경남 17, 환승역 134) · 역-노선 1090
--        원천 1099행 중 넣은 노선 1092행(중복 2행 · 틀린 행 1행(DROP_ROWS) · 자기부상 뺌)
-- 좌표가 틀려 넣지 않은 역: 마곡역(SEL-5) · 양원역(GYEONGUI-JUNGANG) — 고친 좌표가 확인되면 넣는다
-- 시군구가 붙은 역 916 · 못 정한 역 18: 병점역 · 기흥역 · 이매역 · 소새울역 · 시흥대야역 · 신현역 · 동인천역 · 인천역 · 검암역 · 공항화물청사역 · 영종역 · 운서역 · 인천공항1터미널역 · 인천공항2터미널역 · 청라국제도시역 · 신포역 · 신검단중앙역 · 검단오류역
--
-- ⚠ subway_lines · subway_stations · subway_station_lines 를 만드는 V 마이그레이션이 먼저(또는 같은 커밋) 나가야 한다.
-- 멱등: 코드 upsert. 원천에서 사라진 역 · 노선은 지우지 않고 is_active = 0. 역-노선은 이번 역들의 것을 통째로 다시 맞춘다.
-- 실행: mysql --default-character-set=utf8mb4 <DB> < dml/05_subway.sql
-- =============================================================================

INSERT INTO subway_lines (code, name_ko, name_en, color, is_active, sort_order)
VALUES
    ('SEL-1', '1호선', 'Line 1', '#0052A4', 1, 1),
    ('SEL-2', '2호선', 'Line 2', '#00A84D', 1, 2),
    ('SEL-3', '3호선', 'Line 3', '#EF7C1C', 1, 3),
    ('SEL-4', '4호선', 'Line 4', '#00A4E3', 1, 4),
    ('SEL-5', '5호선', 'Line 5', '#996CAC', 1, 5),
    ('SEL-6', '6호선', 'Line 6', '#CD7C2F', 1, 6),
    ('SEL-7', '7호선', 'Line 7', '#747F00', 1, 7),
    ('SEL-8', '8호선', 'Line 8', '#E6186C', 1, 8),
    ('SEL-9', '9호선', 'Line 9', '#BDB092', 1, 9),
    ('GYEONGUI-JUNGANG', '경의중앙선', 'Gyeongui-Jungang Line', '#77C4A3', 1, 10),
    ('AREX', '공항철도', 'AREX', '#0090D2', 1, 11),
    ('SUIN-BUNDANG', '수인분당선', 'Suin-Bundang Line', '#FABE00', 1, 12),
    ('SHINBUNDANG', '신분당선', 'Shinbundang Line', '#D20F46', 1, 13),
    ('GYEONGCHUN', '경춘선', 'Gyeongchun Line', '#178C72', 1, 14),
    ('GYEONGGANG', '경강선', 'Gyeonggang Line', '#0054A6', 1, 15),
    ('SEOHAE', '서해선', 'Seohae Line', '#8FC31F', 1, 16),
    ('UI-SINSEOL', '우이신설선', 'Ui-Sinseol Line', '#B7C450', 1, 17),
    ('SILLIM', '신림선', 'Sillim Line', '#6789CA', 1, 18),
    ('GIMPO-GOLD', '김포골드라인', 'Gimpo Goldline', '#AD8605', 1, 19),
    ('UIJEONGBU', '의정부경전철', 'Uijeongbu LRT', '#F39C00', 1, 20),
    ('EVERLINE', '용인에버라인', 'Yongin EverLine', '#56AD2D', 1, 21),
    ('ICN-1', '인천 1호선', 'Incheon Line 1', '#759CCE', 1, 22),
    ('ICN-2', '인천 2호선', 'Incheon Line 2', '#F5A251', 1, 23),
    ('BUSAN-1', '부산 1호선', 'Busan Line 1', '#F06A00', 1, 24),
    ('BUSAN-2', '부산 2호선', 'Busan Line 2', '#81BF48', 1, 25),
    ('BUSAN-3', '부산 3호선', 'Busan Line 3', '#BB8C00', 1, 26),
    ('BUSAN-4', '부산 4호선', 'Busan Line 4', '#217DCB', 1, 27),
    ('BUSAN-GIMHAE', '부산김해경전철', 'Busan-Gimhae LRT', '#875CAC', 1, 28),
    ('DONGHAE', '동해선', 'Donghae Line', '#0054A6', 1, 29),
    ('DAEGU-1', '대구 1호선', 'Daegu Line 1', '#D93F5C', 1, 30),
    ('DAEGU-2', '대구 2호선', 'Daegu Line 2', '#00AA80', 1, 31),
    ('DAEGU-3', '대구 3호선', 'Daegu Line 3', '#FFB100', 1, 32),
    ('DAEGYEONG', '대경선', 'Daegyeong Line', '#0054A6', 1, 33),
    ('GWANGJU-1', '광주 1호선', 'Gwangju Line 1', '#009088', 1, 34),
    ('DAEJEON-1', '대전 1호선', 'Daejeon Line 1', '#007448', 1, 35)
ON DUPLICATE KEY UPDATE name_ko    = VALUES(name_ko),
                        name_en    = VALUES(name_en),
                        color      = VALUES(color),
                        is_active  = VALUES(is_active),
                        sort_order = VALUES(sort_order);

UPDATE subway_lines
SET is_active = 0
WHERE is_active = 1
  AND code NOT IN ('SEL-1', 'SEL-2', 'SEL-3', 'SEL-4', 'SEL-5', 'SEL-6', 'SEL-7', 'SEL-8', 'SEL-9', 'GYEONGUI-JUNGANG', 'AREX', 'SUIN-BUNDANG', 'SHINBUNDANG', 'GYEONGCHUN', 'GYEONGGANG', 'SEOHAE', 'UI-SINSEOL', 'SILLIM', 'GIMPO-GOLD', 'UIJEONGBU', 'EVERLINE', 'ICN-1', 'ICN-2', 'BUSAN-1', 'BUSAN-2', 'BUSAN-3', 'BUSAN-4', 'BUSAN-GIMHAE', 'DONGHAE', 'DAEGU-1', 'DAEGU-2', 'DAEGU-3', 'DAEGYEONG', 'GWANGJU-1', 'DAEJEON-1');

INSERT INTO subway_stations (code, name_ko, name_en, lat, lng, region_id, is_active)
VALUES
    ('ST0001', '가산디지털단지역', 'Gasan Digital Complex', 37.4809720, 126.8826660, (SELECT id FROM regions WHERE code = '11545'), 1),
    ('ST0002', '개봉역', 'Gaebong', 37.4946980, 126.8585040, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0003', '광운대역', 'Kwangwoon Univ.', 37.6236025, 127.0618925, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0004', '구로역', 'Guro', 37.5031780, 126.8820370, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0005', '구일역', 'Guil', 37.4959730, 126.8692010, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0006', '금천구청역', 'Geumcheon-gu Office', 37.4557600, 126.8943440, (SELECT id FROM regions WHERE code = '11545'), 1),
    ('ST0007', '남영역', 'Namyeong', 37.5405970, 126.9711710, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0008', '노량진역', 'Noryangjin', 37.5138695, 126.9412940, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0009', '녹천역', 'Nokcheon', 37.6445990, 127.0514060, (SELECT id FROM regions WHERE code = '11320'), 1),
    ('ST0010', '대방역', 'Daebang', 37.5131161, 126.9258619, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0011', '도봉역', 'Dobong', 37.6794410, 127.0455210, (SELECT id FROM regions WHERE code = '11320'), 1),
    ('ST0012', '도봉산역', 'Dobongsan', 37.6893670, 127.0462975, (SELECT id FROM regions WHERE code = '11320'), 1),
    ('ST0013', '독산역', 'Doksan', 37.4659940, 126.8895080, (SELECT id FROM regions WHERE code = '11545'), 1),
    ('ST0014', '동대문역', 'Dongdaemun', 37.5713180, 127.0099700, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0015', '동묘앞역', 'Dongmyo', 37.5727700, 127.0160625, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0016', '방학역', 'Banghak', 37.6674710, 127.0442950, (SELECT id FROM regions WHERE code = '11320'), 1),
    ('ST0017', '서울역', 'Seoul Station', 37.5546882, 126.9714901, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0018', '석계역', 'Seokgye', 37.6150715, 127.0657580, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0019', '시청역', 'City Hall', 37.5643725, 126.9762390, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0020', '신길역', 'Singil', 37.5173240, 126.9163145, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0021', '신도림역', 'Sindorim', 37.5088990, 126.8913675, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0022', '신설동역', 'Sinseoldong', 37.5757867, 127.0243630, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0023', '신이문역', 'Sinimun', 37.6018290, 127.0673470, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0024', '영등포역', 'Yeongdeungpo', 37.5157560, 126.9074110, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0025', '오류동역', 'Oryu-dong', 37.4944000, 126.8445510, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0026', '온수역', 'Onsu', 37.4921630, 126.8233465, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0027', '외대앞역', 'Hankuk Univ. of Foreign Studies', 37.5961570, 127.0635720, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0028', '용산역', 'Yongsan', 37.5298900, 126.9647750, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0029', '월계역', 'Wolgye', 37.6331140, 127.0588350, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0030', '제기동역', 'Jegidong', 37.5781160, 127.0349020, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0031', '종각역', 'Jonggak', 37.5702030, 126.9831160, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0032', '종로3가역', 'Jongno 3(sam)ga', 37.5715040, 126.9914597, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0033', '종로5가역', 'Jongno 5(o)ga', 37.5709710, 127.0019000, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0034', '창동역', 'Changdong', 37.6532480, 127.0479835, (SELECT id FROM regions WHERE code = '11320'), 1),
    ('ST0035', '청량리역', 'Cheongnyangni', 37.5802447, 127.0469093, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0036', '회기역', 'Hoegi', 37.5897020, 127.0579360, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0037', '강남역', 'Gangnam', 37.4977177, 127.0279145, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0038', '강변역', 'Gangbyeon', 37.5351610, 127.0946840, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0039', '건대입구역', 'Konkuk Univ.', 37.5406450, 127.0701670, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0040', '교대역', 'Seoul Nat''l Univ. of Education', 37.4935085, 127.0141135, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0041', '구로디지털단지역', 'Guro Digital Complex', 37.4852931, 126.9014560, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0042', '구의역', 'Guui', 37.5371570, 127.0862124, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0043', '까치산역', 'Kkachisan', 37.5322000, 126.8464500, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0044', '낙성대역', 'Nakseongdae', 37.4771300, 126.9635830, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0045', '당산역', 'Dangsan', 37.5343255, 126.9024165, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0046', '대림역', 'Daerim', 37.4931333, 126.8959882, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0047', '도림천역', 'Dorimcheon', 37.5145589, 126.8825860, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0048', '동대문역사문화공원역', 'Dongdaemun History & Culture Park', 37.5651440, 127.0074070, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0049', '뚝섬역', 'Ttukseom', 37.5471800, 127.0474130, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0050', '문래역', 'Mullae', 37.5179930, 126.8947660, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0051', '방배역', 'Bangbae', 37.4814690, 126.9976270, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0052', '봉천역', 'Bongcheon', 37.4824160, 126.9416960, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0053', '사당역', 'Sadang', 37.4763995, 126.9816335, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0054', '삼성역', 'Samseong', 37.5088270, 127.0632030, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0055', '상왕십리역', 'Sangwangsimni', 37.5644440, 127.0293220, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0056', '서울대입구역', 'Seoul Nat''l Univ.', 37.4812330, 126.9527450, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0057', '서초역', 'Seocho', 37.4919100, 127.0076850, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0058', '선릉역', 'Seolleung', 37.5043680, 127.0485575, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0059', '성수역', 'Seongsu', 37.5446280, 127.0559830, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0060', '신답역', 'Sindap', 37.5698470, 127.0470480, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0061', '신당역', 'Sindang', 37.5659245, 127.0177920, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0062', '신대방역', 'Sindaebang', 37.4876040, 126.9134790, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0063', '신림역', 'Sillim', 37.4845534, 126.9295915, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0064', '신정네거리역', 'Sinjeongnegeori', 37.5202180, 126.8528490, (SELECT id FROM regions WHERE code = '11470'), 1),
    ('ST0065', '신촌역', 'Sinchon', 37.5551530, 126.9368900, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0066', '아현역', 'Ahyeon', 37.5574070, 126.9560790, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0067', '양천구청역', 'Yangcheon-gu Office', 37.5123294, 126.8655930, (SELECT id FROM regions WHERE code = '11470'), 1),
    ('ST0068', '역삼역', 'Yeoksam', 37.5006580, 127.0364300, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0069', '영등포구청역', 'Yeongdeungpo-gu Office', 37.5249875, 126.8958245, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0070', '왕십리역', 'Wangsimni', 37.5615000, 127.0378589, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0071', '용답역', 'Yongdap', 37.5620660, 127.0508786, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0072', '용두역', 'Yongdu', 37.5740120, 127.0381100, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0073', '을지로3가역', 'Euljiro 3(sam)ga', 37.5662955, 126.9917445, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0074', '을지로4가역', 'Euljiro 4(sa)ga', 37.5669455, 126.9978745, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0075', '을지로입구역', 'Euljiro 1(il)ga', 37.5659980, 126.9825690, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0076', '이대역', 'Ewha Womans Univ.', 37.5567340, 126.9458970, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0077', '잠실역', 'Jamsil', 37.5139770, 127.1021980, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0078', '잠실나루역', 'Jamsillaru', 37.5206880, 127.1038360, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0079', '잠실새내역', 'Jamsilsaenae', 37.5116530, 127.0862240, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0080', '종합운동장역', 'Sports Complex', 37.5111420, 127.0753790, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0081', '충정로역', 'Chungjeongno', 37.5599015, 126.9636190, (SELECT id FROM regions WHERE code = '11410'), 1),
    ('ST0082', '한양대역', 'Hanyang Univ.', 37.5557580, 127.0436594, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0083', '합정역', 'Hapjeong', 37.5495290, 126.9140515, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0084', '홍대입구역', 'Hongik Univ.', 37.5573563, 126.9256566, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0085', '가락시장역', 'Garak Market', 37.4926610, 127.1179895, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0086', '경복궁역', 'Gyeongbokgung', 37.5758440, 126.9735760, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0087', '경찰병원역', 'National Police Hospital', 37.4957540, 127.1241980, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0088', '고속터미널역', 'Express Bus Terminal', 37.5047577, 127.0046717, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0089', '구파발역', 'Gupabal', 37.6366120, 126.9188270, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0090', '금호역', 'Geumho', 37.5482690, 127.0157850, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0091', '남부터미널역', 'Nambu Bus Terminal', 37.4849400, 127.0162890, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0092', '녹번역', 'Nokbeon', 37.6008820, 126.9357580, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0093', '대청역', 'Daecheong', 37.4936070, 127.0795260, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0094', '대치역', 'Daechi', 37.4946010, 127.0634490, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0095', '도곡역', 'Dogok', 37.4910215, 127.0555435, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0096', '독립문역', 'Dongnimmun', 37.5745340, 126.9579020, (SELECT id FROM regions WHERE code = '11410'), 1),
    ('ST0097', '동대입구역', 'Dongguk Univ.', 37.5590600, 127.0052730, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0098', '매봉역', 'Maebong', 37.4871140, 127.0469070, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0099', '무악재역', 'Muakjae', 37.5826580, 126.9501310, (SELECT id FROM regions WHERE code = '11410'), 1),
    ('ST0100', '불광역', 'Bulgwang', 37.6105365, 126.9298435, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0101', '수서역', 'Suseo', 37.4875665, 127.1015210, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0102', '신사역', 'Sinsa', 37.5162816, 127.0200040, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0103', '안국역', 'Anguk', 37.5765620, 126.9854700, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0104', '압구정역', 'Apgujeong', 37.5261690, 127.0285020, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0105', '약수역', 'Yaksu', 37.5543205, 127.0105475, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0106', '양재역', 'Yangjae', 37.4845168, 127.0341647, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0107', '연신내역', 'Yeonsinnae', 37.6188890, 126.9206810, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0108', '오금역', 'Ogeum', 37.5022580, 127.1280225, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0109', '옥수역', 'Oksu', 37.5410835, 127.0180005, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0110', '일원역', 'Irwon', 37.4838900, 127.0841600, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0111', '잠원역', 'Jamwon', 37.5129890, 127.0116130, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0112', '충무로역', 'Chungmuro', 37.5613670, 126.9943730, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0113', '학여울역', 'Hangnyeoul', 37.4967870, 127.0708601, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0114', '홍제역', 'Hongje', 37.5888510, 126.9440920, (SELECT id FROM regions WHERE code = '11410'), 1),
    ('ST0115', '길음역', 'Gireum', 37.6033870, 127.0250530, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0116', '남태령역', 'Namtaeryeong', 37.4643390, 126.9890810, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0117', '노원역', 'Nowon', 37.6553760, 127.0618690, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0118', '동작역', 'Dongjak', 37.5032225, 126.9792245, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0119', '명동역', 'Myeongdong', 37.5609550, 126.9862710, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0120', '미아역', 'Mia', 37.6264350, 127.0261510, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0121', '미아사거리역', 'Miasageori', 37.6132760, 127.0300830, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0122', '불암산역', 'Buramsan', 37.6700900, 127.0790040, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0123', '삼각지역', 'Samgakji', 37.5350100, 126.9734940, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0124', '상계역', 'Sanggye', 37.6605760, 127.0731990, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0125', '성신여대입구역', 'Sungshin Women''s Univ.', 37.5926760, 127.0167340, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0126', '수유역', 'Suyu', 37.6377270, 127.0253710, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0127', '숙대입구역', 'Sookmyung Women''s Univ.', 37.5447834, 126.9721000, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0128', '신용산역', 'Sinyongsan', 37.5290900, 126.9678800, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0129', '쌍문역', 'Ssangmun', 37.6482740, 127.0343810, (SELECT id FROM regions WHERE code = '11320'), 1),
    ('ST0130', '이촌역', 'Ichon', 37.5224760, 126.9738160, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0131', '총신대입구(이수)역', 'Chongshin Univ. (Isu)', 37.4863895, 126.9820375, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0132', '한성대입구역', 'Hansung Univ.', 37.5883800, 127.0059510, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0133', '혜화역', 'Hyehwa', 37.5821160, 127.0019290, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0134', '회현역', 'Hoehyeon', 37.5586980, 126.9783460, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0135', '강동역', 'Gangdong', 37.5358100, 127.1324900, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0136', '강일역', 'Gangil', 37.5575210, 127.1760180, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0137', '개롱역', 'Gaerong', 37.4980970, 127.1348170, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0138', '개화산역', 'Gaehwasan', 37.5724580, 126.8068380, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0139', '거여역', 'Geoyeo', 37.4932080, 127.1439830, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0140', '고덕역', 'Godeok', 37.5550020, 127.1542140, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0141', '공덕역', 'Gongdeok', 37.5432546, 126.9516214, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0142', '광나루역', 'Gwangnaru', 37.5453010, 127.1034780, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0143', '광화문역', 'Gwanghwamun', 37.5716450, 126.9765680, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0144', '군자역', 'Gunja', 37.5571265, 127.0795215, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0145', '굽은다리역', 'Gubeundari', 37.5454420, 127.1428440, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0146', '길동역', 'Gildong', 37.5380220, 127.1400850, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0147', '김포공항역', 'Gimpo Int''l Airport', 37.5621539, 126.8020678, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0148', '답십리역', 'Dapsimni', 37.5668330, 127.0526600, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0149', '둔촌동역', 'Dunchondong', 37.5277870, 127.1362190, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0150', '마장역', 'Majang', 37.5660660, 127.0429210, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0151', '마천역', 'Macheon', 37.4949720, 127.1527840, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0152', '마포역', 'Mapo', 37.5397180, 126.9460430, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0153', '명일역', 'Myeongil', 37.5513170, 127.1440020, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0154', '목동역', 'Mokdong', 37.5260880, 126.8642960, (SELECT id FROM regions WHERE code = '11470'), 1),
    ('ST0155', '발산역', 'Balsan', 37.5586920, 126.8376330, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0156', '방이역', 'Bangi', 37.5087520, 127.1260540, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0157', '방화역', 'Banghwa', 37.5776690, 126.8128220, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0158', '상일동역', 'Sangildong', 37.5567140, 127.1663810, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0159', '서대문역', 'Seodaemun', 37.5658120, 126.9666390, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0160', '송정역', 'Songjeong', 37.5612010, 126.8120520, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0161', '신금호역', 'Singeumho', 37.5545040, 127.0204030, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0162', '신정역', 'Sinjeong', 37.5250010, 126.8561760, (SELECT id FROM regions WHERE code = '11470'), 1),
    ('ST0163', '아차산역', 'Achasan', 37.5520050, 127.0896090, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0164', '애오개역', 'Aeogae', 37.5535920, 126.9567330, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0165', '양평역', 'Yangpyeong', 37.5256140, 126.8861770, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0166', '여의나루역', 'Yeouinaru', 37.5270450, 126.9328070, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0167', '여의도역', 'Yeouido', 37.5217110, 126.9242425, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0168', '영등포시장역', 'Yeongdeungpo Market', 37.5227600, 126.9051430, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0169', '오목교역', 'Omokgyo', 37.5245570, 126.8750490, (SELECT id FROM regions WHERE code = '11470'), 1),
    ('ST0170', '올림픽공원역', 'Olympic Park', 37.5161640, 127.1306450, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0171', '우장산역', 'Ujangsan', 37.5488640, 126.8363300, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0172', '장한평역', 'Janghanpyeong', 37.5614380, 127.0646010, (SELECT id FROM regions WHERE code = '11230'), 1),
    ('ST0173', '천호역', 'Cheonho', 37.5382430, 127.1233590, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0174', '청구역', 'Cheonggu', 37.5602290, 127.0137830, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0175', '행당역', 'Haengdang', 37.5572970, 127.0294820, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0176', '화곡역', 'Hwagok', 37.5415850, 126.8404360, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0177', '고려대역', 'Korea Univ.', 37.5903400, 127.0362600, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0178', '광흥창역', 'Gwangheungchang', 37.5474640, 126.9319710, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0179', '구산역', 'Gusan', 37.6111960, 126.9172519, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0180', '녹사평역', 'Noksapyeong', 37.5346900, 126.9866500, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0181', '대흥역', 'Daeheung', 37.5477320, 126.9422140, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0182', '독바위역', 'Dokbawi', 37.6184130, 126.9330350, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0183', '돌곶이역', 'Dolgoji', 37.6105220, 127.0564190, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0184', '디지털미디어시티역', 'Digital Media City', 37.5771332, 126.8999297, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0185', '마포구청역', 'Mapo-gu Office', 37.5635290, 126.9033560, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0186', '망원역', 'Mangwon', 37.5560310, 126.9101290, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0187', '버티고개역', 'Beotigogae', 37.5479330, 127.0069480, (SELECT id FROM regions WHERE code = '11140'), 1),
    ('ST0188', '보문역', 'Bomun', 37.5854680, 127.0195560, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0189', '봉화산역', 'Bonghwasan', 37.6174290, 127.0912326, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0190', '상수역', 'Sangsu', 37.5477040, 126.9229200, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0191', '상월곡역', 'Sangwolgok', 37.6063920, 127.0485090, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0192', '새절역', 'Saejeol', 37.5911480, 126.9136130, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0193', '신내역', 'Sinnae', 37.6127130, 127.1038015, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0194', '안암역', 'Anam', 37.5862610, 127.0290300, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0195', '역촌역', 'Yeokchon', 37.6060500, 126.9227640, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0196', '월곡역', 'Wolgok', 37.6019200, 127.0414920, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0197', '월드컵경기장역', 'World Cup Stadium', 37.5694390, 126.8990770, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0198', '응암역', 'Eungam', 37.5985900, 126.9155830, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0199', '이태원역', 'Itaewon', 37.5344850, 126.9943690, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0200', '증산역', 'Jeungsan', 37.5839890, 126.9097850, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0201', '창신역', 'Changsin', 37.5797720, 127.0152460, (SELECT id FROM regions WHERE code = '11110'), 1),
    ('ST0202', '태릉입구역', 'Taereung', 37.6179190, 127.0750510, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0203', '한강진역', 'Hangangjin', 37.5395600, 127.0017290, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0204', '화랑대역', 'Hwarangdae', 37.6198750, 127.0841060, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0205', '효창공원앞역', 'Hyochang Park', 37.5390340, 126.9618435, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0206', '강남구청역', 'Gangnam-gu Office', 37.5168785, 127.0413975, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0207', '공릉역', 'Gongneung', 37.6256420, 127.0729690, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0208', '남구로역', 'Namguro', 37.4861810, 126.8873720, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0209', '남성역', 'Namseong', 37.4846880, 126.9711080, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0210', '내방역', 'Naebang', 37.4876400, 126.9935410, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0211', '논현역', 'Nonhyeon', 37.5111117, 127.0213754, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0212', '마들역', 'Madeul', 37.6649850, 127.0577010, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0213', '먹골역', 'Meokgol', 37.6106380, 127.0777190, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0214', '면목역', 'Myeonmok', 37.5886710, 127.0875030, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0215', '반포역', 'Banpo', 37.5081710, 127.0117170, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0216', '보라매역', 'Boramae', 37.5001144, 126.9202989, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0217', '사가정역', 'Sagajeong', 37.5809120, 127.0885020, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0218', '상도역', 'Sangdo', 37.5027900, 126.9479490, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0219', '상봉역', 'Sangbong', 37.5958937, 127.0857047, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0220', '수락산역', 'Suraksan', 37.6778040, 127.0553140, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0221', '숭실대입구역', 'Soongsil Univ.', 37.4962580, 126.9536490, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0222', '신대방삼거리역', 'Sindaebangsamgeori', 37.4997170, 126.9282180, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0223', '신풍역', 'Sinpung', 37.5001070, 126.9098060, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0224', '어린이대공원역', 'Children''s Grand Park', 37.5479620, 127.0746500, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0225', '용마산역', 'Yongmasan', 37.5737520, 127.0868020, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0226', '자양역', 'Jayang', 37.5315580, 127.0667140, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0227', '장승배기역', 'Jangseungbaegi', 37.5048450, 126.9390250, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0228', '중계역', 'Junggye', 37.6450520, 127.0640840, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0229', '중곡역', 'Junggok', 37.5658770, 127.0842910, (SELECT id FROM regions WHERE code = '11215'), 1),
    ('ST0230', '중화역', 'Junghwa', 37.6026040, 127.0792540, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0231', '천왕역', 'Cheonwang', 37.4866990, 126.8386840, (SELECT id FROM regions WHERE code = '11530'), 1),
    ('ST0232', '청담역', 'Cheongdam', 37.5190970, 127.0518510, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0233', '하계역', 'Hagye', 37.6363630, 127.0679990, (SELECT id FROM regions WHERE code = '11350'), 1),
    ('ST0234', '학동역', 'Hakdong', 37.5142620, 127.0317380, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0235', '강동구청역', 'Gangdong-gu Office', 37.5303480, 127.1204610, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0236', '몽촌토성역', 'Mongchontoseong', 37.5176920, 127.1127400, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0237', '문정역', 'Munjeong', 37.4859310, 127.1224730, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0238', '복정역', 'Bokjeong', 37.4703845, 127.1266865, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0239', '석촌역', 'Seokchon', 37.5052370, 127.1069695, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0240', '송파역', 'Songpa', 37.4997800, 127.1121200, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0241', '암사역', 'Amsa', 37.5501270, 127.1275210, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0242', '암사역사공원역', 'Amsa History Park', 37.5571670, 127.1375560, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0243', '장지역', 'Jangji', 37.4786090, 127.1262290, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0244', '가양역', 'Gayang', 37.5611110, 126.8550780, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0245', '개화역', 'Gaehwa', 37.5785910, 126.7978330, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0246', '공항시장역', 'Airport Market', 37.5637260, 126.8106780, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0247', '구반포역', 'Gubanpo', 37.5013890, 126.9872220, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0248', '국회의사당역', 'National Assembly', 37.5281050, 126.9178740, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0249', '노들역', 'Nodeul', 37.5127260, 126.9530960, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0250', '둔촌오륜역', 'Dunchon Oryun', 37.5193390, 127.1386190, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0251', '등촌역', 'Deungchon', 37.5511110, 126.8647220, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0252', '마곡나루역', 'Magongnaru', 37.5662681, 126.8276215, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0253', '봉은사역', 'Bongeunsa', 37.5142190, 127.0602450, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0254', '사평역', 'Sapyeong', 37.5041610, 127.0152190, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0255', '삼성중앙역', 'Samseongjungang', 37.5130110, 127.0532820, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0256', '삼전역', 'Samjeon', 37.5045310, 127.0872110, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0257', '샛강역', 'Saetgang', 37.5172600, 126.9289020, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0258', '석촌고분역', 'Seokchon Gobun', 37.5024390, 127.0966110, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0259', '선유도역', 'Seonyudo', 37.5377780, 126.8938890, (SELECT id FROM regions WHERE code = '11560'), 1),
    ('ST0260', '선정릉역', 'Seonjeongneung', 37.5105275, 127.0437382, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0261', '송파나루역', 'Songpanaru', 37.5105500, 127.1124510, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0262', '신논현역', 'Sinnonhyeon', 37.5044997, 127.0244738, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0263', '신목동역', 'Sinmokdong', 37.5441670, 126.8830560, (SELECT id FROM regions WHERE code = '11470'), 1),
    ('ST0264', '신반포역', 'Sinbanpo', 37.5035033, 126.9960440, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0265', '신방화역', 'Sinbanghwa', 37.5675000, 126.8166670, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0266', '양천향교역', 'Yangcheon Hyanggyo', 37.5683330, 126.8413890, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0267', '언주역', 'Eonju', 37.5072870, 127.0338680, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0268', '염창역', 'Yeomchang', 37.5469440, 126.8747220, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0269', '중앙보훈병원역', 'VHS Medical Center', 37.5288070, 127.1484880, (SELECT id FROM regions WHERE code = '11740'), 1),
    ('ST0270', '증미역', 'Jeungmi', 37.5578500, 126.8610670, (SELECT id FROM regions WHERE code = '11500'), 1),
    ('ST0271', '한성백제역', 'Hanseong Baekje', 37.5163690, 127.1162110, (SELECT id FROM regions WHERE code = '11710'), 1),
    ('ST0272', '흑석역', 'Heukseok', 37.5088890, 126.9636110, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0273', '가좌역', 'Gajwa', 37.5686110, 126.9148410, (SELECT id FROM regions WHERE code = '11410'), 1),
    ('ST0274', '망우역', 'Mangu', 37.5996380, 127.0921745, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0275', '서강대역', 'Sogang Univ', 37.5521320, 126.9353890, (SELECT id FROM regions WHERE code = '11440'), 1),
    ('ST0276', '서빙고역', 'Seobinggo', 37.5196100, 126.9883580, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0277', '수색역', 'Susaek', 37.5808550, 126.8956810, (SELECT id FROM regions WHERE code = '11380'), 1),
    ('ST0278', '신촌역', 'Sinchon', 37.5597680, 126.9423080, (SELECT id FROM regions WHERE code = '11410'), 1),
    ('ST0279', '응봉역', 'Eungbong', 37.5504330, 127.0348240, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0280', '중랑역', 'Jungnang', 37.5949480, 127.0761520, (SELECT id FROM regions WHERE code = '11260'), 1),
    ('ST0281', '한남역', 'Hannam', 37.5293610, 127.0089840, (SELECT id FROM regions WHERE code = '11170'), 1),
    ('ST0282', '개포동역', 'Gaepo-dong', 37.4891120, 127.0660270, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0283', '구룡역', 'Guryong', 37.4869850, 127.0594960, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0284', '대모산입구역', 'Daemosan', 37.4913470, 127.0727020, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0285', '서울숲역', 'Seoul Forest', 37.5436470, 127.0446860, (SELECT id FROM regions WHERE code = '11200'), 1),
    ('ST0286', '압구정로데오역', 'Apgujeongrodeo', 37.5273850, 127.0405550, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0287', '한티역', 'Hanti', 37.4962080, 127.0528830, (SELECT id FROM regions WHERE code = '11680'), 1),
    ('ST0288', '양재시민의숲역', 'Yangjae Citizen''s Forest', 37.4709639, 127.0384203, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0289', '청계산입구역', 'Cheonggyesan', 37.4487148, 127.0543396, (SELECT id FROM regions WHERE code = '11650'), 1),
    ('ST0290', '4.19민주묘지역', 'April 19th National Cemetery', 37.6495400, 127.0137100, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0291', '가오리역', 'Gaori', 37.6416200, 127.0168100, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0292', '북한산보국문역', 'Bukhansan Bogungmun', 37.6123300, 127.0081600, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0293', '북한산우이역', 'BukhansanUI', 37.6628400, 127.0127200, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0294', '삼양역', 'Samyang', 37.6270600, 127.0181700, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0295', '삼양사거리역', 'Samyang Sageori', 37.6214400, 127.0204900, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0296', '솔밭공원역', 'Solbat Park', 37.6560700, 127.0132600, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0297', '솔샘역', 'Solsaem', 37.6203700, 127.0135700, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0298', '정릉역', 'Jeongneung', 37.6027800, 127.0134500, (SELECT id FROM regions WHERE code = '11290'), 1),
    ('ST0299', '화계역', 'Hwagye', 37.6342800, 127.0175000, (SELECT id FROM regions WHERE code = '11305'), 1),
    ('ST0300', '관악산역', 'Gwanaksan', 37.4691690, 126.9449548, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0301', '당곡역', 'Danggok', 37.4900391, 126.9275688, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0302', '보라매공원역', 'Boramae Park', 37.4952254, 126.9182588, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0303', '보라매병원역', 'Boramae Medical Center', 37.4929607, 126.9238860, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0304', '서울대벤처타운역', 'Seoul National Univ. Venture Town', 37.4720407, 126.9339766, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0305', '서울지방병무청역', 'Seoul Regional Office of Military Manpower', 37.5060469, 126.9227834, (SELECT id FROM regions WHERE code = '11590'), 1),
    ('ST0306', '서원역', 'Seowon', 37.4782734, 126.9332282, (SELECT id FROM regions WHERE code = '11620'), 1),
    ('ST0307', '가능역', 'Ganeung', 37.7483620, 127.0442510, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0308', '관악역', 'Gwanak', 37.4195780, 126.9084760, (SELECT id FROM regions WHERE code = '41171'), 1),
    ('ST0309', '광명역', 'Gwangmyeong', 37.4159950, 126.8845230, (SELECT id FROM regions WHERE code = '41210'), 1),
    ('ST0310', '군포역', 'Gunpo', 37.3537320, 126.9485170, (SELECT id FROM regions WHERE code = '41410'), 1),
    ('ST0311', '금정역', 'Geumjeong', 37.3719570, 126.9435110, (SELECT id FROM regions WHERE code = '41410'), 1),
    ('ST0312', '녹양역', 'Nogyang', 37.7594160, 127.0422720, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0313', '당정역', 'Dangjeong', 37.3433800, 126.9483880, (SELECT id FROM regions WHERE code = '41410'), 1),
    ('ST0314', '덕계역', 'Deokgye', 37.8187610, 127.0566760, (SELECT id FROM regions WHERE code = '41630'), 1),
    ('ST0315', '덕정역', 'Deokjeong', 37.8432160, 127.0615110, (SELECT id FROM regions WHERE code = '41630'), 1),
    ('ST0316', '동두천역', 'Dongducheon', 37.9275690, 127.0548250, (SELECT id FROM regions WHERE code = '41250'), 1),
    ('ST0317', '동두천중앙역', 'Dongducheon Jungang', 37.9018180, 127.0565280, (SELECT id FROM regions WHERE code = '41250'), 1),
    ('ST0318', '망월사역', 'Mangwolsa', 37.7104990, 127.0473610, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0319', '명학역', 'Myeonghak', 37.3844350, 126.9355790, (SELECT id FROM regions WHERE code = '41171'), 1),
    ('ST0320', '병점역', 'Byeongjeom', 37.2068210, 127.0332680, NULL, 1),
    ('ST0321', '보산역', 'Bosan', 37.9142680, 127.0571340, (SELECT id FROM regions WHERE code = '41250'), 1),
    ('ST0322', '부천역', 'Bucheon', 37.4840660, 126.7827790, (SELECT id FROM regions WHERE code = '41194'), 1),
    ('ST0323', '서동탄역', 'Seodongtan', 37.1956750, 127.0518930, (SELECT id FROM regions WHERE code = '41370'), 1),
    ('ST0324', '서정리역', 'Seojeong-ri', 37.0564590, 127.0528440, (SELECT id FROM regions WHERE code = '41220'), 1),
    ('ST0325', '석수역', 'Seoksu', 37.4351610, 126.9022330, (SELECT id FROM regions WHERE code = '41171'), 1),
    ('ST0326', '성균관대역', 'Sungkyunkwan Univ.', 37.3003440, 126.9708610, (SELECT id FROM regions WHERE code = '41111'), 1),
    ('ST0327', '세류역', 'Seryu', 37.2441020, 127.0137720, (SELECT id FROM regions WHERE code = '41113'), 1),
    ('ST0328', '세마역', 'Sema', 37.1872880, 127.0432790, (SELECT id FROM regions WHERE code = '41370'), 1),
    ('ST0329', '소사역', 'Sosa', 37.4827550, 126.7953597, (SELECT id FROM regions WHERE code = '41194'), 1),
    ('ST0330', '소요산역', 'Soyosan', 37.9486080, 127.0610070, (SELECT id FROM regions WHERE code = '41250'), 1),
    ('ST0331', '송내역', 'Songnae', 37.4876260, 126.7530450, (SELECT id FROM regions WHERE code = '41194'), 1),
    ('ST0332', '송탄역', 'Songtan', 37.0753760, 127.0543970, (SELECT id FROM regions WHERE code = '41220'), 1),
    ('ST0333', '수원역', 'Suwon', 37.2661620, 126.9999515, (SELECT id FROM regions WHERE code = '41115'), 1),
    ('ST0334', '안양역', 'Anyang', 37.4019290, 126.9226470, (SELECT id FROM regions WHERE code = '41171'), 1),
    ('ST0335', '양주역', 'Yangju', 37.7742810, 127.0447190, (SELECT id FROM regions WHERE code = '41630'), 1),
    ('ST0336', '역곡역', 'Yeokgok', 37.4851400, 126.8116220, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0337', '연천역', 'Yeoncheon', 38.1004850, 127.0736800, (SELECT id FROM regions WHERE code = '41800'), 1),
    ('ST0338', '오산역', 'Osan', 37.1454720, 127.0667080, (SELECT id FROM regions WHERE code = '41370'), 1),
    ('ST0339', '오산대역', 'Osan College', 37.1694010, 127.0630810, (SELECT id FROM regions WHERE code = '41370'), 1),
    ('ST0340', '의왕역', 'Uiwang', 37.3206480, 126.9480990, (SELECT id FROM regions WHERE code = '41430'), 1),
    ('ST0341', '의정부역', 'Uijeongbu', 37.7387300, 127.0458910, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0342', '전곡역', 'Jeongok', 38.0240410, 127.0719130, (SELECT id FROM regions WHERE code = '41800'), 1),
    ('ST0343', '중동역', 'Jung-dong', 37.4866640, 126.7644740, (SELECT id FROM regions WHERE code = '41194'), 1),
    ('ST0344', '지행역', 'Jihaeng', 37.8923610, 127.0557340, (SELECT id FROM regions WHERE code = '41250'), 1),
    ('ST0345', '진위역', 'Jinwi', 37.1097710, 127.0623420, (SELECT id FROM regions WHERE code = '41220'), 1),
    ('ST0346', '청산역', 'Cheongsan', 37.9951380, 127.0740970, (SELECT id FROM regions WHERE code = '41800'), 1),
    ('ST0347', '평택역', 'Pyeongtaek', 36.9905590, 127.0854960, (SELECT id FROM regions WHERE code = '41220'), 1),
    ('ST0348', '평택지제역', 'PyeongtaekJije', 37.0188220, 127.0702690, (SELECT id FROM regions WHERE code = '41220'), 1),
    ('ST0349', '화서역', 'Hwaseo', 37.2839900, 126.9895810, (SELECT id FROM regions WHERE code = '41115'), 1),
    ('ST0350', '회룡역', 'Hoeryong', 37.7245448, 127.0470527, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0351', '대곡역', 'Daegok', 37.6316290, 126.8103507, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0352', '대화역', 'Daehwa', 37.6758830, 126.7477560, (SELECT id FROM regions WHERE code = '41287'), 1),
    ('ST0353', '마두역', 'Madu', 37.6521850, 126.7776410, (SELECT id FROM regions WHERE code = '41285'), 1),
    ('ST0354', '백석역', 'Baekseok', 37.6429730, 126.7881090, (SELECT id FROM regions WHERE code = '41285'), 1),
    ('ST0355', '삼송역', 'Samsong', 37.6530960, 126.8955590, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0356', '원당역', 'Wondang', 37.6531030, 126.8428910, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0357', '원흥역', 'Wonheung', 37.6507090, 126.8732390, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0358', '정발산역', 'Jeongbalsan', 37.6597090, 126.7732540, (SELECT id FROM regions WHERE code = '41285'), 1),
    ('ST0359', '주엽역', 'Juyeop', 37.6701380, 126.7612320, (SELECT id FROM regions WHERE code = '41287'), 1),
    ('ST0360', '지축역', 'Jichuk', 37.6481325, 126.9137485, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0361', '화정역', 'Hwajeong', 37.6347080, 126.8325030, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0362', '경마공원역', 'Seoul Racecourse Park', 37.4439870, 127.0078990, (SELECT id FROM regions WHERE code = '41290'), 1),
    ('ST0363', '고잔역', 'Gojan', 37.3168210, 126.8230940, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0364', '과천역', 'Gwacheon', 37.4328050, 126.9964040, (SELECT id FROM regions WHERE code = '41290'), 1),
    ('ST0365', '능길역', 'Neunggil', 37.3374880, 126.7673060, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0366', '대공원역', 'Seoul Grand Park', 37.4357240, 127.0064740, (SELECT id FROM regions WHERE code = '41290'), 1),
    ('ST0367', '대야미역', 'Daeyami', 37.3283040, 126.9171610, (SELECT id FROM regions WHERE code = '41410'), 1),
    ('ST0368', '반월역', 'Banwol', 37.3122440, 126.9036840, (SELECT id FROM regions WHERE code = '41271'), 1),
    ('ST0369', '범계역', 'Beomgye', 37.3897830, 126.9507520, (SELECT id FROM regions WHERE code = '41173'), 1),
    ('ST0370', '별내별가람역', 'ByeollaeByeolgaram', 37.6678330, 127.1161670, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0371', '산본역', 'Sanbon', 37.3580190, 126.9329690, (SELECT id FROM regions WHERE code = '41410'), 1),
    ('ST0372', '상록수역', 'Sangnoksu', 37.3028450, 126.8665330, (SELECT id FROM regions WHERE code = '41271'), 1),
    ('ST0373', '선바위역', 'Seonbawi', 37.4518540, 127.0021640, (SELECT id FROM regions WHERE code = '41290'), 1),
    ('ST0374', '수리산역', 'Surisan', 37.3502970, 126.9255370, (SELECT id FROM regions WHERE code = '41410'), 1),
    ('ST0375', '안산역', 'Ansan', 37.3270060, 126.7887480, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0376', '오남역', 'Onam', 37.7054056, 127.1916440, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0377', '오이도역', 'Oido', 37.3617730, 126.7384370, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0378', '인덕원역', 'Indeogwon', 37.4019010, 126.9767180, (SELECT id FROM regions WHERE code = '41173'), 1),
    ('ST0379', '정부과천청사역', 'Government Complex Gwacheon', 37.4264450, 126.9898020, (SELECT id FROM regions WHERE code = '41290'), 1),
    ('ST0380', '정왕역', 'Jeongwang', 37.3518160, 126.7428440, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0381', '중앙역', 'Jungang', 37.3160550, 126.8385320, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0382', '진접역', 'Jinjeop', 37.7204720, 127.2031670, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0383', '초지역', 'Choji', 37.3204163, 126.8067843, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0384', '평촌역', 'Pyeongchon', 37.3943460, 126.9638810, (SELECT id FROM regions WHERE code = '41173'), 1),
    ('ST0385', '한대앞역', 'Hanyang Univ. at Ansan', 37.3098350, 126.8535810, (SELECT id FROM regions WHERE code = '41271'), 1),
    ('ST0386', '미사역', 'Misa', 37.5630900, 127.1929540, (SELECT id FROM regions WHERE code = '41450'), 1),
    ('ST0387', '하남검단산역', 'Hanam Geomdansan', 37.5397290, 127.2234270, (SELECT id FROM regions WHERE code = '41450'), 1),
    ('ST0388', '하남시청역', 'Hanam City Hall', 37.5417230, 127.2069010, (SELECT id FROM regions WHERE code = '41450'), 1),
    ('ST0389', '하남풍산역', 'Hanam Pungsan', 37.5522010, 127.2038970, (SELECT id FROM regions WHERE code = '41450'), 1),
    ('ST0390', '광명사거리역', 'Gwangmyeongsageori', 37.4792700, 126.8548540, (SELECT id FROM regions WHERE code = '41210'), 1),
    ('ST0391', '까치울역', 'Kkachiul', 37.5062360, 126.8109500, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0392', '부천시청역', 'Bucheon City Hall', 37.5046110, 126.7635610, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0393', '부천종합운동장역', 'Bucheon Stadium', 37.5056165, 126.7974240, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0394', '상동역', 'Sang-dong', 37.5057690, 126.7530810, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0395', '신중동역', 'Sinjung-dong', 37.5030810, 126.7761610, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0396', '장암역', 'Jangam', 37.7001500, 127.0531260, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0397', '철산역', 'Cheolsan', 37.4761600, 126.8682170, (SELECT id FROM regions WHERE code = '41210'), 1),
    ('ST0398', '춘의역', 'Chunui', 37.5036500, 126.7870690, (SELECT id FROM regions WHERE code = '41192'), 1),
    ('ST0399', '구리역', 'Guri', 37.6029100, 127.1420515, (SELECT id FROM regions WHERE code = '41310'), 1),
    ('ST0400', '남위례역', 'Namwirye', 37.4628390, 127.1390470, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0401', '남한산성입구역', 'Namhansanseong', 37.4515680, 127.1598450, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0402', '다산역', 'Dasan', 37.6243700, 127.1498000, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0403', '단대오거리역', 'Dandaeogeori', 37.4450570, 127.1567350, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0404', '동구릉역', 'Donggureung', 37.6105560, 127.1350560, (SELECT id FROM regions WHERE code = '41310'), 1),
    ('ST0405', '모란역', 'Moran', 37.4330560, 127.1294925, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0406', '별내역', 'Byeollae', 37.6423690, 127.1272540, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0407', '산성역', 'Sanseong', 37.4568860, 127.1499270, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0408', '수진역', 'Sujin', 37.4375750, 127.1409360, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0409', '신흥역', 'Sinheung', 37.4409520, 127.1475900, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0410', '장자호수공원역', 'Jangja Lake Park', 37.5872220, 127.1379300, (SELECT id FROM regions WHERE code = '41310'), 1),
    ('ST0411', '강매역', 'Gangmae', 37.6123960, 126.8445370, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0412', '곡산역', 'Goksan', 37.6457290, 126.8018770, (SELECT id FROM regions WHERE code = '41285'), 1),
    ('ST0413', '국수역', 'Guksu', 37.5161710, 127.3994020, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0414', '금릉역', 'Geumneung', 37.7512950, 126.7654010, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0415', '금촌역', 'Geumchon', 37.7661060, 126.7745150, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0416', '능곡역', 'Neunggok', 37.6187410, 126.8207450, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0417', '덕소역', 'Deokso', 37.5868920, 127.2088350, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0418', '도농역', 'Donong', 37.6087560, 127.1611050, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0419', '도심역', 'Dosim', 37.5796280, 127.2227980, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0420', '문산역', 'Munsan', 37.8545650, 126.7875310, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0421', '백마역', 'Baengma', 37.6583460, 126.7944010, (SELECT id FROM regions WHERE code = '41285'), 1),
    ('ST0422', '신원역', 'Sinwon', 37.5259210, 127.3727450, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0423', '아신역', 'Asin', 37.5139540, 127.4432380, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0424', '야당역', 'Yadang', 37.7127490, 126.7615350, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0425', '양수역', 'Yangsu', 37.5459980, 127.3289480, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0426', '양정역', 'Yangjeong', 37.6042690, 127.1946020, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0427', '양평역', 'Yangpyeong', 37.4926740, 127.4918580, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0428', '오빈역', 'Obin', 37.5060610, 127.4740530, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0429', '용문역', 'Yongmun', 37.4820850, 127.5942510, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0430', '운길산역', 'Ungilsan', 37.5546590, 127.3100890, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0431', '운정역', 'Unjeong', 37.7254410, 126.7670760, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0432', '운천역', 'Uncheon', 37.8800080, 126.7697130, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0433', '원덕역', 'Wondeok', 37.4685850, 127.5474680, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0434', '월롱역', 'Wollong', 37.7962010, 126.7925740, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0435', '일산역', 'Ilsan', 37.6822310, 126.7695210, (SELECT id FROM regions WHERE code = '41287'), 1),
    ('ST0436', '임진강역', 'Imjingang', 37.8883640, 126.7467770, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0437', '지평역', 'Jipyeong', 37.4764960, 127.6296810, (SELECT id FROM regions WHERE code = '41830'), 1),
    ('ST0438', '탄현역', 'Tanhyeon', 37.6941140, 126.7610750, (SELECT id FROM regions WHERE code = '41287'), 1),
    ('ST0439', '파주역', 'Paju', 37.8153910, 126.7924260, (SELECT id FROM regions WHERE code = '41480'), 1),
    ('ST0440', '팔당역', 'Paldang', 37.5473210, 127.2438960, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0441', '풍산역', 'Pungsan', 37.6721730, 126.7863550, (SELECT id FROM regions WHERE code = '41285'), 1),
    ('ST0442', '한국항공대역', 'Korea Aerospace Univ.', 37.6031020, 126.8682910, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0443', '행신역', 'Haengsin', 37.6121250, 126.8340630, (SELECT id FROM regions WHERE code = '41281'), 1),
    ('ST0444', '가천대역', 'Gachon Univ.', 37.4487840, 127.1266770, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0445', '고색역', 'Gosaek', 37.2498290, 126.9805899, (SELECT id FROM regions WHERE code = '41113'), 1),
    ('ST0446', '구성역', 'Guseong', 37.2990130, 127.1056640, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0447', '기흥역', 'Giheung', 37.2755549, 127.1164953, NULL, 1),
    ('ST0448', '달월역', 'Darwol', 37.3799930, 126.7454310, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0449', '망포역', 'Mangpo', 37.2457930, 127.0573670, (SELECT id FROM regions WHERE code = '41117'), 1),
    ('ST0450', '매교역', 'Maegyo', 37.2655020, 127.0156570, (SELECT id FROM regions WHERE code = '41115'), 1),
    ('ST0451', '매탄권선역', 'Maetan Gwonseon', 37.2524580, 127.0408240, (SELECT id FROM regions WHERE code = '41117'), 1),
    ('ST0452', '미금역', 'Migeum', 37.3506307, 127.1089274, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0453', '보정역', 'Bojeong', 37.3127400, 127.1082190, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0454', '사리역', 'Sari', 37.2908090, 126.8571720, (SELECT id FROM regions WHERE code = '41271'), 1),
    ('ST0455', '상갈역', 'Sanggal', 37.2617970, 127.1087180, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0456', '서현역', 'Seohyeon', 37.3849310, 127.1232490, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0457', '수내역', 'Sunae', 37.3784350, 127.1142160, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0458', '수원시청역', 'Suwon City Hall', 37.2619410, 127.0307360, (SELECT id FROM regions WHERE code = '41113'), 1),
    ('ST0459', '신갈역', 'Singal', 37.2861270, 127.1113110, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0460', '야목역', 'Yamok', 37.2612110, 126.8840290, (SELECT id FROM regions WHERE code = '41593'), 1),
    ('ST0461', '야탑역', 'Yatap', 37.4113200, 127.1286610, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0462', '어천역', 'Eocheon', 37.2500760, 126.9088050, (SELECT id FROM regions WHERE code = '41593'), 1),
    ('ST0463', '영통역', 'Yeongtong', 37.2515210, 127.0713410, (SELECT id FROM regions WHERE code = '41117'), 1),
    ('ST0464', '오리역', 'Ori', 37.3398320, 127.1089850, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0465', '오목천역', 'Omokcheon', 37.2430110, 126.9636354, (SELECT id FROM regions WHERE code = '41113'), 1),
    ('ST0466', '월곶역', 'Wolgot', 37.3918900, 126.7427410, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0467', '이매역', 'Imae', 37.3952910, 127.1276600, NULL, 1),
    ('ST0468', '정자역', 'Jeongja', 37.3671236, 127.1082385, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0469', '죽전역', 'Jukjeon', 37.3245750, 127.1073850, (SELECT id FROM regions WHERE code = '41465'), 1),
    ('ST0470', '청명역', 'Cheongmyeong', 37.2594850, 127.0789450, (SELECT id FROM regions WHERE code = '41117'), 1),
    ('ST0471', '태평역', 'Taepyeong', 37.4398420, 127.1277460, (SELECT id FROM regions WHERE code = '41131'), 1),
    ('ST0472', '광교역', 'Gwanggyo', 37.3017347, 127.0448561, (SELECT id FROM regions WHERE code = '41117'), 1),
    ('ST0473', '광교중앙역', 'Gwanggyo Jungang', 37.2877375, 127.0526250, (SELECT id FROM regions WHERE code = '41117'), 1),
    ('ST0474', '동천역', 'Dongcheon', 37.3386841, 127.1027937, (SELECT id FROM regions WHERE code = '41465'), 1),
    ('ST0475', '상현역', 'Sanghyeon', 37.2986853, 127.0695799, (SELECT id FROM regions WHERE code = '41465'), 1),
    ('ST0476', '성복역', 'Seongbok', 37.3140425, 127.0807578, (SELECT id FROM regions WHERE code = '41465'), 1),
    ('ST0477', '수지구청역', 'Suji-gu Office', 37.3230090, 127.0955813, (SELECT id FROM regions WHERE code = '41465'), 1),
    ('ST0478', '판교역', 'Pangyo', 37.3952564, 127.1111729, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0479', '가평역', 'Gapyeong', 37.8145150, 127.5106930, (SELECT id FROM regions WHERE code = '41820'), 1),
    ('ST0480', '갈매역', 'Galmae', 37.6341350, 127.1148030, (SELECT id FROM regions WHERE code = '41310'), 1),
    ('ST0481', '금곡역', 'Geumgok', 37.6374140, 127.2079310, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0482', '대성리역', 'Daeseong-ri', 37.6839090, 127.3791650, (SELECT id FROM regions WHERE code = '41820'), 1),
    ('ST0483', '마석역', 'Maseok', 37.6523280, 127.3117130, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0484', '사릉역', 'Sareung', 37.6511450, 127.1768390, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0485', '상천역', 'Sangcheon', 37.7703050, 127.4542000, (SELECT id FROM regions WHERE code = '41820'), 1),
    ('ST0486', '천마산역', 'Cheonmasan', 37.6589950, 127.2857310, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0487', '청평역', 'Cheongpyeong', 37.7354840, 127.4265410, (SELECT id FROM regions WHERE code = '41820'), 1),
    ('ST0488', '퇴계원역', 'Toegyewon', 37.6483160, 127.1438580, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0489', '평내호평역', 'PyeongnaeHopyeong', 37.6532290, 127.2444520, (SELECT id FROM regions WHERE code = '41360'), 1),
    ('ST0490', '경기광주역', 'GyeonggiGwangju', 37.3989740, 127.2532010, (SELECT id FROM regions WHERE code = '41610'), 1),
    ('ST0491', '곤지암역', 'Gonjiam', 37.3505250, 127.3461860, (SELECT id FROM regions WHERE code = '41610'), 1),
    ('ST0492', '부발역', 'Bubal', 37.2604110, 127.4903030, (SELECT id FROM regions WHERE code = '41500'), 1),
    ('ST0493', '삼동역', 'Samdong', 37.4087310, 127.2034470, (SELECT id FROM regions WHERE code = '41610'), 1),
    ('ST0494', '성남역', 'Seongnam', 37.3947090, 127.1194900, (SELECT id FROM regions WHERE code = '41135'), 1),
    ('ST0495', '세종대왕릉역', 'Sejongdaewangneung', 37.2936360, 127.5707640, (SELECT id FROM regions WHERE code = '41670'), 1),
    ('ST0496', '신둔도예촌역', 'Sindundoyechon', 37.3154990, 127.4052510, (SELECT id FROM regions WHERE code = '41500'), 1),
    ('ST0497', '여주역', 'Yeoju', 37.2828810, 127.6285710, (SELECT id FROM regions WHERE code = '41670'), 1),
    ('ST0498', '이천역', 'Icheon', 37.2642220, 127.4421350, (SELECT id FROM regions WHERE code = '41500'), 1),
    ('ST0499', '초월역', 'Chowol', 37.3733890, 127.2999960, (SELECT id FROM regions WHERE code = '41610'), 1),
    ('ST0500', '달미역', 'Dalmi', 37.3490003, 126.8093180, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0501', '선부역', 'Seonbu', 37.3345900, 126.8100000, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0502', '소새울역', 'Sosaeul', 37.4685046, 126.7972280, NULL, 1),
    ('ST0503', '시우역', 'Siu', 37.3130967, 126.7955030, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0504', '시흥능곡역', 'Siheung Neunggok', 37.3699903, 126.8085940, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0505', '시흥대야역', 'Siheung Daeya', 37.4504787, 126.7931420, NULL, 1),
    ('ST0506', '시흥시청역', 'Siheung City Hall', 37.3814109, 126.8059460, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0507', '신천역', 'Sincheon', 37.4391147, 126.7868350, (SELECT id FROM regions WHERE code = '41390'), 1),
    ('ST0508', '신현역', 'Sinhyeon', 37.4091399, 126.7878800, NULL, 1),
    ('ST0509', '원시역', 'Wonsi', 37.3026540, 126.7867897, (SELECT id FROM regions WHERE code = '41273'), 1),
    ('ST0510', '원종역', 'Wonjong', 37.5240628, 126.8048386, (SELECT id FROM regions WHERE code = '41196'), 1),
    ('ST0511', '걸포북변역', 'Geolpo Bukbyeon', 37.6316390, 126.7057500, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0512', '고촌역', 'Gochon', 37.6012500, 126.7703610, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0513', '구래역', 'Gurae', 37.6453540, 126.6285000, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0514', '마산역', 'Masan', 37.6406060, 126.6439440, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0515', '사우역', 'Sau', 37.6203610, 126.7197220, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0516', '양촌역', 'Yangchon', 37.6416390, 126.6146940, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0517', '운양역', 'Unyang', 37.6538610, 126.6838330, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0518', '장기역', 'Janggi', 37.6440280, 126.6690000, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0519', '풍무역', 'Pungmu', 37.6123330, 126.7325830, (SELECT id FROM regions WHERE code = '41570'), 1),
    ('ST0520', '경기도청북부청사역', 'Northern Gyeonggi-do Government', 37.7507321, 127.0714795, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0521', '경전철의정부역', 'LRT Uijeongbu', 37.7372997, 127.0433210, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0522', '곤제역', 'Gonjae', 37.7505434, 127.0838651, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0523', '동오역', 'Dong-o', 37.7451912, 127.0570300, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0524', '발곡역', 'Balgok', 37.7269830, 127.0528890, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0525', '범골역', 'Beomgol', 37.7287279, 127.0437202, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0526', '새말역', 'Sae-mal', 37.7488994, 127.0638690, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0527', '송산역', 'Songsan', 37.7371698, 127.0872755, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0528', '어룡역', 'Eoryong', 37.7427218, 127.0852045, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0529', '의정부시청역', 'Uijeongbu City Hall', 37.7392487, 127.0348540, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0530', '의정부중앙역', 'Uijeongbu Jung-ang', 37.7437190, 127.0496332, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0531', '탑석역', 'Tapseok', 37.7335536, 127.0889064, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0532', '효자역', 'Hyoja', 37.7539845, 127.0773155, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0533', '흥선역', 'Heungseon', 37.7432249, 127.0371765, (SELECT id FROM regions WHERE code = '41150'), 1),
    ('ST0534', '강남대역', 'Kangnam Univ.', 37.2701707, 127.1260669, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0535', '고진역', 'Gojin', 37.2446383, 127.2141836, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0536', '김량장역', 'Gimnyangjang', 37.2373238, 127.1986109, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0537', '동백역', 'Dongbaek', 37.2691014, 127.1526906, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0538', '둔전역', 'Dunjeon', 37.2672122, 127.2136387, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0539', '명지대역', 'Myongji Univ.', 37.2380763, 127.1902913, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0540', '보평역', 'Bopyeong', 37.2589643, 127.2184808, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0541', '삼가역', 'Samga', 37.2420599, 127.1681800, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0542', '시청·용인대역', 'Yongin City Hall·Yongin Univ.', 37.2394420, 127.1788851, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0543', '어정역', 'Eojeong', 37.2749513, 127.1437499, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0544', '용인중앙시장역', 'Yongin Jungang Market', 37.2379113, 127.2090526, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0545', '전대·에버랜드역', 'Jeondae·Everland', 37.2854133, 127.2195090, (SELECT id FROM regions WHERE code = '41461'), 1),
    ('ST0546', '지석역', 'Jiseok', 37.2697373, 127.1363256, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0547', '초당역', 'Chodang', 37.2608065, 127.1594692, (SELECT id FROM regions WHERE code = '41463'), 1),
    ('ST0548', '간석역', 'Ganseok', 37.4646890, 126.6934080, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0549', '도원역', 'Dowon', 37.4686040, 126.6428240, (SELECT id FROM regions WHERE code = '28125'), 1),
    ('ST0550', '도화역', 'Dohwa', 37.4661540, 126.6684600, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0551', '동암역', 'Dongam', 37.4708350, 126.7030160, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0552', '동인천역', 'Dongincheon', 37.4754140, 126.6325980, NULL, 1),
    ('ST0553', '백운역', 'Baegun', 37.4833010, 126.7071790, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0554', '부개역', 'Bugae', 37.4884790, 126.7405490, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0555', '부평역', 'Bupyeong', 37.4899990, 126.7240105, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0556', '인천역', 'Incheon', 37.4763640, 126.6171798, NULL, 1),
    ('ST0557', '제물포역', 'Jemulpo', 37.4668090, 126.6571650, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0558', '주안역', 'Juan', 37.4651375, 126.6798715, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0559', '굴포천역', 'Gulpocheon', 37.5069500, 126.7314810, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0560', '부평구청역', 'Bupyeong-gu Office', 37.5081455, 126.7209515, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0561', '산곡역', 'Sangok', 37.5085470, 126.7035100, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0562', '삼산체육관역', 'Samsan Gymnasium', 37.5064310, 126.7420810, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0563', '석남역', 'Seongnam', 37.5067095, 126.6762585, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0564', '검암역', 'Geomam', 37.5688832, 126.6746089, NULL, 1),
    ('ST0565', '계양역', 'Gyeyang', 37.5716540, 126.7363190, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0566', '공항화물청사역', 'Incheon Int''l Airport Cargo Terminal', 37.4590261, 126.4774333, NULL, 1),
    ('ST0567', '영종역', 'Yeongjong', 37.5120981, 126.5242471, NULL, 1),
    ('ST0568', '운서역', 'Unseo', 37.4928446, 126.4936619, NULL, 1),
    ('ST0569', '인천공항1터미널역', 'Incheon Int''l Airport Terminal 1', 37.4476067, 126.4523433, NULL, 1),
    ('ST0570', '인천공항2터미널역', 'Incheon Int''l Airport Terminal 2', 37.4686600, 126.4334150, NULL, 1),
    ('ST0571', '청라국제도시역', 'Cheongna Int''l City', 37.5565090, 126.6247250, NULL, 1),
    ('ST0572', '남동인더스파크역', 'Namdong Induspark', 37.4076680, 126.6956130, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0573', '소래포구역', 'Soraepogu', 37.4009630, 126.7335330, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0574', '송도역', 'Songdo', 37.4297140, 126.6544860, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0575', '숭의역', 'Sungui', 37.4608910, 126.6380350, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0576', '신포역', 'Sinpo', 37.4684420, 126.6245090, NULL, 1),
    ('ST0577', '연수역', 'Yeonsu', 37.4177690, 126.6789910, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0578', '원인재역', 'Woninjae', 37.4126425, 126.6874225, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0579', '인천논현역', 'Incheon Nonhyeon', 37.4006410, 126.7224950, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0580', '인하대역', 'Inha Univ.', 37.4481910, 126.6498320, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0581', '호구포역', 'Hogupo', 37.4016220, 126.7086820, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0582', '간석오거리역', 'Ganseogogeori', 37.4669210, 126.7078610, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0583', '갈산역', 'Galsan', 37.5171240, 126.7215660, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0584', '검단호수공원역', 'Geomdan Lake Park', 37.6025000, 126.6880560, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0585', '경인교대입구역', 'Gyeongin Nat''l Univ of Education', 37.5382720, 126.7226030, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0586', '계산역', 'Gyesan', 37.5432430, 126.7284360, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0587', '국제업무지구역', 'Int''l Business District', 37.4004210, 126.6301050, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0588', '귤현역', 'Gyulhyeon', 37.5663620, 126.7424980, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0589', '동막역', 'Dongmak', 37.3981730, 126.6735610, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0590', '동수역', 'Dongsu', 37.4854090, 126.7184640, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0591', '동춘역', 'Dongchun', 37.4046760, 126.6808350, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0592', '문학경기장역', 'Munhak Sports Complex', 37.4349640, 126.6983730, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0593', '박촌역', 'Bakchon', 37.5535250, 126.7449460, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0594', '부평삼거리역', 'Bupyeongsamgeori', 37.4782670, 126.7104940, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0595', '부평시장역', 'Bupyeong Market', 37.4984950, 126.7222820, (SELECT id FROM regions WHERE code = '28237'), 1),
    ('ST0596', '선학역', 'Seonhak', 37.4269850, 126.6988720, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0597', '센트럴파크역', 'Central Park', 37.3928330, 126.6350020, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0598', '송도달빛축제공원역', 'Songdo Moonlight Festival Park', 37.4068750, 126.6260537, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0599', '신검단중앙역', 'Singeomdanjungang', 37.6026000, 126.6986110, NULL, 1),
    ('ST0600', '신연수역', 'Sinyeonsu', 37.4180320, 126.6938530, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0601', '아라역', 'Ara', 37.5922220, 126.7133330, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0602', '예술회관역', 'Arts Center', 37.4493340, 126.7009910, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0603', '인천대입구역', 'Incheon National University', 37.3862390, 126.6393510, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0604', '인천시청역', 'Incheon City Hall', 37.4572595, 126.7018695, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0605', '인천터미널역', 'Incheon Bus Terminal', 37.4419170, 126.6997270, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0606', '임학역', 'Imhak', 37.5450580, 126.7386420, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0607', '작전역', 'Jakjeon', 37.5302710, 126.7225480, (SELECT id FROM regions WHERE code = '28245'), 1),
    ('ST0608', '지식정보단지역', 'BIT Zone', 37.3780210, 126.6454120, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0609', '캠퍼스타운역', 'Campus Town', 37.3882090, 126.6619850, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0610', '테크노파크역', 'Technopark', 37.3823110, 126.6563550, (SELECT id FROM regions WHERE code = '28185'), 1),
    ('ST0611', '가재울역', 'Gajaeul', 37.4841520, 126.6838830, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0612', '가정역', 'Gajeong', 37.5248050, 126.6753870, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0613', '가정중앙시장역', 'Gajeong Jungang Market', 37.5175110, 126.6767840, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0614', '검단사거리역', 'Geomdan Sageori', 37.6016930, 126.6565450, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0615', '검단오류역', 'Geomdan Oryu', 37.5950162, 126.6280100, NULL, 1),
    ('ST0616', '검바위역', 'Geombawi', 37.5610910, 126.6775290, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0617', '남동구청역', 'Namdong-gu Office', 37.4481790, 126.7365810, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0618', '독정역', 'Dokjeong', 37.5849270, 126.6759810, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0619', '마전역', 'Majeon', 37.5977560, 126.6671950, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0620', '만수역', 'Mansu', 37.4549620, 126.7317640, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0621', '모래내시장역', 'Moraenae Market', 37.4558420, 126.7195780, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0622', '서구청역', 'Seo-gu Office', 37.5438390, 126.6767950, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0623', '서부여성회관역', 'West Woman''s Community Center', 37.5002980, 126.6758750, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0624', '석바위시장역', 'Seokbawi Market', 37.4576590, 126.6923870, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0625', '석천사거리역', 'Seokcheon Sageori', 37.4569323, 126.7103830, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0626', '시민공원역', 'Citizens Park', 37.4583390, 126.6810091, (SELECT id FROM regions WHERE code = '28177'), 1),
    ('ST0627', '아시아드경기장역', 'Asiad Stadium', 37.5513730, 126.6770750, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0628', '완정역', 'Wanjeong', 37.5928670, 126.6729610, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0629', '왕길역', 'Wanggil', 37.5950191, 126.6422840, (SELECT id FROM regions WHERE code = '28290'), 1),
    ('ST0630', '운연역', 'Unyeon', 37.4395330, 126.7596090, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0631', '인천가좌역', 'Incheon Gajwa', 37.4899620, 126.6752840, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0632', '인천대공원역', 'Incheon Grand Park', 37.4484750, 126.7530510, (SELECT id FROM regions WHERE code = '28200'), 1),
    ('ST0633', '주안국가산단역', 'Juan National Industrial Complex', 37.4735990, 126.6811020, (SELECT id FROM regions WHERE code = '28275'), 1),
    ('ST0634', '괴정역', 'Goejeong', 35.1000000, 128.9925000, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0635', '교대역', 'Busan Nat''l Univ. of Edu', 35.1948150, 129.0797900, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0636', '구서역', 'Guseo', 35.2474070, 129.0913270, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0637', '남산역', 'Namsan', 35.2652800, 129.0925000, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0638', '남포역', 'Nampo', 35.0977800, 129.0347200, (SELECT id FROM regions WHERE code = '26110'), 1),
    ('ST0639', '낫개역', 'Natgae', 35.0651790, 128.9797020, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0640', '노포역', 'Nopo', 35.2836100, 129.0950000, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0641', '다대포항역', 'Dadaepo Harbor', 35.0572880, 128.9711510, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0642', '다대포해수욕장역', 'Dadaepo Beach', 35.0482290, 128.9659190, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0643', '당리역', 'Dangni', 35.1033300, 128.9741700, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0644', '대티역', 'Daeti', 35.1030600, 128.9997200, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0645', '동대신역', 'Dongdaesin', 35.1105600, 129.0175700, (SELECT id FROM regions WHERE code = '26140'), 1),
    ('ST0646', '동래역', 'Dongnae', 35.2049780, 129.0777404, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0647', '동매역', 'Dongmae', 35.0898190, 128.9740290, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0648', '두실역', 'Dusil', 35.2569400, 129.0913900, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0649', '명륜역', 'Myeongnyun', 35.2122200, 129.0794400, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0650', '범내골역', 'Beomnaegol', 35.1472200, 129.0591700, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0651', '범어사역', 'Beomeosa', 35.2733300, 129.0926800, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0652', '범일역', 'Beomil', 35.1413900, 129.0594400, (SELECT id FROM regions WHERE code = '26170'), 1),
    ('ST0653', '부산역', 'Busan Station', 35.1152800, 129.0397200, (SELECT id FROM regions WHERE code = '26170'), 1),
    ('ST0654', '부산대역', 'Pusan Nat''l Univ.', 35.2300000, 129.0894400, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0655', '부산진역', 'Busanjin', 35.1277800, 129.0477800, (SELECT id FROM regions WHERE code = '26170'), 1),
    ('ST0656', '부전역', 'Bujeon', 35.1635840, 129.0614450, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0657', '사하역', 'Saha', 35.0997200, 128.9833300, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0658', '서대신역', 'Seodaesin', 35.1108300, 129.0119400, (SELECT id FROM regions WHERE code = '26140'), 1),
    ('ST0659', '서면역', 'Seomyeon', 35.1577300, 129.0588435, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0660', '시청역', 'City Hall', 35.1797200, 129.0766700, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0661', '신장림역', 'Sinjangnim', 35.0742220, 128.9765380, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0662', '신평역', 'Sinpyeong', 35.0955600, 128.9605600, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0663', '양정역', 'Yangjeong', 35.1730400, 129.0711900, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0664', '연산역', 'Yeonsan', 35.1861250, 129.0815700, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0665', '온천장역', 'Oncheonjang', 35.2205600, 129.0866700, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0666', '자갈치역', 'Jagalchi', 35.0975000, 129.0269400, (SELECT id FROM regions WHERE code = '26110'), 1),
    ('ST0667', '장림역', 'Jangnim', 35.0815550, 128.9773083, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0668', '장전역', 'Jangjeon', 35.2380600, 129.0880600, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0669', '좌천역', 'Jwacheon', 35.1341700, 129.0541700, (SELECT id FROM regions WHERE code = '26170'), 1),
    ('ST0670', '중앙역', 'Jungang', 35.1038900, 129.0363900, (SELECT id FROM regions WHERE code = '26110'), 1),
    ('ST0671', '초량역', 'Choryang', 35.1211100, 129.0429600, (SELECT id FROM regions WHERE code = '26170'), 1),
    ('ST0672', '토성역', 'Toseong', 35.1008300, 129.0197200, (SELECT id FROM regions WHERE code = '26140'), 1),
    ('ST0673', '하단역', 'Hadan', 35.1061100, 128.9666700, (SELECT id FROM regions WHERE code = '26380'), 1),
    ('ST0674', '가야역', 'Gaya', 35.1558900, 129.0428300, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0675', '감전역', 'Gamjeon', 35.1555300, 128.9911400, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0676', '개금역', 'Gaegeum', 35.1532500, 129.0205600, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0677', '경성대·부경대역', 'Kyungsung Univ.·Pukyong Nat''l Univ.', 35.1375700, 129.1005500, (SELECT id FROM regions WHERE code = '26290'), 1),
    ('ST0678', '광안역', 'Gwangan', 35.1579200, 129.1131970, (SELECT id FROM regions WHERE code = '26500'), 1),
    ('ST0679', '구남역', 'Gunam', 35.1968100, 128.9949200, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0680', '구명역', 'Gumyeong', 35.2025300, 128.9993100, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0681', '국제금융센터·부산은행역', 'Busan Int''l Finance Center·Busan Bank', 35.1458300, 129.0667200, (SELECT id FROM regions WHERE code = '26290'), 1),
    ('ST0682', '금곡역', 'Geumgok', 35.2673300, 129.0168600, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0683', '금련산역', 'Geumnyeonsan', 35.1498900, 129.1110080, (SELECT id FROM regions WHERE code = '26500'), 1),
    ('ST0684', '남천역', 'Namcheon', 35.1421700, 129.1079700, (SELECT id FROM regions WHERE code = '26500'), 1),
    ('ST0685', '냉정역', 'Naengjeong', 35.1512500, 129.0122800, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0686', '대연역', 'Daeyeon', 35.1351200, 129.0921800, (SELECT id FROM regions WHERE code = '26290'), 1),
    ('ST0687', '덕천역', 'Deokcheon', 35.2101245, 129.0054375, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0688', '덕포역', 'Deokpo', 35.1737800, 128.9839400, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0689', '동백역', 'Dongbaek', 35.1614700, 129.1478870, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0690', '동원역', 'Dongwon', 35.2586400, 129.0124200, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0691', '동의대역', 'Dong-eui Univ.', 35.1541100, 129.0325000, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0692', '모덕역', 'Modeok', 35.1803600, 128.9856100, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0693', '모라역', 'Mora', 35.1896700, 128.9886400, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0694', '못골역', 'Motgol', 35.1347200, 129.0844200, (SELECT id FROM regions WHERE code = '26290'), 1),
    ('ST0695', '문현역', 'Munhyeon', 35.1391100, 129.0673600, (SELECT id FROM regions WHERE code = '26290'), 1),
    ('ST0696', '민락역', 'Millak', 35.1672200, 129.1218780, (SELECT id FROM regions WHERE code = '26500'), 1),
    ('ST0697', '벡스코역', 'BEXCO', 35.1704050, 129.1371945, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0698', '부암역', 'Buam', 35.1574400, 129.0501900, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0699', '사상역', 'Sasang', 35.1622409, 128.9853054, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0700', '센텀시티역', 'Centum City', 35.1689200, 129.1317640, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0701', '수영역', 'Suyeong', 35.1667650, 129.1147110, (SELECT id FROM regions WHERE code = '26500'), 1),
    ('ST0702', '수정역', 'Sujeong', 35.2233600, 129.0092200, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0703', '율리역', 'Yulli', 35.2467200, 129.0129200, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0704', '장산역', 'Jangsan', 35.1700000, 129.1769400, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0705', '전포역', 'Jeonpo', 35.1531100, 129.0653300, (SELECT id FROM regions WHERE code = '26230'), 1),
    ('ST0706', '주례역', 'Jurye', 35.1505000, 129.0030600, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0707', '중동역', 'Jung-dong', 35.1666900, 129.1688700, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0708', '지게골역', 'Jigegol', 35.1355800, 129.0743600, (SELECT id FROM regions WHERE code = '26290'), 1),
    ('ST0709', '해운대역', 'Haeundae', 35.1635180, 129.1585840, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0710', '화명역', 'Hwamyeong', 35.2358500, 129.0138700, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0711', '강서구청역', 'Gangseo-gu Office', 35.2112500, 128.9817500, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0712', '거제역', 'Geoje', 35.1890800, 129.0741625, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0713', '구포역', 'Gupo', 35.2064400, 128.9963100, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0714', '남산정역', 'Namsanjeong', 35.2133300, 129.0239200, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0715', '대저역', 'Daejeo', 35.2132480, 128.9606510, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0716', '만덕역', 'Mandeok', 35.2130000, 129.0365000, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0717', '망미역', 'Mangmi', 35.1715300, 129.1082200, (SELECT id FROM regions WHERE code = '26500'), 1),
    ('ST0718', '물만골역', 'Mulmangol', 35.1768100, 129.0857500, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0719', '미남역', 'Minam', 35.2059650, 129.0683700, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0720', '배산역', 'Baesan', 35.1735000, 129.0955000, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0721', '사직역', 'Sajik', 35.1990000, 129.0650000, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0722', '숙등역', 'Sukdeung', 35.2119700, 129.0127500, (SELECT id FROM regions WHERE code = '26320'), 1),
    ('ST0723', '종합운동장역', 'Sports Complex', 35.1912500, 129.0675000, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0724', '체육공원역', 'Sports Park', 35.2126100, 128.9689400, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0725', '고촌역', 'Gochon', 35.2360300, 129.1605000, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0726', '금사역', 'Geumsa', 35.2158300, 129.1151700, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0727', '낙민역', 'Nangmin', 35.2002500, 129.0907800, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0728', '명장역', 'Myeongjang', 35.2050300, 129.1016100, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0729', '반여농산물시장역', 'Banyeo Agricultural Market', 35.2177800, 129.1239700, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0730', '서동역', 'Seo-dong', 35.2133100, 129.1076900, (SELECT id FROM regions WHERE code = '26410'), 1),
    ('ST0731', '석대역', 'Seokdae', 35.2182500, 129.1371700, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0732', '수안역', 'Suan', 35.2018300, 129.0837800, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0733', '안평역', 'Anpyeong', 35.2373300, 129.1718300, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0734', '영산대역', 'Youngsan Univ.', 35.2257800, 129.1461700, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0735', '윗반송역', 'Witbansong', 35.2325000, 129.1540300, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0736', '충렬사역', 'Chungnyeolsa', 35.1998600, 129.0976400, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0737', '공항역', 'Gimhae International Airport', 35.1719060, 128.9485860, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0738', '괘법르네시떼역', 'Gwaebeop Renecite', 35.1632616, 128.9776836, (SELECT id FROM regions WHERE code = '26530'), 1),
    ('ST0739', '대사역', 'Daesa', 35.2176570, 128.9384170, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0740', '덕두역', 'Deokdu', 35.1820010, 128.9541980, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0741', '등구역', 'Deunggu', 35.1961430, 128.9635990, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0742', '서부산유통지구역', 'Seobusan Yutongjigu', 35.1664020, 128.9548881, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0743', '평강역', 'Pyeonggang', 35.2141050, 128.9508250, (SELECT id FROM regions WHERE code = '26440'), 1),
    ('ST0744', '거제해맞이역', 'Geojehaemaji', 35.1811970, 129.0690150, (SELECT id FROM regions WHERE code = '26470'), 1),
    ('ST0745', '기장역', 'Gijang', 35.2446270, 129.2188951, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0746', '부산원동역', 'Busanwondong', 35.1938000, 129.1137090, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0747', '센텀역', 'Centum', 35.1793000, 129.1245000, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0748', '송정역', 'Songjeong', 35.1878320, 129.2020690, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0749', '신해운대역', 'Sinhaeundae', 35.1818320, 129.1766960, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0750', '안락역', 'Allak', 35.1960070, 129.1010620, (SELECT id FROM regions WHERE code = '26260'), 1),
    ('ST0751', '오시리아역', 'OSIRIA', 35.1962560, 129.2082910, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0752', '월내역', 'Wollae', 35.3281770, 129.2766260, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0753', '일광역', 'Ilgwang', 35.2675565, 129.2330307, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0754', '재송역', 'Jaesong', 35.1880000, 129.1201090, (SELECT id FROM regions WHERE code = '26350'), 1),
    ('ST0755', '좌천역', 'Jwacheon', 35.3118380, 129.2451970, (SELECT id FROM regions WHERE code = '26710'), 1),
    ('ST0756', '각산역', 'Gaksan', 35.8678520, 128.7238430, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0757', '교대역', 'National University of Education', 35.8510953, 128.5906430, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0758', '대곡역', 'Daegok', 35.8095710, 128.5125700, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0759', '대구역', 'Daegu', 35.8761895, 128.5965095, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0760', '대구한의대병원역', 'Daegu Haany University Hospital', 35.8717089, 128.7508237, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0761', '대명역', 'Daemyeong', 35.8392924, 128.5652846, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0762', '동구청역', 'Dong-gu Office', 35.8844850, 128.6322950, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0763', '동대구역', 'Dongdaegu', 35.8785010, 128.6279795, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0764', '동촌역', 'Dongchon', 35.8860390, 128.6504550, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0765', '명덕역', 'Myeongdeok', 35.8571636, 128.5904635, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0766', '반야월역', 'Banyawol', 35.8659480, 128.7136630, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0767', '반월당역', 'Banwoldang', 35.8647410, 128.5933230, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0768', '방촌역', 'Bangchon', 35.8799290, 128.6697670, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0769', '상인역', 'Sangin', 35.8188583, 128.5375588, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0770', '서부정류장역', 'Seobu Bus Terminal', 35.8373375, 128.5570702, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0771', '설화명곡역', 'SeolhwaMyeonggok', 35.7986179, 128.4893927, (SELECT id FROM regions WHERE code = '27710'), 1),
    ('ST0772', '송현역', 'Songhyeon', 35.8310920, 128.5517550, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0773', '신기역', 'Singi', 35.8667610, 128.7017240, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0774', '신천역', 'Sincheon', 35.8745270, 128.6172440, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0775', '아양교역', 'Ayanggyo', 35.8872080, 128.6395720, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0776', '안심역', 'Ansim', 35.8712000, 128.7338000, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0777', '안지랑역', 'Anjirang', 35.8392000, 128.5740000, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0778', '영대병원역', 'Yeungnam University Hospital', 35.8442516, 128.5882407, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0779', '용계역', 'Yonggye', 35.8762560, 128.6814140, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0780', '월배역', 'Wolbae', 35.8161230, 128.5301190, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0781', '월촌역', 'Wolchon', 35.8236950, 128.5454650, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0782', '율하역', 'Yulha', 35.8697510, 128.6927750, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0783', '중앙로역', 'Jungangno', 35.8708730, 128.5938880, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0784', '진천역', 'Jincheon', 35.8138150, 128.5223740, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0785', '칠성시장역', 'Chilseong Market', 35.8761590, 128.6048850, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0786', '해안역', 'Haean', 35.8837650, 128.6590790, (SELECT id FROM regions WHERE code = '27140'), 1),
    ('ST0787', '현충로역', 'Hyeonchungno', 35.8407200, 128.5808500, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0788', '화원역', 'Hwawon', 35.8043490, 128.5004090, (SELECT id FROM regions WHERE code = '27710'), 1),
    ('ST0789', '감삼역', 'Gamsam', 35.8543251, 128.5479050, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0790', '강창역', 'Gangchang', 35.8531116, 128.4789997, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0791', '경대병원역', 'Kyungpook Nat''l Univ. Hosp.', 35.8631020, 128.6031600, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0792', '계명대역', 'Keimyung Univ.', 35.8514649, 128.4919690, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0793', '고산역', 'Gosan', 35.8429550, 128.6933500, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0794', '내당역', 'Naedang', 35.8601940, 128.5645930, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0795', '다사역', 'Dasa', 35.8649530, 128.4582876, (SELECT id FROM regions WHERE code = '27710'), 1),
    ('ST0796', '담티역', 'Damti', 35.8548410, 128.6541510, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0797', '대구은행역', 'Daegu Bank', 35.8598680, 128.6139750, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0798', '대실역', 'Daesil', 35.8573333, 128.4656264, (SELECT id FROM regions WHERE code = '27710'), 1),
    ('ST0799', '두류역', 'Duryu', 35.8572405, 128.5555090, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0800', '만촌역', 'Manchon', 35.8588410, 128.6449510, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0801', '문양역', 'Munyang', 35.8641431, 128.4370043, (SELECT id FROM regions WHERE code = '27710'), 1),
    ('ST0802', '반고개역', 'Bangogae', 35.8623000, 128.5728170, (SELECT id FROM regions WHERE code = '27170'), 1),
    ('ST0803', '범어역', 'Beomeo', 35.8591000, 128.6257000, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0804', '사월역', 'Sawol', 35.8370660, 128.7151730, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0805', '성서산업단지역', 'Seongseo Industrial Complex', 35.8518236, 128.5071662, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0806', '수성구청역', 'Suseong-gu Office', 35.8587402, 128.6359517, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0807', '수성알파시티역', 'Suseong Alpha City', 35.8427180, 128.6799900, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0808', '신매역', 'Sinmae', 35.8408000, 128.7050000, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0809', '연호역', 'Yeonho', 35.8465000, 128.6718500, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0810', '용산역', 'Yongsan', 35.8491200, 128.5287686, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0811', '이곡역', 'Igok', 35.8506200, 128.5157686, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0812', '죽전역', 'Jukjeon', 35.8503505, 128.5370890, (SELECT id FROM regions WHERE code = '27290'), 1),
    ('ST0813', '청라언덕역', 'Cheongna Hill', 35.8645972, 128.5823493, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0814', '건들바위역', 'Geondeulbawi', 35.8553825, 128.5996903, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0815', '공단역', 'Gongdan', 35.8920000, 128.5536000, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0816', '구암역', 'Guam', 35.9257422, 128.5503415, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0817', '남산역', 'Namsan', 35.8566898, 128.5834576, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0818', '달성공원역', 'Dalseong Park', 35.8757300, 128.5821218, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0819', '대봉교역', 'Daebonggyo', 35.8549978, 128.6063685, (SELECT id FROM regions WHERE code = '27200'), 1),
    ('ST0820', '동천역', 'Dongcheon', 35.9377066, 128.5566830, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0821', '만평역', 'Manpyeong', 35.8898930, 128.5610020, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0822', '매천역', 'Maecheon', 35.9126500, 128.5434000, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0823', '매천시장역', 'Maecheon Market', 35.9044300, 128.5457550, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0824', '범물역', 'Beommul', 35.8208392, 128.6401127, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0825', '북구청역', 'Buk-gu Office', 35.8838140, 128.5814483, (SELECT id FROM regions WHERE code = '27170'), 1),
    ('ST0826', '서문시장역', 'Seomun Market', 35.8696643, 128.5822410, (SELECT id FROM regions WHERE code = '27110'), 1),
    ('ST0827', '수성구민운동장역', 'Suseong District Stadium', 35.8524098, 128.6251246, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0828', '수성못역', 'Suseongmot', 35.8313561, 128.6232167, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0829', '수성시장역', 'Suseong Market', 35.8543168, 128.6160292, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0830', '어린이세상역', 'Children''s World', 35.8451699, 128.6244677, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0831', '용지역', 'Yongji', 35.8182678, 128.6465345, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0832', '원대역', 'Wondae', 35.8878860, 128.5743920, (SELECT id FROM regions WHERE code = '27170'), 1),
    ('ST0833', '지산역', 'Jisan', 35.8251532, 128.6319513, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0834', '칠곡경대병원역', 'Chilgok Kyungpook Nat''l Univ. Medical Center', 35.9583875, 128.5598908, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0835', '칠곡운암역', 'Chilgok·Unam', 35.9314553, 128.5545305, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0836', '태전역', 'Taejeon', 35.9196172, 128.5473880, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0837', '팔거역', 'Palgeo', 35.9440472, 128.5583366, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0838', '팔달역', 'Paldal', 35.8977700, 128.5468000, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0839', '팔달시장역', 'Paldal Market', 35.8888309, 128.5678716, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0840', '학정역', 'Hakjeong', 35.9515551, 128.5591934, (SELECT id FROM regions WHERE code = '27230'), 1),
    ('ST0841', '황금역', 'Hwanggeum', 35.8390784, 128.6239185, (SELECT id FROM regions WHERE code = '27260'), 1),
    ('ST0842', '서대구역', 'Seodaegu', 35.8817340, 128.5401210, (SELECT id FROM regions WHERE code = '27170'), 1),
    ('ST0843', '공항역', 'Airport', 35.1441790, 126.8115300, (SELECT id FROM regions WHERE code = '12330'), 1),
    ('ST0844', '광주송정역', 'Gwangju Songjeong Station', 35.1375290, 126.7914880, (SELECT id FROM regions WHERE code = '12330'), 1),
    ('ST0845', '금남로4가역', 'Geumnamno 4(sa)-ga', 35.1509760, 126.9144460, (SELECT id FROM regions WHERE code = '12210'), 1),
    ('ST0846', '금남로5가역', 'Geumnamno 5(o)-ga', 35.1536580, 126.9100330, (SELECT id FROM regions WHERE code = '12300'), 1),
    ('ST0847', '김대중컨벤션센터역', 'Kim Daejung Convention Center', 35.1432140, 126.8414420, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0848', '남광주역', 'Namgwangju', 35.1389849, 126.9231800, (SELECT id FROM regions WHERE code = '12210'), 1),
    ('ST0849', '녹동역', 'Nokdong', 35.1070280, 126.9339320, (SELECT id FROM regions WHERE code = '12210'), 1),
    ('ST0850', '농성역', 'Nongseong', 35.1533630, 126.8839730, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0851', '도산역', 'Dosan', 35.1317130, 126.7879420, (SELECT id FROM regions WHERE code = '12330'), 1),
    ('ST0852', '돌고개역', 'Dolgogae', 35.1520470, 126.8956370, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0853', '문화전당역', 'Culture Center', 35.1466100, 126.9200500, (SELECT id FROM regions WHERE code = '12210'), 1),
    ('ST0854', '상무역', 'Sangmu', 35.1467840, 126.8486410, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0855', '소태역', 'Sotae', 35.1241080, 126.9320130, (SELECT id FROM regions WHERE code = '12210'), 1),
    ('ST0856', '송정공원역', 'Songjeong Park', 35.1435650, 126.7992450, (SELECT id FROM regions WHERE code = '12330'), 1),
    ('ST0857', '쌍촌역', 'Ssangchon', 35.1517930, 126.8695190, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0858', '양동시장역', 'Yangdong Market', 35.1541760, 126.9010890, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0859', '운천역', 'Uncheon', 35.1504860, 126.8586940, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0860', '평동역', 'Pyeong-dong', 35.1247000, 126.7695860, (SELECT id FROM regions WHERE code = '12330'), 1),
    ('ST0861', '학동증심사입구역', 'Hak-dong·Jeungsimsa', 35.1330230, 126.9283820, (SELECT id FROM regions WHERE code = '12210'), 1),
    ('ST0862', '화정역', 'Hwajeong', 35.1520850, 126.8766980, (SELECT id FROM regions WHERE code = '12240'), 1),
    ('ST0863', '갈마역', 'Galma', 36.3575220, 127.3726320, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0864', '갑천역', 'Gapcheon', 36.3545780, 127.3544670, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0865', '구암역', 'Guam', 36.3566050, 127.3308300, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0866', '노은역', 'Noeun', 36.3741500, 127.3178350, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0867', '대동역', 'Dae-dong', 36.3295320, 127.4428480, (SELECT id FROM regions WHERE code = '30110'), 1),
    ('ST0868', '대전역', 'Daejeon', 36.3315830, 127.4331180, (SELECT id FROM regions WHERE code = '30110'), 1),
    ('ST0869', '반석역', 'Banseok', 36.3921280, 127.3145810, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0870', '서대전네거리역', 'Seodaejeon Negeori', 36.3224160, 127.4125180, (SELECT id FROM regions WHERE code = '30140'), 1),
    ('ST0871', '시청역', 'City Hall', 36.3516730, 127.3867390, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0872', '신흥역', 'Sinheung', 36.3196520, 127.4487920, (SELECT id FROM regions WHERE code = '30110'), 1),
    ('ST0873', '오룡역', 'Oryong', 36.3285820, 127.4050460, (SELECT id FROM regions WHERE code = '30140'), 1),
    ('ST0874', '용문역', 'Yongmun', 36.3382520, 127.3933170, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0875', '월드컵경기장역', 'World Cup Stadium', 36.3668250, 127.3178590, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0876', '월평역', 'Wolpyeong', 36.3582720, 127.3643820, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0877', '유성온천역', 'Yuseong Spa', 36.3536890, 127.3415240, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0878', '정부청사역', 'Government Complex Daejeon', 36.3576610, 127.3810470, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0879', '중구청역', 'Jung-gu Office', 36.3248310, 127.4196270, (SELECT id FROM regions WHERE code = '30140'), 1),
    ('ST0880', '중앙로역', 'Jungangno', 36.3286640, 127.4258420, (SELECT id FROM regions WHERE code = '30140'), 1),
    ('ST0881', '지족역', 'Jijok', 36.3841060, 127.3195010, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0882', '탄방역', 'Tanbang', 36.3454400, 127.3846030, (SELECT id FROM regions WHERE code = '30170'), 1),
    ('ST0883', '판암역', 'Panam', 36.3168960, 127.4582640, (SELECT id FROM regions WHERE code = '30110'), 1),
    ('ST0884', '현충원역', 'National Cemetery', 36.3590210, 127.3215250, (SELECT id FROM regions WHERE code = '30200'), 1),
    ('ST0885', '개운포역', 'Gaeunpo', 35.5078840, 129.3210970, (SELECT id FROM regions WHERE code = '31140'), 1),
    ('ST0886', '남창역', 'Namchang', 35.4197130, 129.2822300, (SELECT id FROM regions WHERE code = '31710'), 1),
    ('ST0887', '덕하역', 'Deokha', 35.4940070, 129.3030940, (SELECT id FROM regions WHERE code = '31710'), 1),
    ('ST0888', '망양역', 'Mangyang', 35.4565190, 129.2878560, (SELECT id FROM regions WHERE code = '31710'), 1),
    ('ST0889', '서생역', 'Seosaeng', 35.3461210, 129.2930400, (SELECT id FROM regions WHERE code = '31710'), 1),
    ('ST0890', '태화강역', 'Taehwagang', 35.5386690, 129.3532690, (SELECT id FROM regions WHERE code = '31140'), 1),
    ('ST0891', '강촌역', 'Gangchon', 37.8057280, 127.6340540, (SELECT id FROM regions WHERE code = '51110'), 1),
    ('ST0892', '굴봉산역', 'Gulbongsan', 37.8320860, 127.5577320, (SELECT id FROM regions WHERE code = '51110'), 1),
    ('ST0893', '김유정역', 'Gimyujeong', 37.8184750, 127.7143020, (SELECT id FROM regions WHERE code = '51110'), 1),
    ('ST0894', '남춘천역', 'Namchuncheon', 37.8640710, 127.7237610, (SELECT id FROM regions WHERE code = '51110'), 1),
    ('ST0895', '백양리역', 'Baegyang-ri', 37.8308810, 127.5890850, (SELECT id FROM regions WHERE code = '51110'), 1),
    ('ST0896', '춘천역', 'Chuncheon', 37.8843910, 127.7166030, (SELECT id FROM regions WHERE code = '51110'), 1),
    ('ST0897', '두정역', 'Dujeong', 36.8337910, 127.1489050, (SELECT id FROM regions WHERE code = '44133'), 1),
    ('ST0898', '배방역', 'Baebang', 36.7775410, 127.0527510, (SELECT id FROM regions WHERE code = '44200'), 1),
    ('ST0899', '봉명역', 'Bongmyeong', 36.8015780, 127.1363190, (SELECT id FROM regions WHERE code = '44131'), 1),
    ('ST0900', '성환역', 'Seonghwan', 36.9157810, 127.1270070, (SELECT id FROM regions WHERE code = '44133'), 1),
    ('ST0901', '신창역', 'Sinchang', 36.7692130, 126.9501290, (SELECT id FROM regions WHERE code = '44200'), 1),
    ('ST0902', '쌍용역', 'Ssangyong', 36.7937450, 127.1213630, (SELECT id FROM regions WHERE code = '44133'), 1),
    ('ST0903', '아산역', 'Asan', 36.7921950, 127.1043840, (SELECT id FROM regions WHERE code = '44200'), 1),
    ('ST0904', '온양온천역', 'Onyangoncheon', 36.7804190, 127.0037070, (SELECT id FROM regions WHERE code = '44200'), 1),
    ('ST0905', '직산역', 'Jiksan', 36.8709210, 127.1437740, (SELECT id FROM regions WHERE code = '44133'), 1),
    ('ST0906', '천안역', 'Cheonan', 36.8102380, 127.1464170, (SELECT id FROM regions WHERE code = '44131'), 1),
    ('ST0907', '탕정역', 'Tangjeong', 36.7882720, 127.0844460, (SELECT id FROM regions WHERE code = '44200'), 1),
    ('ST0908', '부호역', 'Buho', 35.9002809, 128.8031144, (SELECT id FROM regions WHERE code = '47290'), 1),
    ('ST0909', '하양역', 'Hayang', 35.9094258, 128.8175403, (SELECT id FROM regions WHERE code = '47290'), 1),
    ('ST0910', '영남대역', 'Yeungnam University', 35.8363730, 128.7529660, (SELECT id FROM regions WHERE code = '47290'), 1),
    ('ST0911', '임당역', 'Imdang', 35.8341310, 128.7410760, (SELECT id FROM regions WHERE code = '47290'), 1),
    ('ST0912', '정평역', 'Jeongpyeong', 35.8340700, 128.7286950, (SELECT id FROM regions WHERE code = '47290'), 1),
    ('ST0913', '경산역', 'Gyeongsan', 35.8192000, 128.7276000, (SELECT id FROM regions WHERE code = '47290'), 1),
    ('ST0914', '구미역', 'Gumi', 36.1282780, 128.3310710, (SELECT id FROM regions WHERE code = '47190'), 1),
    ('ST0915', '북삼역', 'Buksam', 36.0560326, 128.3452780, (SELECT id FROM regions WHERE code = '47850'), 1),
    ('ST0916', '사곡역', 'Sagok', 36.0985080, 128.3564160, (SELECT id FROM regions WHERE code = '47190'), 1),
    ('ST0917', '왜관역', 'Waegwan', 35.9918210, 128.4003910, (SELECT id FROM regions WHERE code = '47850'), 1),
    ('ST0918', '남양산역', 'Namyangsan', 35.3253300, 129.0194200, (SELECT id FROM regions WHERE code = '48330'), 1),
    ('ST0919', '부산대양산캠퍼스역', 'Pusan Nat''l Univ. Yangsan Campus', 35.3169700, 129.0138300, (SELECT id FROM regions WHERE code = '48330'), 1),
    ('ST0920', '양산역', 'Yangsan', 35.3385100, 129.0264300, (SELECT id FROM regions WHERE code = '48330'), 1),
    ('ST0921', '증산역', 'Jeungsan', 35.3082800, 129.0102800, (SELECT id FROM regions WHERE code = '48330'), 1),
    ('ST0922', '호포역', 'Hopo', 35.2810000, 129.0175250, (SELECT id FROM regions WHERE code = '48330'), 1),
    ('ST0923', '가야대역', 'Kaya Univ.', 35.2667272, 128.8650703, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0924', '김해대학역', 'Gimhae College', 35.2289900, 128.9156120, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0925', '김해시청역', 'Gimhae City Hall', 35.2271030, 128.8904100, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0926', '박물관역', 'Museum', 35.2400647, 128.8718628, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0927', '봉황역', 'Bonghwang', 35.2272676, 128.8743069, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0928', '부원역', 'Buwon', 35.2264662, 128.8838997, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0929', '불암역', 'Buram', 35.2221620, 128.9278930, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0930', '수로왕릉역', 'Royal Tomb of King Suro', 35.2328215, 128.8721409, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0931', '연지공원역', 'Yeonji Park', 35.2496576, 128.8692908, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0932', '인제대역', 'Inje Univ', 35.2280990, 128.9017360, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0933', '장신대역', 'Presbyterian Univ.', 35.2595564, 128.8671142, (SELECT id FROM regions WHERE code = '48250'), 1),
    ('ST0934', '지내역', 'Jinae', 35.2275000, 128.9237210, (SELECT id FROM regions WHERE code = '48250'), 1)
ON DUPLICATE KEY UPDATE name_ko   = VALUES(name_ko),
                        name_en   = VALUES(name_en),
                        lat       = VALUES(lat),
                        lng       = VALUES(lng),
                        region_id = VALUES(region_id),
                        is_active = VALUES(is_active);

UPDATE subway_stations
SET is_active = 0
WHERE is_active = 1
  AND code NOT IN ('ST0001', 'ST0002', 'ST0003', 'ST0004', 'ST0005', 'ST0006', 'ST0007', 'ST0008', 'ST0009', 'ST0010', 'ST0011', 'ST0012', 'ST0013', 'ST0014', 'ST0015', 'ST0016', 'ST0017', 'ST0018', 'ST0019', 'ST0020', 'ST0021', 'ST0022', 'ST0023', 'ST0024', 'ST0025', 'ST0026', 'ST0027', 'ST0028', 'ST0029', 'ST0030', 'ST0031', 'ST0032', 'ST0033', 'ST0034', 'ST0035', 'ST0036', 'ST0037', 'ST0038', 'ST0039', 'ST0040', 'ST0041', 'ST0042', 'ST0043', 'ST0044', 'ST0045', 'ST0046', 'ST0047', 'ST0048', 'ST0049', 'ST0050', 'ST0051', 'ST0052', 'ST0053', 'ST0054', 'ST0055', 'ST0056', 'ST0057', 'ST0058', 'ST0059', 'ST0060', 'ST0061', 'ST0062', 'ST0063', 'ST0064', 'ST0065', 'ST0066', 'ST0067', 'ST0068', 'ST0069', 'ST0070', 'ST0071', 'ST0072', 'ST0073', 'ST0074', 'ST0075', 'ST0076', 'ST0077', 'ST0078', 'ST0079', 'ST0080', 'ST0081', 'ST0082', 'ST0083', 'ST0084', 'ST0085', 'ST0086', 'ST0087', 'ST0088', 'ST0089', 'ST0090', 'ST0091', 'ST0092', 'ST0093', 'ST0094', 'ST0095', 'ST0096', 'ST0097', 'ST0098', 'ST0099', 'ST0100', 'ST0101', 'ST0102', 'ST0103', 'ST0104', 'ST0105', 'ST0106', 'ST0107', 'ST0108', 'ST0109', 'ST0110', 'ST0111', 'ST0112', 'ST0113', 'ST0114', 'ST0115', 'ST0116', 'ST0117', 'ST0118', 'ST0119', 'ST0120', 'ST0121', 'ST0122', 'ST0123', 'ST0124', 'ST0125', 'ST0126', 'ST0127', 'ST0128', 'ST0129', 'ST0130', 'ST0131', 'ST0132', 'ST0133', 'ST0134', 'ST0135', 'ST0136', 'ST0137', 'ST0138', 'ST0139', 'ST0140', 'ST0141', 'ST0142', 'ST0143', 'ST0144', 'ST0145', 'ST0146', 'ST0147', 'ST0148', 'ST0149', 'ST0150', 'ST0151', 'ST0152', 'ST0153', 'ST0154', 'ST0155', 'ST0156', 'ST0157', 'ST0158', 'ST0159', 'ST0160', 'ST0161', 'ST0162', 'ST0163', 'ST0164', 'ST0165', 'ST0166', 'ST0167', 'ST0168', 'ST0169', 'ST0170', 'ST0171', 'ST0172', 'ST0173', 'ST0174', 'ST0175', 'ST0176', 'ST0177', 'ST0178', 'ST0179', 'ST0180', 'ST0181', 'ST0182', 'ST0183', 'ST0184', 'ST0185', 'ST0186', 'ST0187', 'ST0188', 'ST0189', 'ST0190', 'ST0191', 'ST0192', 'ST0193', 'ST0194', 'ST0195', 'ST0196', 'ST0197', 'ST0198', 'ST0199', 'ST0200', 'ST0201', 'ST0202', 'ST0203', 'ST0204', 'ST0205', 'ST0206', 'ST0207', 'ST0208', 'ST0209', 'ST0210', 'ST0211', 'ST0212', 'ST0213', 'ST0214', 'ST0215', 'ST0216', 'ST0217', 'ST0218', 'ST0219', 'ST0220', 'ST0221', 'ST0222', 'ST0223', 'ST0224', 'ST0225', 'ST0226', 'ST0227', 'ST0228', 'ST0229', 'ST0230', 'ST0231', 'ST0232', 'ST0233', 'ST0234', 'ST0235', 'ST0236', 'ST0237', 'ST0238', 'ST0239', 'ST0240', 'ST0241', 'ST0242', 'ST0243', 'ST0244', 'ST0245', 'ST0246', 'ST0247', 'ST0248', 'ST0249', 'ST0250', 'ST0251', 'ST0252', 'ST0253', 'ST0254', 'ST0255', 'ST0256', 'ST0257', 'ST0258', 'ST0259', 'ST0260', 'ST0261', 'ST0262', 'ST0263', 'ST0264', 'ST0265', 'ST0266', 'ST0267', 'ST0268', 'ST0269', 'ST0270', 'ST0271', 'ST0272', 'ST0273', 'ST0274', 'ST0275', 'ST0276', 'ST0277', 'ST0278', 'ST0279', 'ST0280', 'ST0281', 'ST0282', 'ST0283', 'ST0284', 'ST0285', 'ST0286', 'ST0287', 'ST0288', 'ST0289', 'ST0290', 'ST0291', 'ST0292', 'ST0293', 'ST0294', 'ST0295', 'ST0296', 'ST0297', 'ST0298', 'ST0299', 'ST0300', 'ST0301', 'ST0302', 'ST0303', 'ST0304', 'ST0305', 'ST0306', 'ST0307', 'ST0308', 'ST0309', 'ST0310', 'ST0311', 'ST0312', 'ST0313', 'ST0314', 'ST0315', 'ST0316', 'ST0317', 'ST0318', 'ST0319', 'ST0320', 'ST0321', 'ST0322', 'ST0323', 'ST0324', 'ST0325', 'ST0326', 'ST0327', 'ST0328', 'ST0329', 'ST0330', 'ST0331', 'ST0332', 'ST0333', 'ST0334', 'ST0335', 'ST0336', 'ST0337', 'ST0338', 'ST0339', 'ST0340', 'ST0341', 'ST0342', 'ST0343', 'ST0344', 'ST0345', 'ST0346', 'ST0347', 'ST0348', 'ST0349', 'ST0350', 'ST0351', 'ST0352', 'ST0353', 'ST0354', 'ST0355', 'ST0356', 'ST0357', 'ST0358', 'ST0359', 'ST0360', 'ST0361', 'ST0362', 'ST0363', 'ST0364', 'ST0365', 'ST0366', 'ST0367', 'ST0368', 'ST0369', 'ST0370', 'ST0371', 'ST0372', 'ST0373', 'ST0374', 'ST0375', 'ST0376', 'ST0377', 'ST0378', 'ST0379', 'ST0380', 'ST0381', 'ST0382', 'ST0383', 'ST0384', 'ST0385', 'ST0386', 'ST0387', 'ST0388', 'ST0389', 'ST0390', 'ST0391', 'ST0392', 'ST0393', 'ST0394', 'ST0395', 'ST0396', 'ST0397', 'ST0398', 'ST0399', 'ST0400', 'ST0401', 'ST0402', 'ST0403', 'ST0404', 'ST0405', 'ST0406', 'ST0407', 'ST0408', 'ST0409', 'ST0410', 'ST0411', 'ST0412', 'ST0413', 'ST0414', 'ST0415', 'ST0416', 'ST0417', 'ST0418', 'ST0419', 'ST0420', 'ST0421', 'ST0422', 'ST0423', 'ST0424', 'ST0425', 'ST0426', 'ST0427', 'ST0428', 'ST0429', 'ST0430', 'ST0431', 'ST0432', 'ST0433', 'ST0434', 'ST0435', 'ST0436', 'ST0437', 'ST0438', 'ST0439', 'ST0440', 'ST0441', 'ST0442', 'ST0443', 'ST0444', 'ST0445', 'ST0446', 'ST0447', 'ST0448', 'ST0449', 'ST0450', 'ST0451', 'ST0452', 'ST0453', 'ST0454', 'ST0455', 'ST0456', 'ST0457', 'ST0458', 'ST0459', 'ST0460', 'ST0461', 'ST0462', 'ST0463', 'ST0464', 'ST0465', 'ST0466', 'ST0467', 'ST0468', 'ST0469', 'ST0470', 'ST0471', 'ST0472', 'ST0473', 'ST0474', 'ST0475', 'ST0476', 'ST0477', 'ST0478', 'ST0479', 'ST0480', 'ST0481', 'ST0482', 'ST0483', 'ST0484', 'ST0485', 'ST0486', 'ST0487', 'ST0488', 'ST0489', 'ST0490', 'ST0491', 'ST0492', 'ST0493', 'ST0494', 'ST0495', 'ST0496', 'ST0497', 'ST0498', 'ST0499', 'ST0500', 'ST0501', 'ST0502', 'ST0503', 'ST0504', 'ST0505', 'ST0506', 'ST0507', 'ST0508', 'ST0509', 'ST0510', 'ST0511', 'ST0512', 'ST0513', 'ST0514', 'ST0515', 'ST0516', 'ST0517', 'ST0518', 'ST0519', 'ST0520', 'ST0521', 'ST0522', 'ST0523', 'ST0524', 'ST0525', 'ST0526', 'ST0527', 'ST0528', 'ST0529', 'ST0530', 'ST0531', 'ST0532', 'ST0533', 'ST0534', 'ST0535', 'ST0536', 'ST0537', 'ST0538', 'ST0539', 'ST0540', 'ST0541', 'ST0542', 'ST0543', 'ST0544', 'ST0545', 'ST0546', 'ST0547', 'ST0548', 'ST0549', 'ST0550', 'ST0551', 'ST0552', 'ST0553', 'ST0554', 'ST0555', 'ST0556', 'ST0557', 'ST0558', 'ST0559', 'ST0560', 'ST0561', 'ST0562', 'ST0563', 'ST0564', 'ST0565', 'ST0566', 'ST0567', 'ST0568', 'ST0569', 'ST0570', 'ST0571', 'ST0572', 'ST0573', 'ST0574', 'ST0575', 'ST0576', 'ST0577', 'ST0578', 'ST0579', 'ST0580', 'ST0581', 'ST0582', 'ST0583', 'ST0584', 'ST0585', 'ST0586', 'ST0587', 'ST0588', 'ST0589', 'ST0590', 'ST0591', 'ST0592', 'ST0593', 'ST0594', 'ST0595', 'ST0596', 'ST0597', 'ST0598', 'ST0599', 'ST0600', 'ST0601', 'ST0602', 'ST0603', 'ST0604', 'ST0605', 'ST0606', 'ST0607', 'ST0608', 'ST0609', 'ST0610', 'ST0611', 'ST0612', 'ST0613', 'ST0614', 'ST0615', 'ST0616', 'ST0617', 'ST0618', 'ST0619', 'ST0620', 'ST0621', 'ST0622', 'ST0623', 'ST0624', 'ST0625', 'ST0626', 'ST0627', 'ST0628', 'ST0629', 'ST0630', 'ST0631', 'ST0632', 'ST0633', 'ST0634', 'ST0635', 'ST0636', 'ST0637', 'ST0638', 'ST0639', 'ST0640', 'ST0641', 'ST0642', 'ST0643', 'ST0644', 'ST0645', 'ST0646', 'ST0647', 'ST0648', 'ST0649', 'ST0650', 'ST0651', 'ST0652', 'ST0653', 'ST0654', 'ST0655', 'ST0656', 'ST0657', 'ST0658', 'ST0659', 'ST0660', 'ST0661', 'ST0662', 'ST0663', 'ST0664', 'ST0665', 'ST0666', 'ST0667', 'ST0668', 'ST0669', 'ST0670', 'ST0671', 'ST0672', 'ST0673', 'ST0674', 'ST0675', 'ST0676', 'ST0677', 'ST0678', 'ST0679', 'ST0680', 'ST0681', 'ST0682', 'ST0683', 'ST0684', 'ST0685', 'ST0686', 'ST0687', 'ST0688', 'ST0689', 'ST0690', 'ST0691', 'ST0692', 'ST0693', 'ST0694', 'ST0695', 'ST0696', 'ST0697', 'ST0698', 'ST0699', 'ST0700', 'ST0701', 'ST0702', 'ST0703', 'ST0704', 'ST0705', 'ST0706', 'ST0707', 'ST0708', 'ST0709', 'ST0710', 'ST0711', 'ST0712', 'ST0713', 'ST0714', 'ST0715', 'ST0716', 'ST0717', 'ST0718', 'ST0719', 'ST0720', 'ST0721', 'ST0722', 'ST0723', 'ST0724', 'ST0725', 'ST0726', 'ST0727', 'ST0728', 'ST0729', 'ST0730', 'ST0731', 'ST0732', 'ST0733', 'ST0734', 'ST0735', 'ST0736', 'ST0737', 'ST0738', 'ST0739', 'ST0740', 'ST0741', 'ST0742', 'ST0743', 'ST0744', 'ST0745', 'ST0746', 'ST0747', 'ST0748', 'ST0749', 'ST0750', 'ST0751', 'ST0752', 'ST0753', 'ST0754', 'ST0755', 'ST0756', 'ST0757', 'ST0758', 'ST0759', 'ST0760', 'ST0761', 'ST0762', 'ST0763', 'ST0764', 'ST0765', 'ST0766', 'ST0767', 'ST0768', 'ST0769', 'ST0770', 'ST0771', 'ST0772', 'ST0773', 'ST0774', 'ST0775', 'ST0776', 'ST0777', 'ST0778', 'ST0779', 'ST0780', 'ST0781', 'ST0782', 'ST0783', 'ST0784', 'ST0785', 'ST0786', 'ST0787', 'ST0788', 'ST0789', 'ST0790', 'ST0791', 'ST0792', 'ST0793', 'ST0794', 'ST0795', 'ST0796', 'ST0797', 'ST0798', 'ST0799', 'ST0800', 'ST0801', 'ST0802', 'ST0803', 'ST0804', 'ST0805', 'ST0806', 'ST0807', 'ST0808', 'ST0809', 'ST0810', 'ST0811', 'ST0812', 'ST0813', 'ST0814', 'ST0815', 'ST0816', 'ST0817', 'ST0818', 'ST0819', 'ST0820', 'ST0821', 'ST0822', 'ST0823', 'ST0824', 'ST0825', 'ST0826', 'ST0827', 'ST0828', 'ST0829', 'ST0830', 'ST0831', 'ST0832', 'ST0833', 'ST0834', 'ST0835', 'ST0836', 'ST0837', 'ST0838', 'ST0839', 'ST0840', 'ST0841', 'ST0842', 'ST0843', 'ST0844', 'ST0845', 'ST0846', 'ST0847', 'ST0848', 'ST0849', 'ST0850', 'ST0851', 'ST0852', 'ST0853', 'ST0854', 'ST0855', 'ST0856', 'ST0857', 'ST0858', 'ST0859', 'ST0860', 'ST0861', 'ST0862', 'ST0863', 'ST0864', 'ST0865', 'ST0866', 'ST0867', 'ST0868', 'ST0869', 'ST0870', 'ST0871', 'ST0872', 'ST0873', 'ST0874', 'ST0875', 'ST0876', 'ST0877', 'ST0878', 'ST0879', 'ST0880', 'ST0881', 'ST0882', 'ST0883', 'ST0884', 'ST0885', 'ST0886', 'ST0887', 'ST0888', 'ST0889', 'ST0890', 'ST0891', 'ST0892', 'ST0893', 'ST0894', 'ST0895', 'ST0896', 'ST0897', 'ST0898', 'ST0899', 'ST0900', 'ST0901', 'ST0902', 'ST0903', 'ST0904', 'ST0905', 'ST0906', 'ST0907', 'ST0908', 'ST0909', 'ST0910', 'ST0911', 'ST0912', 'ST0913', 'ST0914', 'ST0915', 'ST0916', 'ST0917', 'ST0918', 'ST0919', 'ST0920', 'ST0921', 'ST0922', 'ST0923', 'ST0924', 'ST0925', 'ST0926', 'ST0927', 'ST0928', 'ST0929', 'ST0930', 'ST0931', 'ST0932', 'ST0933', 'ST0934');

-- 역-노선은 지웠다 다시 넣는다 — 한 트랜잭션이라 중간에 노선 없는 역이 보이지 않는다. (별칭에 ssl 을 쓰지 않는다 — MySQL 예약어다)
START TRANSACTION;

DELETE link
FROM subway_station_lines link
         JOIN subway_stations s ON s.id = link.station_id
WHERE s.code IN ('ST0001', 'ST0002', 'ST0003', 'ST0004', 'ST0005', 'ST0006', 'ST0007', 'ST0008', 'ST0009', 'ST0010', 'ST0011', 'ST0012', 'ST0013', 'ST0014', 'ST0015', 'ST0016', 'ST0017', 'ST0018', 'ST0019', 'ST0020', 'ST0021', 'ST0022', 'ST0023', 'ST0024', 'ST0025', 'ST0026', 'ST0027', 'ST0028', 'ST0029', 'ST0030', 'ST0031', 'ST0032', 'ST0033', 'ST0034', 'ST0035', 'ST0036', 'ST0037', 'ST0038', 'ST0039', 'ST0040', 'ST0041', 'ST0042', 'ST0043', 'ST0044', 'ST0045', 'ST0046', 'ST0047', 'ST0048', 'ST0049', 'ST0050', 'ST0051', 'ST0052', 'ST0053', 'ST0054', 'ST0055', 'ST0056', 'ST0057', 'ST0058', 'ST0059', 'ST0060', 'ST0061', 'ST0062', 'ST0063', 'ST0064', 'ST0065', 'ST0066', 'ST0067', 'ST0068', 'ST0069', 'ST0070', 'ST0071', 'ST0072', 'ST0073', 'ST0074', 'ST0075', 'ST0076', 'ST0077', 'ST0078', 'ST0079', 'ST0080', 'ST0081', 'ST0082', 'ST0083', 'ST0084', 'ST0085', 'ST0086', 'ST0087', 'ST0088', 'ST0089', 'ST0090', 'ST0091', 'ST0092', 'ST0093', 'ST0094', 'ST0095', 'ST0096', 'ST0097', 'ST0098', 'ST0099', 'ST0100', 'ST0101', 'ST0102', 'ST0103', 'ST0104', 'ST0105', 'ST0106', 'ST0107', 'ST0108', 'ST0109', 'ST0110', 'ST0111', 'ST0112', 'ST0113', 'ST0114', 'ST0115', 'ST0116', 'ST0117', 'ST0118', 'ST0119', 'ST0120', 'ST0121', 'ST0122', 'ST0123', 'ST0124', 'ST0125', 'ST0126', 'ST0127', 'ST0128', 'ST0129', 'ST0130', 'ST0131', 'ST0132', 'ST0133', 'ST0134', 'ST0135', 'ST0136', 'ST0137', 'ST0138', 'ST0139', 'ST0140', 'ST0141', 'ST0142', 'ST0143', 'ST0144', 'ST0145', 'ST0146', 'ST0147', 'ST0148', 'ST0149', 'ST0150', 'ST0151', 'ST0152', 'ST0153', 'ST0154', 'ST0155', 'ST0156', 'ST0157', 'ST0158', 'ST0159', 'ST0160', 'ST0161', 'ST0162', 'ST0163', 'ST0164', 'ST0165', 'ST0166', 'ST0167', 'ST0168', 'ST0169', 'ST0170', 'ST0171', 'ST0172', 'ST0173', 'ST0174', 'ST0175', 'ST0176', 'ST0177', 'ST0178', 'ST0179', 'ST0180', 'ST0181', 'ST0182', 'ST0183', 'ST0184', 'ST0185', 'ST0186', 'ST0187', 'ST0188', 'ST0189', 'ST0190', 'ST0191', 'ST0192', 'ST0193', 'ST0194', 'ST0195', 'ST0196', 'ST0197', 'ST0198', 'ST0199', 'ST0200', 'ST0201', 'ST0202', 'ST0203', 'ST0204', 'ST0205', 'ST0206', 'ST0207', 'ST0208', 'ST0209', 'ST0210', 'ST0211', 'ST0212', 'ST0213', 'ST0214', 'ST0215', 'ST0216', 'ST0217', 'ST0218', 'ST0219', 'ST0220', 'ST0221', 'ST0222', 'ST0223', 'ST0224', 'ST0225', 'ST0226', 'ST0227', 'ST0228', 'ST0229', 'ST0230', 'ST0231', 'ST0232', 'ST0233', 'ST0234', 'ST0235', 'ST0236', 'ST0237', 'ST0238', 'ST0239', 'ST0240', 'ST0241', 'ST0242', 'ST0243', 'ST0244', 'ST0245', 'ST0246', 'ST0247', 'ST0248', 'ST0249', 'ST0250', 'ST0251', 'ST0252', 'ST0253', 'ST0254', 'ST0255', 'ST0256', 'ST0257', 'ST0258', 'ST0259', 'ST0260', 'ST0261', 'ST0262', 'ST0263', 'ST0264', 'ST0265', 'ST0266', 'ST0267', 'ST0268', 'ST0269', 'ST0270', 'ST0271', 'ST0272', 'ST0273', 'ST0274', 'ST0275', 'ST0276', 'ST0277', 'ST0278', 'ST0279', 'ST0280', 'ST0281', 'ST0282', 'ST0283', 'ST0284', 'ST0285', 'ST0286', 'ST0287', 'ST0288', 'ST0289', 'ST0290', 'ST0291', 'ST0292', 'ST0293', 'ST0294', 'ST0295', 'ST0296', 'ST0297', 'ST0298', 'ST0299', 'ST0300', 'ST0301', 'ST0302', 'ST0303', 'ST0304', 'ST0305', 'ST0306', 'ST0307', 'ST0308', 'ST0309', 'ST0310', 'ST0311', 'ST0312', 'ST0313', 'ST0314', 'ST0315', 'ST0316', 'ST0317', 'ST0318', 'ST0319', 'ST0320', 'ST0321', 'ST0322', 'ST0323', 'ST0324', 'ST0325', 'ST0326', 'ST0327', 'ST0328', 'ST0329', 'ST0330', 'ST0331', 'ST0332', 'ST0333', 'ST0334', 'ST0335', 'ST0336', 'ST0337', 'ST0338', 'ST0339', 'ST0340', 'ST0341', 'ST0342', 'ST0343', 'ST0344', 'ST0345', 'ST0346', 'ST0347', 'ST0348', 'ST0349', 'ST0350', 'ST0351', 'ST0352', 'ST0353', 'ST0354', 'ST0355', 'ST0356', 'ST0357', 'ST0358', 'ST0359', 'ST0360', 'ST0361', 'ST0362', 'ST0363', 'ST0364', 'ST0365', 'ST0366', 'ST0367', 'ST0368', 'ST0369', 'ST0370', 'ST0371', 'ST0372', 'ST0373', 'ST0374', 'ST0375', 'ST0376', 'ST0377', 'ST0378', 'ST0379', 'ST0380', 'ST0381', 'ST0382', 'ST0383', 'ST0384', 'ST0385', 'ST0386', 'ST0387', 'ST0388', 'ST0389', 'ST0390', 'ST0391', 'ST0392', 'ST0393', 'ST0394', 'ST0395', 'ST0396', 'ST0397', 'ST0398', 'ST0399', 'ST0400', 'ST0401', 'ST0402', 'ST0403', 'ST0404', 'ST0405', 'ST0406', 'ST0407', 'ST0408', 'ST0409', 'ST0410', 'ST0411', 'ST0412', 'ST0413', 'ST0414', 'ST0415', 'ST0416', 'ST0417', 'ST0418', 'ST0419', 'ST0420', 'ST0421', 'ST0422', 'ST0423', 'ST0424', 'ST0425', 'ST0426', 'ST0427', 'ST0428', 'ST0429', 'ST0430', 'ST0431', 'ST0432', 'ST0433', 'ST0434', 'ST0435', 'ST0436', 'ST0437', 'ST0438', 'ST0439', 'ST0440', 'ST0441', 'ST0442', 'ST0443', 'ST0444', 'ST0445', 'ST0446', 'ST0447', 'ST0448', 'ST0449', 'ST0450', 'ST0451', 'ST0452', 'ST0453', 'ST0454', 'ST0455', 'ST0456', 'ST0457', 'ST0458', 'ST0459', 'ST0460', 'ST0461', 'ST0462', 'ST0463', 'ST0464', 'ST0465', 'ST0466', 'ST0467', 'ST0468', 'ST0469', 'ST0470', 'ST0471', 'ST0472', 'ST0473', 'ST0474', 'ST0475', 'ST0476', 'ST0477', 'ST0478', 'ST0479', 'ST0480', 'ST0481', 'ST0482', 'ST0483', 'ST0484', 'ST0485', 'ST0486', 'ST0487', 'ST0488', 'ST0489', 'ST0490', 'ST0491', 'ST0492', 'ST0493', 'ST0494', 'ST0495', 'ST0496', 'ST0497', 'ST0498', 'ST0499', 'ST0500', 'ST0501', 'ST0502', 'ST0503', 'ST0504', 'ST0505', 'ST0506', 'ST0507', 'ST0508', 'ST0509', 'ST0510', 'ST0511', 'ST0512', 'ST0513', 'ST0514', 'ST0515', 'ST0516', 'ST0517', 'ST0518', 'ST0519', 'ST0520', 'ST0521', 'ST0522', 'ST0523', 'ST0524', 'ST0525', 'ST0526', 'ST0527', 'ST0528', 'ST0529', 'ST0530', 'ST0531', 'ST0532', 'ST0533', 'ST0534', 'ST0535', 'ST0536', 'ST0537', 'ST0538', 'ST0539', 'ST0540', 'ST0541', 'ST0542', 'ST0543', 'ST0544', 'ST0545', 'ST0546', 'ST0547', 'ST0548', 'ST0549', 'ST0550', 'ST0551', 'ST0552', 'ST0553', 'ST0554', 'ST0555', 'ST0556', 'ST0557', 'ST0558', 'ST0559', 'ST0560', 'ST0561', 'ST0562', 'ST0563', 'ST0564', 'ST0565', 'ST0566', 'ST0567', 'ST0568', 'ST0569', 'ST0570', 'ST0571', 'ST0572', 'ST0573', 'ST0574', 'ST0575', 'ST0576', 'ST0577', 'ST0578', 'ST0579', 'ST0580', 'ST0581', 'ST0582', 'ST0583', 'ST0584', 'ST0585', 'ST0586', 'ST0587', 'ST0588', 'ST0589', 'ST0590', 'ST0591', 'ST0592', 'ST0593', 'ST0594', 'ST0595', 'ST0596', 'ST0597', 'ST0598', 'ST0599', 'ST0600', 'ST0601', 'ST0602', 'ST0603', 'ST0604', 'ST0605', 'ST0606', 'ST0607', 'ST0608', 'ST0609', 'ST0610', 'ST0611', 'ST0612', 'ST0613', 'ST0614', 'ST0615', 'ST0616', 'ST0617', 'ST0618', 'ST0619', 'ST0620', 'ST0621', 'ST0622', 'ST0623', 'ST0624', 'ST0625', 'ST0626', 'ST0627', 'ST0628', 'ST0629', 'ST0630', 'ST0631', 'ST0632', 'ST0633', 'ST0634', 'ST0635', 'ST0636', 'ST0637', 'ST0638', 'ST0639', 'ST0640', 'ST0641', 'ST0642', 'ST0643', 'ST0644', 'ST0645', 'ST0646', 'ST0647', 'ST0648', 'ST0649', 'ST0650', 'ST0651', 'ST0652', 'ST0653', 'ST0654', 'ST0655', 'ST0656', 'ST0657', 'ST0658', 'ST0659', 'ST0660', 'ST0661', 'ST0662', 'ST0663', 'ST0664', 'ST0665', 'ST0666', 'ST0667', 'ST0668', 'ST0669', 'ST0670', 'ST0671', 'ST0672', 'ST0673', 'ST0674', 'ST0675', 'ST0676', 'ST0677', 'ST0678', 'ST0679', 'ST0680', 'ST0681', 'ST0682', 'ST0683', 'ST0684', 'ST0685', 'ST0686', 'ST0687', 'ST0688', 'ST0689', 'ST0690', 'ST0691', 'ST0692', 'ST0693', 'ST0694', 'ST0695', 'ST0696', 'ST0697', 'ST0698', 'ST0699', 'ST0700', 'ST0701', 'ST0702', 'ST0703', 'ST0704', 'ST0705', 'ST0706', 'ST0707', 'ST0708', 'ST0709', 'ST0710', 'ST0711', 'ST0712', 'ST0713', 'ST0714', 'ST0715', 'ST0716', 'ST0717', 'ST0718', 'ST0719', 'ST0720', 'ST0721', 'ST0722', 'ST0723', 'ST0724', 'ST0725', 'ST0726', 'ST0727', 'ST0728', 'ST0729', 'ST0730', 'ST0731', 'ST0732', 'ST0733', 'ST0734', 'ST0735', 'ST0736', 'ST0737', 'ST0738', 'ST0739', 'ST0740', 'ST0741', 'ST0742', 'ST0743', 'ST0744', 'ST0745', 'ST0746', 'ST0747', 'ST0748', 'ST0749', 'ST0750', 'ST0751', 'ST0752', 'ST0753', 'ST0754', 'ST0755', 'ST0756', 'ST0757', 'ST0758', 'ST0759', 'ST0760', 'ST0761', 'ST0762', 'ST0763', 'ST0764', 'ST0765', 'ST0766', 'ST0767', 'ST0768', 'ST0769', 'ST0770', 'ST0771', 'ST0772', 'ST0773', 'ST0774', 'ST0775', 'ST0776', 'ST0777', 'ST0778', 'ST0779', 'ST0780', 'ST0781', 'ST0782', 'ST0783', 'ST0784', 'ST0785', 'ST0786', 'ST0787', 'ST0788', 'ST0789', 'ST0790', 'ST0791', 'ST0792', 'ST0793', 'ST0794', 'ST0795', 'ST0796', 'ST0797', 'ST0798', 'ST0799', 'ST0800', 'ST0801', 'ST0802', 'ST0803', 'ST0804', 'ST0805', 'ST0806', 'ST0807', 'ST0808', 'ST0809', 'ST0810', 'ST0811', 'ST0812', 'ST0813', 'ST0814', 'ST0815', 'ST0816', 'ST0817', 'ST0818', 'ST0819', 'ST0820', 'ST0821', 'ST0822', 'ST0823', 'ST0824', 'ST0825', 'ST0826', 'ST0827', 'ST0828', 'ST0829', 'ST0830', 'ST0831', 'ST0832', 'ST0833', 'ST0834', 'ST0835', 'ST0836', 'ST0837', 'ST0838', 'ST0839', 'ST0840', 'ST0841', 'ST0842', 'ST0843', 'ST0844', 'ST0845', 'ST0846', 'ST0847', 'ST0848', 'ST0849', 'ST0850', 'ST0851', 'ST0852', 'ST0853', 'ST0854', 'ST0855', 'ST0856', 'ST0857', 'ST0858', 'ST0859', 'ST0860', 'ST0861', 'ST0862', 'ST0863', 'ST0864', 'ST0865', 'ST0866', 'ST0867', 'ST0868', 'ST0869', 'ST0870', 'ST0871', 'ST0872', 'ST0873', 'ST0874', 'ST0875', 'ST0876', 'ST0877', 'ST0878', 'ST0879', 'ST0880', 'ST0881', 'ST0882', 'ST0883', 'ST0884', 'ST0885', 'ST0886', 'ST0887', 'ST0888', 'ST0889', 'ST0890', 'ST0891', 'ST0892', 'ST0893', 'ST0894', 'ST0895', 'ST0896', 'ST0897', 'ST0898', 'ST0899', 'ST0900', 'ST0901', 'ST0902', 'ST0903', 'ST0904', 'ST0905', 'ST0906', 'ST0907', 'ST0908', 'ST0909', 'ST0910', 'ST0911', 'ST0912', 'ST0913', 'ST0914', 'ST0915', 'ST0916', 'ST0917', 'ST0918', 'ST0919', 'ST0920', 'ST0921', 'ST0922', 'ST0923', 'ST0924', 'ST0925', 'ST0926', 'ST0927', 'ST0928', 'ST0929', 'ST0930', 'ST0931', 'ST0932', 'ST0933', 'ST0934');

INSERT INTO subway_station_lines (station_id, line_id, sort_order)
SELECT s.id, l.id, x.sort_order
FROM (SELECT 'ST0001' AS station_code, 'SEL-1' AS line_code, 1 AS sort_order
      UNION ALL SELECT 'ST0001', 'SEL-7', 2
      UNION ALL SELECT 'ST0002', 'SEL-1', 1
      UNION ALL SELECT 'ST0003', 'SEL-1', 1
      UNION ALL SELECT 'ST0003', 'GYEONGCHUN', 2
      UNION ALL SELECT 'ST0004', 'SEL-1', 1
      UNION ALL SELECT 'ST0005', 'SEL-1', 1
      UNION ALL SELECT 'ST0006', 'SEL-1', 1
      UNION ALL SELECT 'ST0007', 'SEL-1', 1
      UNION ALL SELECT 'ST0008', 'SEL-1', 1
      UNION ALL SELECT 'ST0008', 'SEL-9', 2
      UNION ALL SELECT 'ST0009', 'SEL-1', 1
      UNION ALL SELECT 'ST0010', 'SEL-1', 1
      UNION ALL SELECT 'ST0010', 'SILLIM', 2
      UNION ALL SELECT 'ST0011', 'SEL-1', 1
      UNION ALL SELECT 'ST0012', 'SEL-1', 1
      UNION ALL SELECT 'ST0012', 'SEL-7', 2
      UNION ALL SELECT 'ST0013', 'SEL-1', 1
      UNION ALL SELECT 'ST0014', 'SEL-1', 1
      UNION ALL SELECT 'ST0014', 'SEL-4', 2
      UNION ALL SELECT 'ST0015', 'SEL-1', 1
      UNION ALL SELECT 'ST0015', 'SEL-6', 2
      UNION ALL SELECT 'ST0016', 'SEL-1', 1
      UNION ALL SELECT 'ST0017', 'SEL-1', 1
      UNION ALL SELECT 'ST0017', 'SEL-4', 2
      UNION ALL SELECT 'ST0017', 'GYEONGUI-JUNGANG', 3
      UNION ALL SELECT 'ST0017', 'AREX', 4
      UNION ALL SELECT 'ST0018', 'SEL-1', 1
      UNION ALL SELECT 'ST0018', 'SEL-6', 2
      UNION ALL SELECT 'ST0019', 'SEL-1', 1
      UNION ALL SELECT 'ST0019', 'SEL-2', 2
      UNION ALL SELECT 'ST0020', 'SEL-1', 1
      UNION ALL SELECT 'ST0020', 'SEL-5', 2
      UNION ALL SELECT 'ST0021', 'SEL-1', 1
      UNION ALL SELECT 'ST0021', 'SEL-2', 2
      UNION ALL SELECT 'ST0022', 'SEL-1', 1
      UNION ALL SELECT 'ST0022', 'SEL-2', 2
      UNION ALL SELECT 'ST0022', 'UI-SINSEOL', 3
      UNION ALL SELECT 'ST0023', 'SEL-1', 1
      UNION ALL SELECT 'ST0024', 'SEL-1', 1
      UNION ALL SELECT 'ST0025', 'SEL-1', 1
      UNION ALL SELECT 'ST0026', 'SEL-1', 1
      UNION ALL SELECT 'ST0026', 'SEL-7', 2
      UNION ALL SELECT 'ST0027', 'SEL-1', 1
      UNION ALL SELECT 'ST0028', 'SEL-1', 1
      UNION ALL SELECT 'ST0028', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0029', 'SEL-1', 1
      UNION ALL SELECT 'ST0030', 'SEL-1', 1
      UNION ALL SELECT 'ST0031', 'SEL-1', 1
      UNION ALL SELECT 'ST0032', 'SEL-1', 1
      UNION ALL SELECT 'ST0032', 'SEL-3', 2
      UNION ALL SELECT 'ST0032', 'SEL-5', 3
      UNION ALL SELECT 'ST0033', 'SEL-1', 1
      UNION ALL SELECT 'ST0034', 'SEL-1', 1
      UNION ALL SELECT 'ST0034', 'SEL-4', 2
      UNION ALL SELECT 'ST0035', 'SEL-1', 1
      UNION ALL SELECT 'ST0035', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0035', 'SUIN-BUNDANG', 3
      UNION ALL SELECT 'ST0035', 'GYEONGCHUN', 4
      UNION ALL SELECT 'ST0036', 'SEL-1', 1
      UNION ALL SELECT 'ST0036', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0036', 'GYEONGCHUN', 3
      UNION ALL SELECT 'ST0037', 'SEL-2', 1
      UNION ALL SELECT 'ST0037', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0038', 'SEL-2', 1
      UNION ALL SELECT 'ST0039', 'SEL-2', 1
      UNION ALL SELECT 'ST0039', 'SEL-7', 2
      UNION ALL SELECT 'ST0040', 'SEL-2', 1
      UNION ALL SELECT 'ST0040', 'SEL-3', 2
      UNION ALL SELECT 'ST0041', 'SEL-2', 1
      UNION ALL SELECT 'ST0042', 'SEL-2', 1
      UNION ALL SELECT 'ST0043', 'SEL-2', 1
      UNION ALL SELECT 'ST0043', 'SEL-5', 2
      UNION ALL SELECT 'ST0044', 'SEL-2', 1
      UNION ALL SELECT 'ST0045', 'SEL-2', 1
      UNION ALL SELECT 'ST0045', 'SEL-9', 2
      UNION ALL SELECT 'ST0046', 'SEL-2', 1
      UNION ALL SELECT 'ST0046', 'SEL-7', 2
      UNION ALL SELECT 'ST0047', 'SEL-2', 1
      UNION ALL SELECT 'ST0048', 'SEL-2', 1
      UNION ALL SELECT 'ST0048', 'SEL-4', 2
      UNION ALL SELECT 'ST0048', 'SEL-5', 3
      UNION ALL SELECT 'ST0049', 'SEL-2', 1
      UNION ALL SELECT 'ST0050', 'SEL-2', 1
      UNION ALL SELECT 'ST0051', 'SEL-2', 1
      UNION ALL SELECT 'ST0052', 'SEL-2', 1
      UNION ALL SELECT 'ST0053', 'SEL-2', 1
      UNION ALL SELECT 'ST0053', 'SEL-4', 2
      UNION ALL SELECT 'ST0054', 'SEL-2', 1
      UNION ALL SELECT 'ST0055', 'SEL-2', 1
      UNION ALL SELECT 'ST0056', 'SEL-2', 1
      UNION ALL SELECT 'ST0057', 'SEL-2', 1
      UNION ALL SELECT 'ST0058', 'SEL-2', 1
      UNION ALL SELECT 'ST0058', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0059', 'SEL-2', 1
      UNION ALL SELECT 'ST0060', 'SEL-2', 1
      UNION ALL SELECT 'ST0061', 'SEL-2', 1
      UNION ALL SELECT 'ST0061', 'SEL-6', 2
      UNION ALL SELECT 'ST0062', 'SEL-2', 1
      UNION ALL SELECT 'ST0063', 'SEL-2', 1
      UNION ALL SELECT 'ST0063', 'SILLIM', 2
      UNION ALL SELECT 'ST0064', 'SEL-2', 1
      UNION ALL SELECT 'ST0065', 'SEL-2', 1
      UNION ALL SELECT 'ST0066', 'SEL-2', 1
      UNION ALL SELECT 'ST0067', 'SEL-2', 1
      UNION ALL SELECT 'ST0068', 'SEL-2', 1
      UNION ALL SELECT 'ST0069', 'SEL-2', 1
      UNION ALL SELECT 'ST0069', 'SEL-5', 2
      UNION ALL SELECT 'ST0070', 'SEL-2', 1
      UNION ALL SELECT 'ST0070', 'SEL-5', 2
      UNION ALL SELECT 'ST0070', 'GYEONGUI-JUNGANG', 3
      UNION ALL SELECT 'ST0070', 'SUIN-BUNDANG', 4
      UNION ALL SELECT 'ST0071', 'SEL-2', 1
      UNION ALL SELECT 'ST0072', 'SEL-2', 1
      UNION ALL SELECT 'ST0073', 'SEL-2', 1
      UNION ALL SELECT 'ST0073', 'SEL-3', 2
      UNION ALL SELECT 'ST0074', 'SEL-2', 1
      UNION ALL SELECT 'ST0074', 'SEL-5', 2
      UNION ALL SELECT 'ST0075', 'SEL-2', 1
      UNION ALL SELECT 'ST0076', 'SEL-2', 1
      UNION ALL SELECT 'ST0077', 'SEL-2', 1
      UNION ALL SELECT 'ST0077', 'SEL-8', 2
      UNION ALL SELECT 'ST0078', 'SEL-2', 1
      UNION ALL SELECT 'ST0079', 'SEL-2', 1
      UNION ALL SELECT 'ST0080', 'SEL-2', 1
      UNION ALL SELECT 'ST0080', 'SEL-9', 2
      UNION ALL SELECT 'ST0081', 'SEL-2', 1
      UNION ALL SELECT 'ST0081', 'SEL-5', 2
      UNION ALL SELECT 'ST0082', 'SEL-2', 1
      UNION ALL SELECT 'ST0083', 'SEL-2', 1
      UNION ALL SELECT 'ST0083', 'SEL-6', 2
      UNION ALL SELECT 'ST0084', 'SEL-2', 1
      UNION ALL SELECT 'ST0084', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0084', 'AREX', 3
      UNION ALL SELECT 'ST0085', 'SEL-3', 1
      UNION ALL SELECT 'ST0085', 'SEL-8', 2
      UNION ALL SELECT 'ST0086', 'SEL-3', 1
      UNION ALL SELECT 'ST0087', 'SEL-3', 1
      UNION ALL SELECT 'ST0088', 'SEL-3', 1
      UNION ALL SELECT 'ST0088', 'SEL-7', 2
      UNION ALL SELECT 'ST0088', 'SEL-9', 3
      UNION ALL SELECT 'ST0089', 'SEL-3', 1
      UNION ALL SELECT 'ST0090', 'SEL-3', 1
      UNION ALL SELECT 'ST0091', 'SEL-3', 1
      UNION ALL SELECT 'ST0092', 'SEL-3', 1
      UNION ALL SELECT 'ST0093', 'SEL-3', 1
      UNION ALL SELECT 'ST0094', 'SEL-3', 1
      UNION ALL SELECT 'ST0095', 'SEL-3', 1
      UNION ALL SELECT 'ST0095', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0096', 'SEL-3', 1
      UNION ALL SELECT 'ST0097', 'SEL-3', 1
      UNION ALL SELECT 'ST0098', 'SEL-3', 1
      UNION ALL SELECT 'ST0099', 'SEL-3', 1
      UNION ALL SELECT 'ST0100', 'SEL-3', 1
      UNION ALL SELECT 'ST0100', 'SEL-6', 2
      UNION ALL SELECT 'ST0101', 'SEL-3', 1
      UNION ALL SELECT 'ST0101', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0102', 'SEL-3', 1
      UNION ALL SELECT 'ST0102', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0103', 'SEL-3', 1
      UNION ALL SELECT 'ST0104', 'SEL-3', 1
      UNION ALL SELECT 'ST0105', 'SEL-3', 1
      UNION ALL SELECT 'ST0105', 'SEL-6', 2
      UNION ALL SELECT 'ST0106', 'SEL-3', 1
      UNION ALL SELECT 'ST0106', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0107', 'SEL-3', 1
      UNION ALL SELECT 'ST0107', 'SEL-6', 2
      UNION ALL SELECT 'ST0108', 'SEL-3', 1
      UNION ALL SELECT 'ST0108', 'SEL-5', 2
      UNION ALL SELECT 'ST0109', 'SEL-3', 1
      UNION ALL SELECT 'ST0109', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0110', 'SEL-3', 1
      UNION ALL SELECT 'ST0111', 'SEL-3', 1
      UNION ALL SELECT 'ST0112', 'SEL-3', 1
      UNION ALL SELECT 'ST0112', 'SEL-4', 2
      UNION ALL SELECT 'ST0113', 'SEL-3', 1
      UNION ALL SELECT 'ST0114', 'SEL-3', 1
      UNION ALL SELECT 'ST0115', 'SEL-4', 1
      UNION ALL SELECT 'ST0116', 'SEL-4', 1
      UNION ALL SELECT 'ST0117', 'SEL-4', 1
      UNION ALL SELECT 'ST0117', 'SEL-7', 2
      UNION ALL SELECT 'ST0118', 'SEL-4', 1
      UNION ALL SELECT 'ST0118', 'SEL-9', 2
      UNION ALL SELECT 'ST0119', 'SEL-4', 1
      UNION ALL SELECT 'ST0120', 'SEL-4', 1
      UNION ALL SELECT 'ST0121', 'SEL-4', 1
      UNION ALL SELECT 'ST0122', 'SEL-4', 1
      UNION ALL SELECT 'ST0123', 'SEL-4', 1
      UNION ALL SELECT 'ST0123', 'SEL-6', 2
      UNION ALL SELECT 'ST0124', 'SEL-4', 1
      UNION ALL SELECT 'ST0125', 'SEL-4', 1
      UNION ALL SELECT 'ST0125', 'UI-SINSEOL', 2
      UNION ALL SELECT 'ST0126', 'SEL-4', 1
      UNION ALL SELECT 'ST0127', 'SEL-4', 1
      UNION ALL SELECT 'ST0128', 'SEL-4', 1
      UNION ALL SELECT 'ST0129', 'SEL-4', 1
      UNION ALL SELECT 'ST0130', 'SEL-4', 1
      UNION ALL SELECT 'ST0130', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0131', 'SEL-4', 1
      UNION ALL SELECT 'ST0131', 'SEL-7', 2
      UNION ALL SELECT 'ST0132', 'SEL-4', 1
      UNION ALL SELECT 'ST0133', 'SEL-4', 1
      UNION ALL SELECT 'ST0134', 'SEL-4', 1
      UNION ALL SELECT 'ST0135', 'SEL-5', 1
      UNION ALL SELECT 'ST0136', 'SEL-5', 1
      UNION ALL SELECT 'ST0137', 'SEL-5', 1
      UNION ALL SELECT 'ST0138', 'SEL-5', 1
      UNION ALL SELECT 'ST0139', 'SEL-5', 1
      UNION ALL SELECT 'ST0140', 'SEL-5', 1
      UNION ALL SELECT 'ST0141', 'SEL-5', 1
      UNION ALL SELECT 'ST0141', 'SEL-6', 2
      UNION ALL SELECT 'ST0141', 'GYEONGUI-JUNGANG', 3
      UNION ALL SELECT 'ST0141', 'AREX', 4
      UNION ALL SELECT 'ST0142', 'SEL-5', 1
      UNION ALL SELECT 'ST0143', 'SEL-5', 1
      UNION ALL SELECT 'ST0144', 'SEL-5', 1
      UNION ALL SELECT 'ST0144', 'SEL-7', 2
      UNION ALL SELECT 'ST0145', 'SEL-5', 1
      UNION ALL SELECT 'ST0146', 'SEL-5', 1
      UNION ALL SELECT 'ST0147', 'SEL-5', 1
      UNION ALL SELECT 'ST0147', 'SEL-9', 2
      UNION ALL SELECT 'ST0147', 'AREX', 3
      UNION ALL SELECT 'ST0147', 'SEOHAE', 4
      UNION ALL SELECT 'ST0147', 'GIMPO-GOLD', 5
      UNION ALL SELECT 'ST0148', 'SEL-5', 1
      UNION ALL SELECT 'ST0149', 'SEL-5', 1
      UNION ALL SELECT 'ST0150', 'SEL-5', 1
      UNION ALL SELECT 'ST0151', 'SEL-5', 1
      UNION ALL SELECT 'ST0152', 'SEL-5', 1
      UNION ALL SELECT 'ST0153', 'SEL-5', 1
      UNION ALL SELECT 'ST0154', 'SEL-5', 1
      UNION ALL SELECT 'ST0155', 'SEL-5', 1
      UNION ALL SELECT 'ST0156', 'SEL-5', 1
      UNION ALL SELECT 'ST0157', 'SEL-5', 1
      UNION ALL SELECT 'ST0158', 'SEL-5', 1
      UNION ALL SELECT 'ST0159', 'SEL-5', 1
      UNION ALL SELECT 'ST0160', 'SEL-5', 1
      UNION ALL SELECT 'ST0161', 'SEL-5', 1
      UNION ALL SELECT 'ST0162', 'SEL-5', 1
      UNION ALL SELECT 'ST0163', 'SEL-5', 1
      UNION ALL SELECT 'ST0164', 'SEL-5', 1
      UNION ALL SELECT 'ST0165', 'SEL-5', 1
      UNION ALL SELECT 'ST0166', 'SEL-5', 1
      UNION ALL SELECT 'ST0167', 'SEL-5', 1
      UNION ALL SELECT 'ST0167', 'SEL-9', 2
      UNION ALL SELECT 'ST0168', 'SEL-5', 1
      UNION ALL SELECT 'ST0169', 'SEL-5', 1
      UNION ALL SELECT 'ST0170', 'SEL-5', 1
      UNION ALL SELECT 'ST0170', 'SEL-9', 2
      UNION ALL SELECT 'ST0171', 'SEL-5', 1
      UNION ALL SELECT 'ST0172', 'SEL-5', 1
      UNION ALL SELECT 'ST0173', 'SEL-5', 1
      UNION ALL SELECT 'ST0173', 'SEL-8', 2
      UNION ALL SELECT 'ST0174', 'SEL-5', 1
      UNION ALL SELECT 'ST0174', 'SEL-6', 2
      UNION ALL SELECT 'ST0175', 'SEL-5', 1
      UNION ALL SELECT 'ST0176', 'SEL-5', 1
      UNION ALL SELECT 'ST0177', 'SEL-6', 1
      UNION ALL SELECT 'ST0178', 'SEL-6', 1
      UNION ALL SELECT 'ST0179', 'SEL-6', 1
      UNION ALL SELECT 'ST0180', 'SEL-6', 1
      UNION ALL SELECT 'ST0181', 'SEL-6', 1
      UNION ALL SELECT 'ST0182', 'SEL-6', 1
      UNION ALL SELECT 'ST0183', 'SEL-6', 1
      UNION ALL SELECT 'ST0184', 'SEL-6', 1
      UNION ALL SELECT 'ST0184', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0184', 'AREX', 3
      UNION ALL SELECT 'ST0185', 'SEL-6', 1
      UNION ALL SELECT 'ST0186', 'SEL-6', 1
      UNION ALL SELECT 'ST0187', 'SEL-6', 1
      UNION ALL SELECT 'ST0188', 'SEL-6', 1
      UNION ALL SELECT 'ST0188', 'UI-SINSEOL', 2
      UNION ALL SELECT 'ST0189', 'SEL-6', 1
      UNION ALL SELECT 'ST0190', 'SEL-6', 1
      UNION ALL SELECT 'ST0191', 'SEL-6', 1
      UNION ALL SELECT 'ST0192', 'SEL-6', 1
      UNION ALL SELECT 'ST0193', 'SEL-6', 1
      UNION ALL SELECT 'ST0193', 'GYEONGCHUN', 2
      UNION ALL SELECT 'ST0194', 'SEL-6', 1
      UNION ALL SELECT 'ST0195', 'SEL-6', 1
      UNION ALL SELECT 'ST0196', 'SEL-6', 1
      UNION ALL SELECT 'ST0197', 'SEL-6', 1
      UNION ALL SELECT 'ST0198', 'SEL-6', 1
      UNION ALL SELECT 'ST0199', 'SEL-6', 1
      UNION ALL SELECT 'ST0200', 'SEL-6', 1
      UNION ALL SELECT 'ST0201', 'SEL-6', 1
      UNION ALL SELECT 'ST0202', 'SEL-6', 1
      UNION ALL SELECT 'ST0202', 'SEL-7', 2
      UNION ALL SELECT 'ST0203', 'SEL-6', 1
      UNION ALL SELECT 'ST0204', 'SEL-6', 1
      UNION ALL SELECT 'ST0205', 'SEL-6', 1
      UNION ALL SELECT 'ST0205', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0206', 'SEL-7', 1
      UNION ALL SELECT 'ST0206', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0207', 'SEL-7', 1
      UNION ALL SELECT 'ST0208', 'SEL-7', 1
      UNION ALL SELECT 'ST0209', 'SEL-7', 1
      UNION ALL SELECT 'ST0210', 'SEL-7', 1
      UNION ALL SELECT 'ST0211', 'SEL-7', 1
      UNION ALL SELECT 'ST0211', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0212', 'SEL-7', 1
      UNION ALL SELECT 'ST0213', 'SEL-7', 1
      UNION ALL SELECT 'ST0214', 'SEL-7', 1
      UNION ALL SELECT 'ST0215', 'SEL-7', 1
      UNION ALL SELECT 'ST0216', 'SEL-7', 1
      UNION ALL SELECT 'ST0216', 'SILLIM', 2
      UNION ALL SELECT 'ST0217', 'SEL-7', 1
      UNION ALL SELECT 'ST0218', 'SEL-7', 1
      UNION ALL SELECT 'ST0219', 'SEL-7', 1
      UNION ALL SELECT 'ST0219', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0219', 'GYEONGCHUN', 3
      UNION ALL SELECT 'ST0220', 'SEL-7', 1
      UNION ALL SELECT 'ST0221', 'SEL-7', 1
      UNION ALL SELECT 'ST0222', 'SEL-7', 1
      UNION ALL SELECT 'ST0223', 'SEL-7', 1
      UNION ALL SELECT 'ST0224', 'SEL-7', 1
      UNION ALL SELECT 'ST0225', 'SEL-7', 1
      UNION ALL SELECT 'ST0226', 'SEL-7', 1
      UNION ALL SELECT 'ST0227', 'SEL-7', 1
      UNION ALL SELECT 'ST0228', 'SEL-7', 1
      UNION ALL SELECT 'ST0229', 'SEL-7', 1
      UNION ALL SELECT 'ST0230', 'SEL-7', 1
      UNION ALL SELECT 'ST0231', 'SEL-7', 1
      UNION ALL SELECT 'ST0232', 'SEL-7', 1
      UNION ALL SELECT 'ST0233', 'SEL-7', 1
      UNION ALL SELECT 'ST0234', 'SEL-7', 1
      UNION ALL SELECT 'ST0235', 'SEL-8', 1
      UNION ALL SELECT 'ST0236', 'SEL-8', 1
      UNION ALL SELECT 'ST0237', 'SEL-8', 1
      UNION ALL SELECT 'ST0238', 'SEL-8', 1
      UNION ALL SELECT 'ST0238', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0239', 'SEL-8', 1
      UNION ALL SELECT 'ST0239', 'SEL-9', 2
      UNION ALL SELECT 'ST0240', 'SEL-8', 1
      UNION ALL SELECT 'ST0241', 'SEL-8', 1
      UNION ALL SELECT 'ST0242', 'SEL-8', 1
      UNION ALL SELECT 'ST0243', 'SEL-8', 1
      UNION ALL SELECT 'ST0244', 'SEL-9', 1
      UNION ALL SELECT 'ST0245', 'SEL-9', 1
      UNION ALL SELECT 'ST0246', 'SEL-9', 1
      UNION ALL SELECT 'ST0247', 'SEL-9', 1
      UNION ALL SELECT 'ST0248', 'SEL-9', 1
      UNION ALL SELECT 'ST0249', 'SEL-9', 1
      UNION ALL SELECT 'ST0250', 'SEL-9', 1
      UNION ALL SELECT 'ST0251', 'SEL-9', 1
      UNION ALL SELECT 'ST0252', 'SEL-9', 1
      UNION ALL SELECT 'ST0252', 'AREX', 2
      UNION ALL SELECT 'ST0253', 'SEL-9', 1
      UNION ALL SELECT 'ST0254', 'SEL-9', 1
      UNION ALL SELECT 'ST0255', 'SEL-9', 1
      UNION ALL SELECT 'ST0256', 'SEL-9', 1
      UNION ALL SELECT 'ST0257', 'SEL-9', 1
      UNION ALL SELECT 'ST0257', 'SILLIM', 2
      UNION ALL SELECT 'ST0258', 'SEL-9', 1
      UNION ALL SELECT 'ST0259', 'SEL-9', 1
      UNION ALL SELECT 'ST0260', 'SEL-9', 1
      UNION ALL SELECT 'ST0260', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0261', 'SEL-9', 1
      UNION ALL SELECT 'ST0262', 'SEL-9', 1
      UNION ALL SELECT 'ST0262', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0263', 'SEL-9', 1
      UNION ALL SELECT 'ST0264', 'SEL-9', 1
      UNION ALL SELECT 'ST0265', 'SEL-9', 1
      UNION ALL SELECT 'ST0266', 'SEL-9', 1
      UNION ALL SELECT 'ST0267', 'SEL-9', 1
      UNION ALL SELECT 'ST0268', 'SEL-9', 1
      UNION ALL SELECT 'ST0269', 'SEL-9', 1
      UNION ALL SELECT 'ST0270', 'SEL-9', 1
      UNION ALL SELECT 'ST0271', 'SEL-9', 1
      UNION ALL SELECT 'ST0272', 'SEL-9', 1
      UNION ALL SELECT 'ST0273', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0274', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0274', 'GYEONGCHUN', 2
      UNION ALL SELECT 'ST0275', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0276', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0277', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0278', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0279', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0280', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0280', 'GYEONGCHUN', 2
      UNION ALL SELECT 'ST0281', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0282', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0283', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0284', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0285', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0286', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0287', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0288', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0289', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0290', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0291', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0292', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0293', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0294', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0295', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0296', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0297', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0298', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0299', 'UI-SINSEOL', 1
      UNION ALL SELECT 'ST0300', 'SILLIM', 1
      UNION ALL SELECT 'ST0301', 'SILLIM', 1
      UNION ALL SELECT 'ST0302', 'SILLIM', 1
      UNION ALL SELECT 'ST0303', 'SILLIM', 1
      UNION ALL SELECT 'ST0304', 'SILLIM', 1
      UNION ALL SELECT 'ST0305', 'SILLIM', 1
      UNION ALL SELECT 'ST0306', 'SILLIM', 1
      UNION ALL SELECT 'ST0307', 'SEL-1', 1
      UNION ALL SELECT 'ST0308', 'SEL-1', 1
      UNION ALL SELECT 'ST0309', 'SEL-1', 1
      UNION ALL SELECT 'ST0310', 'SEL-1', 1
      UNION ALL SELECT 'ST0311', 'SEL-1', 1
      UNION ALL SELECT 'ST0311', 'SEL-4', 2
      UNION ALL SELECT 'ST0312', 'SEL-1', 1
      UNION ALL SELECT 'ST0313', 'SEL-1', 1
      UNION ALL SELECT 'ST0314', 'SEL-1', 1
      UNION ALL SELECT 'ST0315', 'SEL-1', 1
      UNION ALL SELECT 'ST0316', 'SEL-1', 1
      UNION ALL SELECT 'ST0317', 'SEL-1', 1
      UNION ALL SELECT 'ST0318', 'SEL-1', 1
      UNION ALL SELECT 'ST0319', 'SEL-1', 1
      UNION ALL SELECT 'ST0320', 'SEL-1', 1
      UNION ALL SELECT 'ST0321', 'SEL-1', 1
      UNION ALL SELECT 'ST0322', 'SEL-1', 1
      UNION ALL SELECT 'ST0323', 'SEL-1', 1
      UNION ALL SELECT 'ST0324', 'SEL-1', 1
      UNION ALL SELECT 'ST0325', 'SEL-1', 1
      UNION ALL SELECT 'ST0326', 'SEL-1', 1
      UNION ALL SELECT 'ST0327', 'SEL-1', 1
      UNION ALL SELECT 'ST0328', 'SEL-1', 1
      UNION ALL SELECT 'ST0329', 'SEL-1', 1
      UNION ALL SELECT 'ST0329', 'SEOHAE', 2
      UNION ALL SELECT 'ST0330', 'SEL-1', 1
      UNION ALL SELECT 'ST0331', 'SEL-1', 1
      UNION ALL SELECT 'ST0332', 'SEL-1', 1
      UNION ALL SELECT 'ST0333', 'SEL-1', 1
      UNION ALL SELECT 'ST0333', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0334', 'SEL-1', 1
      UNION ALL SELECT 'ST0335', 'SEL-1', 1
      UNION ALL SELECT 'ST0336', 'SEL-1', 1
      UNION ALL SELECT 'ST0337', 'SEL-1', 1
      UNION ALL SELECT 'ST0338', 'SEL-1', 1
      UNION ALL SELECT 'ST0339', 'SEL-1', 1
      UNION ALL SELECT 'ST0340', 'SEL-1', 1
      UNION ALL SELECT 'ST0341', 'SEL-1', 1
      UNION ALL SELECT 'ST0342', 'SEL-1', 1
      UNION ALL SELECT 'ST0343', 'SEL-1', 1
      UNION ALL SELECT 'ST0344', 'SEL-1', 1
      UNION ALL SELECT 'ST0345', 'SEL-1', 1
      UNION ALL SELECT 'ST0346', 'SEL-1', 1
      UNION ALL SELECT 'ST0347', 'SEL-1', 1
      UNION ALL SELECT 'ST0348', 'SEL-1', 1
      UNION ALL SELECT 'ST0349', 'SEL-1', 1
      UNION ALL SELECT 'ST0350', 'SEL-1', 1
      UNION ALL SELECT 'ST0350', 'UIJEONGBU', 2
      UNION ALL SELECT 'ST0351', 'SEL-3', 1
      UNION ALL SELECT 'ST0351', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0351', 'SEOHAE', 3
      UNION ALL SELECT 'ST0352', 'SEL-3', 1
      UNION ALL SELECT 'ST0353', 'SEL-3', 1
      UNION ALL SELECT 'ST0354', 'SEL-3', 1
      UNION ALL SELECT 'ST0355', 'SEL-3', 1
      UNION ALL SELECT 'ST0356', 'SEL-3', 1
      UNION ALL SELECT 'ST0357', 'SEL-3', 1
      UNION ALL SELECT 'ST0358', 'SEL-3', 1
      UNION ALL SELECT 'ST0359', 'SEL-3', 1
      UNION ALL SELECT 'ST0360', 'SEL-3', 1
      UNION ALL SELECT 'ST0361', 'SEL-3', 1
      UNION ALL SELECT 'ST0362', 'SEL-4', 1
      UNION ALL SELECT 'ST0363', 'SEL-4', 1
      UNION ALL SELECT 'ST0363', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0364', 'SEL-4', 1
      UNION ALL SELECT 'ST0365', 'SEL-4', 1
      UNION ALL SELECT 'ST0365', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0366', 'SEL-4', 1
      UNION ALL SELECT 'ST0367', 'SEL-4', 1
      UNION ALL SELECT 'ST0368', 'SEL-4', 1
      UNION ALL SELECT 'ST0369', 'SEL-4', 1
      UNION ALL SELECT 'ST0370', 'SEL-4', 1
      UNION ALL SELECT 'ST0371', 'SEL-4', 1
      UNION ALL SELECT 'ST0372', 'SEL-4', 1
      UNION ALL SELECT 'ST0373', 'SEL-4', 1
      UNION ALL SELECT 'ST0374', 'SEL-4', 1
      UNION ALL SELECT 'ST0375', 'SEL-4', 1
      UNION ALL SELECT 'ST0375', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0376', 'SEL-4', 1
      UNION ALL SELECT 'ST0377', 'SEL-4', 1
      UNION ALL SELECT 'ST0377', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0378', 'SEL-4', 1
      UNION ALL SELECT 'ST0379', 'SEL-4', 1
      UNION ALL SELECT 'ST0380', 'SEL-4', 1
      UNION ALL SELECT 'ST0380', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0381', 'SEL-4', 1
      UNION ALL SELECT 'ST0381', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0382', 'SEL-4', 1
      UNION ALL SELECT 'ST0383', 'SEL-4', 1
      UNION ALL SELECT 'ST0383', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0383', 'SEOHAE', 3
      UNION ALL SELECT 'ST0384', 'SEL-4', 1
      UNION ALL SELECT 'ST0385', 'SEL-4', 1
      UNION ALL SELECT 'ST0385', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0386', 'SEL-5', 1
      UNION ALL SELECT 'ST0387', 'SEL-5', 1
      UNION ALL SELECT 'ST0388', 'SEL-5', 1
      UNION ALL SELECT 'ST0389', 'SEL-5', 1
      UNION ALL SELECT 'ST0390', 'SEL-7', 1
      UNION ALL SELECT 'ST0391', 'SEL-7', 1
      UNION ALL SELECT 'ST0392', 'SEL-7', 1
      UNION ALL SELECT 'ST0393', 'SEL-7', 1
      UNION ALL SELECT 'ST0393', 'SEOHAE', 2
      UNION ALL SELECT 'ST0394', 'SEL-7', 1
      UNION ALL SELECT 'ST0395', 'SEL-7', 1
      UNION ALL SELECT 'ST0396', 'SEL-7', 1
      UNION ALL SELECT 'ST0397', 'SEL-7', 1
      UNION ALL SELECT 'ST0398', 'SEL-7', 1
      UNION ALL SELECT 'ST0399', 'SEL-8', 1
      UNION ALL SELECT 'ST0399', 'GYEONGUI-JUNGANG', 2
      UNION ALL SELECT 'ST0400', 'SEL-8', 1
      UNION ALL SELECT 'ST0401', 'SEL-8', 1
      UNION ALL SELECT 'ST0402', 'SEL-8', 1
      UNION ALL SELECT 'ST0403', 'SEL-8', 1
      UNION ALL SELECT 'ST0404', 'SEL-8', 1
      UNION ALL SELECT 'ST0405', 'SEL-8', 1
      UNION ALL SELECT 'ST0405', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0406', 'SEL-8', 1
      UNION ALL SELECT 'ST0406', 'GYEONGCHUN', 2
      UNION ALL SELECT 'ST0407', 'SEL-8', 1
      UNION ALL SELECT 'ST0408', 'SEL-8', 1
      UNION ALL SELECT 'ST0409', 'SEL-8', 1
      UNION ALL SELECT 'ST0410', 'SEL-8', 1
      UNION ALL SELECT 'ST0411', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0412', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0412', 'SEOHAE', 2
      UNION ALL SELECT 'ST0413', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0414', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0415', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0416', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0416', 'SEOHAE', 2
      UNION ALL SELECT 'ST0417', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0418', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0419', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0420', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0421', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0421', 'SEOHAE', 2
      UNION ALL SELECT 'ST0422', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0423', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0424', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0425', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0426', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0427', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0428', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0429', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0430', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0431', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0432', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0433', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0434', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0435', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0435', 'SEOHAE', 2
      UNION ALL SELECT 'ST0436', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0437', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0438', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0439', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0440', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0441', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0441', 'SEOHAE', 2
      UNION ALL SELECT 'ST0442', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0443', 'GYEONGUI-JUNGANG', 1
      UNION ALL SELECT 'ST0444', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0445', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0446', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0447', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0447', 'EVERLINE', 2
      UNION ALL SELECT 'ST0448', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0449', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0450', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0451', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0452', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0452', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0453', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0454', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0455', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0456', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0457', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0458', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0459', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0460', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0461', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0462', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0463', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0464', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0465', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0466', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0467', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0467', 'GYEONGGANG', 2
      UNION ALL SELECT 'ST0468', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0468', 'SHINBUNDANG', 2
      UNION ALL SELECT 'ST0469', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0470', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0471', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0472', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0473', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0474', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0475', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0476', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0477', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0478', 'SHINBUNDANG', 1
      UNION ALL SELECT 'ST0478', 'GYEONGGANG', 2
      UNION ALL SELECT 'ST0479', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0480', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0481', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0482', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0483', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0484', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0485', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0486', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0487', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0488', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0489', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0490', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0491', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0492', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0493', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0494', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0495', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0496', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0497', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0498', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0499', 'GYEONGGANG', 1
      UNION ALL SELECT 'ST0500', 'SEOHAE', 1
      UNION ALL SELECT 'ST0501', 'SEOHAE', 1
      UNION ALL SELECT 'ST0502', 'SEOHAE', 1
      UNION ALL SELECT 'ST0503', 'SEOHAE', 1
      UNION ALL SELECT 'ST0504', 'SEOHAE', 1
      UNION ALL SELECT 'ST0505', 'SEOHAE', 1
      UNION ALL SELECT 'ST0506', 'SEOHAE', 1
      UNION ALL SELECT 'ST0507', 'SEOHAE', 1
      UNION ALL SELECT 'ST0508', 'SEOHAE', 1
      UNION ALL SELECT 'ST0509', 'SEOHAE', 1
      UNION ALL SELECT 'ST0510', 'SEOHAE', 1
      UNION ALL SELECT 'ST0511', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0512', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0513', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0514', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0515', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0516', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0517', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0518', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0519', 'GIMPO-GOLD', 1
      UNION ALL SELECT 'ST0520', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0521', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0522', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0523', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0524', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0525', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0526', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0527', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0528', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0529', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0530', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0531', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0532', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0533', 'UIJEONGBU', 1
      UNION ALL SELECT 'ST0534', 'EVERLINE', 1
      UNION ALL SELECT 'ST0535', 'EVERLINE', 1
      UNION ALL SELECT 'ST0536', 'EVERLINE', 1
      UNION ALL SELECT 'ST0537', 'EVERLINE', 1
      UNION ALL SELECT 'ST0538', 'EVERLINE', 1
      UNION ALL SELECT 'ST0539', 'EVERLINE', 1
      UNION ALL SELECT 'ST0540', 'EVERLINE', 1
      UNION ALL SELECT 'ST0541', 'EVERLINE', 1
      UNION ALL SELECT 'ST0542', 'EVERLINE', 1
      UNION ALL SELECT 'ST0543', 'EVERLINE', 1
      UNION ALL SELECT 'ST0544', 'EVERLINE', 1
      UNION ALL SELECT 'ST0545', 'EVERLINE', 1
      UNION ALL SELECT 'ST0546', 'EVERLINE', 1
      UNION ALL SELECT 'ST0547', 'EVERLINE', 1
      UNION ALL SELECT 'ST0548', 'SEL-1', 1
      UNION ALL SELECT 'ST0549', 'SEL-1', 1
      UNION ALL SELECT 'ST0550', 'SEL-1', 1
      UNION ALL SELECT 'ST0551', 'SEL-1', 1
      UNION ALL SELECT 'ST0552', 'SEL-1', 1
      UNION ALL SELECT 'ST0553', 'SEL-1', 1
      UNION ALL SELECT 'ST0554', 'SEL-1', 1
      UNION ALL SELECT 'ST0555', 'SEL-1', 1
      UNION ALL SELECT 'ST0555', 'ICN-1', 2
      UNION ALL SELECT 'ST0556', 'SEL-1', 1
      UNION ALL SELECT 'ST0556', 'SUIN-BUNDANG', 2
      UNION ALL SELECT 'ST0557', 'SEL-1', 1
      UNION ALL SELECT 'ST0558', 'SEL-1', 1
      UNION ALL SELECT 'ST0558', 'ICN-2', 2
      UNION ALL SELECT 'ST0559', 'SEL-7', 1
      UNION ALL SELECT 'ST0560', 'SEL-7', 1
      UNION ALL SELECT 'ST0560', 'ICN-1', 2
      UNION ALL SELECT 'ST0561', 'SEL-7', 1
      UNION ALL SELECT 'ST0562', 'SEL-7', 1
      UNION ALL SELECT 'ST0563', 'SEL-7', 1
      UNION ALL SELECT 'ST0563', 'ICN-2', 2
      UNION ALL SELECT 'ST0564', 'AREX', 1
      UNION ALL SELECT 'ST0564', 'ICN-2', 2
      UNION ALL SELECT 'ST0565', 'AREX', 1
      UNION ALL SELECT 'ST0565', 'ICN-1', 2
      UNION ALL SELECT 'ST0566', 'AREX', 1
      UNION ALL SELECT 'ST0567', 'AREX', 1
      UNION ALL SELECT 'ST0568', 'AREX', 1
      UNION ALL SELECT 'ST0569', 'AREX', 1
      UNION ALL SELECT 'ST0570', 'AREX', 1
      UNION ALL SELECT 'ST0571', 'AREX', 1
      UNION ALL SELECT 'ST0572', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0573', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0574', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0575', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0576', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0577', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0578', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0578', 'ICN-1', 2
      UNION ALL SELECT 'ST0579', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0580', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0581', 'SUIN-BUNDANG', 1
      UNION ALL SELECT 'ST0582', 'ICN-1', 1
      UNION ALL SELECT 'ST0583', 'ICN-1', 1
      UNION ALL SELECT 'ST0584', 'ICN-1', 1
      UNION ALL SELECT 'ST0585', 'ICN-1', 1
      UNION ALL SELECT 'ST0586', 'ICN-1', 1
      UNION ALL SELECT 'ST0587', 'ICN-1', 1
      UNION ALL SELECT 'ST0588', 'ICN-1', 1
      UNION ALL SELECT 'ST0589', 'ICN-1', 1
      UNION ALL SELECT 'ST0590', 'ICN-1', 1
      UNION ALL SELECT 'ST0591', 'ICN-1', 1
      UNION ALL SELECT 'ST0592', 'ICN-1', 1
      UNION ALL SELECT 'ST0593', 'ICN-1', 1
      UNION ALL SELECT 'ST0594', 'ICN-1', 1
      UNION ALL SELECT 'ST0595', 'ICN-1', 1
      UNION ALL SELECT 'ST0596', 'ICN-1', 1
      UNION ALL SELECT 'ST0597', 'ICN-1', 1
      UNION ALL SELECT 'ST0598', 'ICN-1', 1
      UNION ALL SELECT 'ST0599', 'ICN-1', 1
      UNION ALL SELECT 'ST0600', 'ICN-1', 1
      UNION ALL SELECT 'ST0601', 'ICN-1', 1
      UNION ALL SELECT 'ST0602', 'ICN-1', 1
      UNION ALL SELECT 'ST0603', 'ICN-1', 1
      UNION ALL SELECT 'ST0604', 'ICN-1', 1
      UNION ALL SELECT 'ST0604', 'ICN-2', 2
      UNION ALL SELECT 'ST0605', 'ICN-1', 1
      UNION ALL SELECT 'ST0606', 'ICN-1', 1
      UNION ALL SELECT 'ST0607', 'ICN-1', 1
      UNION ALL SELECT 'ST0608', 'ICN-1', 1
      UNION ALL SELECT 'ST0609', 'ICN-1', 1
      UNION ALL SELECT 'ST0610', 'ICN-1', 1
      UNION ALL SELECT 'ST0611', 'ICN-2', 1
      UNION ALL SELECT 'ST0612', 'ICN-2', 1
      UNION ALL SELECT 'ST0613', 'ICN-2', 1
      UNION ALL SELECT 'ST0614', 'ICN-2', 1
      UNION ALL SELECT 'ST0615', 'ICN-2', 1
      UNION ALL SELECT 'ST0616', 'ICN-2', 1
      UNION ALL SELECT 'ST0617', 'ICN-2', 1
      UNION ALL SELECT 'ST0618', 'ICN-2', 1
      UNION ALL SELECT 'ST0619', 'ICN-2', 1
      UNION ALL SELECT 'ST0620', 'ICN-2', 1
      UNION ALL SELECT 'ST0621', 'ICN-2', 1
      UNION ALL SELECT 'ST0622', 'ICN-2', 1
      UNION ALL SELECT 'ST0623', 'ICN-2', 1
      UNION ALL SELECT 'ST0624', 'ICN-2', 1
      UNION ALL SELECT 'ST0625', 'ICN-2', 1
      UNION ALL SELECT 'ST0626', 'ICN-2', 1
      UNION ALL SELECT 'ST0627', 'ICN-2', 1
      UNION ALL SELECT 'ST0628', 'ICN-2', 1
      UNION ALL SELECT 'ST0629', 'ICN-2', 1
      UNION ALL SELECT 'ST0630', 'ICN-2', 1
      UNION ALL SELECT 'ST0631', 'ICN-2', 1
      UNION ALL SELECT 'ST0632', 'ICN-2', 1
      UNION ALL SELECT 'ST0633', 'ICN-2', 1
      UNION ALL SELECT 'ST0634', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0635', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0635', 'DONGHAE', 2
      UNION ALL SELECT 'ST0636', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0637', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0638', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0639', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0640', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0641', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0642', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0643', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0644', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0645', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0646', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0646', 'BUSAN-4', 2
      UNION ALL SELECT 'ST0646', 'DONGHAE', 3
      UNION ALL SELECT 'ST0647', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0648', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0649', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0650', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0651', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0652', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0653', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0654', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0655', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0656', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0656', 'DONGHAE', 2
      UNION ALL SELECT 'ST0657', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0658', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0659', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0659', 'BUSAN-2', 2
      UNION ALL SELECT 'ST0660', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0661', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0662', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0663', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0664', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0664', 'BUSAN-3', 2
      UNION ALL SELECT 'ST0665', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0666', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0667', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0668', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0669', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0670', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0671', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0672', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0673', 'BUSAN-1', 1
      UNION ALL SELECT 'ST0674', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0675', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0676', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0677', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0678', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0679', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0680', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0681', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0682', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0683', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0684', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0685', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0686', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0687', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0687', 'BUSAN-3', 2
      UNION ALL SELECT 'ST0688', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0689', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0690', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0691', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0692', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0693', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0694', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0695', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0696', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0697', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0697', 'DONGHAE', 2
      UNION ALL SELECT 'ST0698', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0699', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0699', 'BUSAN-GIMHAE', 2
      UNION ALL SELECT 'ST0700', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0701', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0701', 'BUSAN-3', 2
      UNION ALL SELECT 'ST0702', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0703', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0704', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0705', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0706', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0707', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0708', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0709', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0710', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0711', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0712', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0712', 'DONGHAE', 2
      UNION ALL SELECT 'ST0713', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0714', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0715', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0715', 'BUSAN-GIMHAE', 2
      UNION ALL SELECT 'ST0716', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0717', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0718', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0719', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0719', 'BUSAN-4', 2
      UNION ALL SELECT 'ST0720', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0721', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0722', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0723', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0724', 'BUSAN-3', 1
      UNION ALL SELECT 'ST0725', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0726', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0727', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0728', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0729', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0730', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0731', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0732', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0733', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0734', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0735', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0736', 'BUSAN-4', 1
      UNION ALL SELECT 'ST0737', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0738', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0739', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0740', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0741', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0742', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0743', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0744', 'DONGHAE', 1
      UNION ALL SELECT 'ST0745', 'DONGHAE', 1
      UNION ALL SELECT 'ST0746', 'DONGHAE', 1
      UNION ALL SELECT 'ST0747', 'DONGHAE', 1
      UNION ALL SELECT 'ST0748', 'DONGHAE', 1
      UNION ALL SELECT 'ST0749', 'DONGHAE', 1
      UNION ALL SELECT 'ST0750', 'DONGHAE', 1
      UNION ALL SELECT 'ST0751', 'DONGHAE', 1
      UNION ALL SELECT 'ST0752', 'DONGHAE', 1
      UNION ALL SELECT 'ST0753', 'DONGHAE', 1
      UNION ALL SELECT 'ST0754', 'DONGHAE', 1
      UNION ALL SELECT 'ST0755', 'DONGHAE', 1
      UNION ALL SELECT 'ST0756', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0757', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0758', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0759', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0759', 'DAEGYEONG', 2
      UNION ALL SELECT 'ST0760', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0761', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0762', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0763', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0763', 'DAEGYEONG', 2
      UNION ALL SELECT 'ST0764', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0765', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0765', 'DAEGU-3', 2
      UNION ALL SELECT 'ST0766', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0767', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0767', 'DAEGU-2', 2
      UNION ALL SELECT 'ST0768', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0769', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0770', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0771', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0772', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0773', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0774', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0775', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0776', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0777', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0778', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0779', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0780', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0781', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0782', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0783', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0784', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0785', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0786', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0787', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0788', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0789', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0790', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0791', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0792', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0793', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0794', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0795', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0796', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0797', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0798', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0799', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0800', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0801', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0802', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0803', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0804', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0805', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0806', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0807', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0808', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0809', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0810', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0811', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0812', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0813', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0813', 'DAEGU-3', 2
      UNION ALL SELECT 'ST0814', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0815', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0816', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0817', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0818', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0819', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0820', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0821', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0822', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0823', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0824', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0825', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0826', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0827', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0828', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0829', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0830', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0831', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0832', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0833', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0834', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0835', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0836', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0837', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0838', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0839', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0840', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0841', 'DAEGU-3', 1
      UNION ALL SELECT 'ST0842', 'DAEGYEONG', 1
      UNION ALL SELECT 'ST0843', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0844', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0845', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0846', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0847', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0848', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0849', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0850', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0851', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0852', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0853', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0854', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0855', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0856', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0857', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0858', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0859', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0860', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0861', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0862', 'GWANGJU-1', 1
      UNION ALL SELECT 'ST0863', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0864', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0865', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0866', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0867', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0868', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0869', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0870', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0871', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0872', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0873', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0874', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0875', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0876', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0877', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0878', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0879', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0880', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0881', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0882', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0883', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0884', 'DAEJEON-1', 1
      UNION ALL SELECT 'ST0885', 'DONGHAE', 1
      UNION ALL SELECT 'ST0886', 'DONGHAE', 1
      UNION ALL SELECT 'ST0887', 'DONGHAE', 1
      UNION ALL SELECT 'ST0888', 'DONGHAE', 1
      UNION ALL SELECT 'ST0889', 'DONGHAE', 1
      UNION ALL SELECT 'ST0890', 'DONGHAE', 1
      UNION ALL SELECT 'ST0891', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0892', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0893', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0894', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0895', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0896', 'GYEONGCHUN', 1
      UNION ALL SELECT 'ST0897', 'SEL-1', 1
      UNION ALL SELECT 'ST0898', 'SEL-1', 1
      UNION ALL SELECT 'ST0899', 'SEL-1', 1
      UNION ALL SELECT 'ST0900', 'SEL-1', 1
      UNION ALL SELECT 'ST0901', 'SEL-1', 1
      UNION ALL SELECT 'ST0902', 'SEL-1', 1
      UNION ALL SELECT 'ST0903', 'SEL-1', 1
      UNION ALL SELECT 'ST0904', 'SEL-1', 1
      UNION ALL SELECT 'ST0905', 'SEL-1', 1
      UNION ALL SELECT 'ST0906', 'SEL-1', 1
      UNION ALL SELECT 'ST0907', 'SEL-1', 1
      UNION ALL SELECT 'ST0908', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0909', 'DAEGU-1', 1
      UNION ALL SELECT 'ST0910', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0911', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0912', 'DAEGU-2', 1
      UNION ALL SELECT 'ST0913', 'DAEGYEONG', 1
      UNION ALL SELECT 'ST0914', 'DAEGYEONG', 1
      UNION ALL SELECT 'ST0915', 'DAEGYEONG', 1
      UNION ALL SELECT 'ST0916', 'DAEGYEONG', 1
      UNION ALL SELECT 'ST0917', 'DAEGYEONG', 1
      UNION ALL SELECT 'ST0918', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0919', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0920', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0921', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0922', 'BUSAN-2', 1
      UNION ALL SELECT 'ST0923', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0924', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0925', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0926', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0927', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0928', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0929', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0930', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0931', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0932', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0933', 'BUSAN-GIMHAE', 1
      UNION ALL SELECT 'ST0934', 'BUSAN-GIMHAE', 1) x
         JOIN subway_stations s ON s.code = x.station_code
         JOIN subway_lines l ON l.code = x.line_code;

COMMIT;
