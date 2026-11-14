#!/usr/bin/env python3
# =============================================================================
# dml/tools/gen_subway.py — 「전국도시철도역사정보표준데이터」 xlsx → dml/05_subway.sql (+ 역 코드 장부) — 전국
#
# 실행:  python3 -I dml/tools/gen_subway.py <원천.xlsx>            (dml/05_subway.sql 과 장부를 다시 쓴다)
#        python3 -I dml/tools/gen_subway.py <원천.xlsx> --check    (다시 만든 결과가 커밋본과 같은지만 본다)
#        --allow-retire : 장부보다 많이 숨겨지거나 같은 역이 새 코드를 받아도 쓴다(확인한 뒤에만)
#        --init         : 장부 없이 처음 만든다(생성 SQL 이 이미 있으면 이것 없이는 멈춘다)
# 표준 라이브러리만 쓴다. 같은 입력 + 같은 장부면 같은 바이트가 나온다. 서버 저장소 루트에서 실행한다.
#
# 원천: PO 가 준 「전국도시철도역사정보표준데이터」 칸 구성의 파일(전체_도시철도역사정보_20260630.xlsx). 원천 행 = (노선, 역) 하나.
#       포털(data.go.kr 15013205) 판본은 20241231 · 1,073행이라 이 파일과 다르다 — 받은 곳 · 이용 조건 확인 전(HUMAN_TODO #72).
# 노선색: PO 제공 표(2026-10-12) 그대로 — 서울시 노선 색표는 공공누리 4유형이라 쓰지 않는다. 대문자 #RRGGBB 로만 맞춘다.
#
# ★ 규칙 — 바꾸려면 아래 표를 고치고 다시 만든다.
#   1. 범위 = 전국(원천의 모든 시도) 역 · 아래 LINES 의 노선(PO 2026-10-13 — 처음 정한 수도권 23노선을 넓혔다).
#      인천공항 자기부상철도는 뺀다(D-90). 행의 (노선번호, 노선명)이 SOURCE_LINE 에 없으면 멈춘다 — 새 노선은 사람이 표에 넣는다.
#      주소로 시도를 못 정한 행이 있으면 멈춘다(SIDO_PREFIX · OPERATOR_SIDO · CITY_SIDO 에 넣는다) — 조용히 빠지지 않게.
#   2. 노선 = 승객이 보는 노선. 원천 노선번호와 1:1 이 아니다(I4101 = 1호선 + 경부선, 경원선 중 용산~왕십리 7역은 경의중앙선).
#   3. 역 = 지도 마커 하나. 원천은 환승역을 노선마다 다른 행 · 다른 역번호로 준다 → 묶는다:
#      이름(괄호 · 끝 '역' 뺌)이 같고 500m 안 · 또는 1km 안이면서 서로의 환승칸이 상대 노선을 적은 행 · 또는 괄호 부기가 상대 이름이고 500m 안.
#      같은 노선 · 역번호 · 좌표의 중복 행은 기준일자 최신 하나만 쓴다.
#   4. 역 좌표 = 묶은 행 좌표의 평균(소수 7자리). 틀린 것으로 확인한 행(BAD_COORD)은 빼고, 그것만 남는 역은 넣지 않고 보고한다.
#   5. 역 이름 = 대표 행(운영기관 → 노선 순서 → 역번호 우선순위)의 이름에서 괄호 부기를 빼고 '역'을 붙인다. 영문은 대표 행 영문명에서 끝 괄호를 뺀다.
#      단 이름이 다른 행을 묶은 역은 괄호를 남긴다(총신대입구(이수)역 — 괄호가 다른 노선의 역 이름이다).
#   6. 역 코드 ST0001… 은 장부(subway_station_codes.tsv)가 고정한다 — 원천 역번호는 운영기관마다 겹쳐 키가 못 된다.
#      다시 만들 때 이름이 같고 300m 안인 장부 행의 코드를 이어 쓴다. 처음 보는 역은 끝 번호 다음. 사라진 역의 코드는 재사용하지 않는다.
#   7. 원천에서 사라진 역 · 노선은 지우지 않고 숨긴다(is_active = 0). 다시 만들 때 장부보다 10역 넘게 숨겨지거나,
#      같은 이름 · 1km 안 역이 새 코드를 받으면 멈춘다(--allow-retire 로 넘긴다) — 앱이 들고 있는 역 코드가 깨진다.
#      장부에는 역에 묶인 이름을 모두 적는다(총신대입구|이수) — 대표 이름 하나만 적으면 원천 행 순서만 바뀌어도 새 코드가 된다.
#      장부 파일이 없는데 생성 SQL 이 있으면 멈춘다(처음부터 다시 매기면 코드가 다른 역을 가리킨다). 정말 처음이면 --init.
#   8. 틀린 원천 행은 DROP_ROWS(행을 뺀다) · NAME_EN_FIX(영문 오타 · 옛 이름)로 고친다. 키가 원천 값이라 원천이 고쳐지면 저절로 안 걸린다.
#      역에 묶인 행들의 환승칸이 적은 노선(23노선 안)이 역에 없으면 멈춘다 — EXTRA_LINES 로 더하거나 XFER_IGNORE 에 이유를 적는다.
#   9. V 마이그레이션에 세 테이블(필요 칸 포함)이 아직 없으면 dml/pending/ 에 쓴다 — CD 는 dml/*.sql 맨 위만 배포하므로
#      테이블보다 먼저 커밋돼도 배포가 ERROR 1146 으로 멈추지 않는다. 마이그레이션이 들어온 뒤 다시 돌리면 dml/05 로 옮겨 간다.
#   10. 역의 시군구(region_id)는 주소로 지역 장부에서 찾는다(전국 — region_lookup.py). 서울 역인데 못 찾으면 멈추고, 그 밖에 못 찾은 역은
#       NULL 로 두고 머리말에 적는다(개편으로 쪼개진 인천 옛 중구 · 서구 · 주소에 구도 동도 없는 역).
# 전화번호 칸은 읽지 않는다.
# =============================================================================
import collections
import datetime
import hashlib
import importlib.util
import math
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from decimal import Decimal, ROUND_HALF_UP

OUT = 'dml/05_subway.sql'
PENDING_OUT = 'dml/pending/05_subway.sql'
MIGRATIONS = 'src/main/resources/db/migration'
REQUIRED_COLUMNS = {
    'subway_lines': ['code', 'name_ko', 'name_en', 'color', 'is_active', 'sort_order'],
    'subway_stations': ['code', 'name_ko', 'name_en', 'lat', 'lng', 'region_id', 'is_active'],
    'subway_station_lines': ['station_id', 'line_id', 'sort_order'],
}
LEDGER = 'dml/tools/subway_station_codes.tsv'
SOURCE_ID = '「전국도시철도역사정보표준데이터」 칸 구성 · PO 제공 파일'
SOURCE_LICENSE = '받은 곳 · 이용 조건 확인 중(포털 판본 15013205 은 이용허락범위 제한 없음 — HUMAN_TODO #72)'
SHEET = '표준데이터 역사'
# 역 정렬 순서이자 범위 — 시도를 못 정한 행('?')은 멈춘다.
SCOPE = ('서울', '경기', '인천', '부산', '대구', '광주', '대전', '울산', '세종', '강원', '충북', '충남', '전북', '전남',
         '경북', '경남', '제주')
SAME_NAME_METERS = 500
TRANSFER_METERS = 1000
LEDGER_METERS = 300
RETIRE_LIMIT = 10
RECODE_METERS = 1000

# (코드, 이름, 영문, 색) — 순서가 역의 lines[] 순서다. 색은 PO 표(2026-10-12)의 그 노선 줄. 영문 이름은 관례 표기(확인 필요).
# 수도권 밖 노선은 뒤에 붙인다 — 앞 23개의 sort_order 를 밀지 않는다. 동해선 · 대경선 색은 PO 표의 '코레일 블루'(경강선과 같은 줄).
LINES = [
    ('SEL-1', '1호선', 'Line 1', '#0052A4'),
    ('SEL-2', '2호선', 'Line 2', '#00A84D'),
    ('SEL-3', '3호선', 'Line 3', '#EF7C1C'),
    ('SEL-4', '4호선', 'Line 4', '#00A4E3'),
    ('SEL-5', '5호선', 'Line 5', '#996CAC'),
    ('SEL-6', '6호선', 'Line 6', '#CD7C2F'),
    ('SEL-7', '7호선', 'Line 7', '#747F00'),
    ('SEL-8', '8호선', 'Line 8', '#E6186C'),
    ('SEL-9', '9호선', 'Line 9', '#BDB092'),
    ('GYEONGUI-JUNGANG', '경의중앙선', 'Gyeongui-Jungang Line', '#77C4A3'),
    ('AREX', '공항철도', 'AREX', '#0090D2'),
    ('SUIN-BUNDANG', '수인분당선', 'Suin-Bundang Line', '#FABE00'),
    ('SHINBUNDANG', '신분당선', 'Shinbundang Line', '#D20F46'),
    ('GYEONGCHUN', '경춘선', 'Gyeongchun Line', '#178C72'),
    ('GYEONGGANG', '경강선', 'Gyeonggang Line', '#0054A6'),
    ('SEOHAE', '서해선', 'Seohae Line', '#8FC31F'),
    ('UI-SINSEOL', '우이신설선', 'Ui-Sinseol Line', '#B7C450'),
    ('SILLIM', '신림선', 'Sillim Line', '#6789CA'),
    ('GIMPO-GOLD', '김포골드라인', 'Gimpo Goldline', '#AD8605'),
    ('UIJEONGBU', '의정부경전철', 'Uijeongbu LRT', '#F39C00'),
    ('EVERLINE', '용인에버라인', 'Yongin EverLine', '#56AD2D'),
    ('ICN-1', '인천 1호선', 'Incheon Line 1', '#759CCE'),
    ('ICN-2', '인천 2호선', 'Incheon Line 2', '#F5A251'),
    ('BUSAN-1', '부산 1호선', 'Busan Line 1', '#F06A00'),
    ('BUSAN-2', '부산 2호선', 'Busan Line 2', '#81BF48'),
    ('BUSAN-3', '부산 3호선', 'Busan Line 3', '#BB8C00'),
    ('BUSAN-4', '부산 4호선', 'Busan Line 4', '#217DCB'),
    ('BUSAN-GIMHAE', '부산김해경전철', 'Busan-Gimhae LRT', '#875CAC'),
    ('DONGHAE', '동해선', 'Donghae Line', '#0054A6'),
    ('DAEGU-1', '대구 1호선', 'Daegu Line 1', '#D93F5C'),
    ('DAEGU-2', '대구 2호선', 'Daegu Line 2', '#00AA80'),
    ('DAEGU-3', '대구 3호선', 'Daegu Line 3', '#FFB100'),
    ('DAEGYEONG', '대경선', 'Daegyeong Line', '#0054A6'),
    ('GWANGJU-1', '광주 1호선', 'Gwangju Line 1', '#009088'),
    ('DAEJEON-1', '대전 1호선', 'Daejeon Line 1', '#007448'),
]
LINE_ORDER = [code for code, _, _, _ in LINES]
EXCLUDED = 'EXCLUDED'

# 원천 (노선번호, 노선명) → 노선 코드. 이중 공백 같은 원천 표기는 공백을 하나로 줄인 뒤 찾는다.
SOURCE_LINE = {
    ('I4101', '1호선'): 'SEL-1', ('I4101', '경부선'): 'SEL-1', ('I1101', '경인선'): 'SEL-1',
    ('I4102', '경원선'): 'SEL-1', ('I4401', '장항선'): 'SEL-1', ('I4401', '경부선'): 'SEL-1',
    ('S1102', '2호선'): 'SEL-2', ('S1121', '2호선'): 'SEL-2', ('S1122', '2호선'): 'SEL-2',
    ('I1103', '3호선'): 'SEL-3', ('I4106', '일산선'): 'SEL-3',
    ('I1104', '4호선'): 'SEL-4', ('I4103', '안산과천선'): 'SEL-4', ('I4104', '진접선'): 'SEL-4',
    ('S1105', '5호선'): 'SEL-5', ('S1106', '6호선'): 'SEL-6',
    ('S1107', '7호선'): 'SEL-7', ('S1107', '도시철도 7호선'): 'SEL-7',
    ('S1108', '8호선'): 'SEL-8', ('S4108', '수도권 광역철도 8호선'): 'SEL-8',
    ('S1109', '서울 도시철도 9호선'): 'SEL-9', ('S1109', '수도권 도시철도 9호선'): 'SEL-9',
    ('I4105', '분당선'): 'SUIN-BUNDANG', ('I28K1', '수인선'): 'SUIN-BUNDANG',
    ('I11D1', '신분당선'): 'SHINBUNDANG',
    ('I4108', '경의중앙선'): 'GYEONGUI-JUNGANG', ('I4108', '경춘선'): 'GYEONGCHUN', ('I41K2', '경춘선'): 'GYEONGCHUN',
    ('I41K5', '경강선'): 'GYEONGGANG', ('I41WS', '서해선'): 'SEOHAE', ('I28A1', '인천국제공항선'): 'AREX',
    ('L11UI', '우이신설선'): 'UI-SINSEOL', ('L11SL', '수도권 경량도시철도 신림선'): 'SILLIM',
    ('L41G1', '김포도시철도'): 'GIMPO-GOLD', ('L41U1', '의정부'): 'UIJEONGBU', ('L41E1', '에버라인'): 'EVERLINE',
    ('S2801', '인천지하철 1호선'): 'ICN-1', ('S2802', '인천지하철 2호선'): 'ICN-2',
    ('S2601', '부산 도시철도 1호선'): 'BUSAN-1', ('S2602', '부산 도시철도 2호선'): 'BUSAN-2',
    ('S2603', '부산 도시철도 3호선'): 'BUSAN-3', ('L2604', '부산 경량도시철도 4호선'): 'BUSAN-4',
    ('L48B1', '부산김해경전철'): 'BUSAN-GIMHAE', ('I26K6', '동해선'): 'DONGHAE',
    ('S2701', '대구 도시철도 1호선'): 'DAEGU-1', ('S2702', '대구 도시철도 2호선'): 'DAEGU-2',
    ('S2703', '대구 도시철도 3호선'): 'DAEGU-3', ('I27K7', '대경선'): 'DAEGYEONG',
    ('S2901', '광주도시철도 1호선'): 'GWANGJU-1', ('S3001', '대전 도시철도 1호선'): 'DAEJEON-1',
    ('S28M1', '자기부상철도'): EXCLUDED,
}
# 경원선(I4102) 중 이 역들은 1호선이 아니라 경의중앙선이 다닌다 — 3호선 옥수 · 4호선 이촌 · 2/5호선 왕십리 행의 환승칸이
# '경의중앙'을 적고 1호선을 적지 않는다. 용산역(경부선) 행 환승칸도 경의중앙선. 서빙고 · 한남 · 응봉은 그 사이.
GYEONGWON_AS_GJ = {'용산', '이촌', '서빙고', '한남', '옥수', '응봉', '왕십리'}
# 역 행이 빠져 환승칸으로만 드러나는 노선 — 근거가 원천 안에 있는 것만 더한다.
#   까치산: 5호선 까치산 행 환승칸 '2호선'(신정지선 행은 도림천~신정네거리 3역뿐)
#   청량리: 1호선 · 경원선 청량리 행 환승칸 '분당' · '경춘' / 회기: 경원선 · 경의중앙선 회기 행 환승칸 '경춘선'
#           — 중랑 경춘선 행이 있어 경춘선이 청량리~회기~중랑~상봉으로 다닌다(경춘선 일부 열차 청량리 연장 운행)
EXTRA_LINES = {('서울', '까치산'): ['SEL-2'], ('서울', '청량리'): ['SUIN-BUNDANG', 'GYEONGCHUN'], ('서울', '회기'): ['GYEONGCHUN']}
# 환승칸에 적혔지만 23노선 범위 밖이라 일부러 넣지 않는 노선 — 이것 말고 환승칸과 역 노선이 어긋나면 멈춘다.
XFER_IGNORE = {'GTX-A': '수도권 광역급행철도 — 원천에 역 행이 없고 23노선 범위 밖', 'MAGLEV': '인천공항 자기부상철도 — 범위 밖(D-90)'}
# 틀린 원천 행 — (노선번호, 노선명, 역번호, 역사명): 이유. 원천에 그 행이 없어지면 알린다.
DROP_ROWS = {
    ('I4108', '경의중앙선', '1019', '광운대역'): '경의중앙선은 광운대에 서지 않는다 — 경원선 광운대 행 환승칸은 경춘선뿐이고 이 행 환승칸도 '
                                       '경춘선을 적지 않는다(중랑 · 상봉 · 망우 경의중앙 행은 적는다). 경춘선은 I4108 경춘선 행으로 따로 들어온다',
}
# 영문 역명 보정 — 키는 끝 괄호를 뺀 원천 영문. 원천이 고쳐지면 저절로 안 걸린다.
NAME_EN_FIX = {
    'Danggogae': 'Buramsan',  # 당고개 → 불암산 개명(2024-12) 뒤에도 원천 영문이 옛 이름. 로마자 표기법 그대로
    'Hangyang Univ. at Ansan': 'Hanyang Univ. at Ansan',  # 원천 오타 — 같은 역 안산과천선 행은 Hanyang
    'Seoul Regional Office of Millitary Manpower': 'Seoul Regional Office of Military Manpower',
    'Seoul-forest': 'Seoul Forest',
    'Dongducheon jungang': 'Dongducheon Jungang',
    'Seoul Grand park': 'Seoul Grand Park',
    'Seoul Racecourse park': 'Seoul Racecourse Park',
    'Bupyeong-gu office': 'Bupyeong-gu Office',  # 같은 역 인천 1호선 행은 Office
    'Seodaejeon negeori': 'Seodaejeon Negeori',  # 대전 1호선 — 다른 역은 낱말 첫 글자가 대문자
    'GwangjuSongjeong Station': 'Gwangju Songjeong Station',  # 광주 1호선 — 띄어쓰기 빠짐
}
# 좌표가 틀린 것으로 확인한 행 — (노선명, 역사명, 원천 위도, 원천 경도). 원천이 고쳐져 좌표가 달라지면 저절로 풀린다.
BAD_COORD = {
    ('4호선', '이촌(국립중앙박물관)', '37.529190', '126.967982'): '이웃 신용산 행과 14m — 신용산 좌표가 들어가 있다(이촌은 경원선 이촌역 행 좌표로 잡힌다)',
    ('5호선', '마곡', '37.558650', '126.837693'): '이웃 발산 행과 7m — 송정에서 2.3km(실제 송정~마곡 약 1.2km)라 마곡 쪽이 틀렸다',
    ('경의중앙선', '양원역', '36.963729', '129.091321'): '주소 서울 중랑구인데 좌표가 경북(서울 중심에서 195km)',
}
OPERATOR_PRIORITY = ['서울교통공사', '서울특별시 서울시메트로9호선㈜', '서울시메트로9호선㈜', '우이신설경전철운영㈜',
                     '남서울경전철 주식회사', '공항철도주식회사', '경기도 신분당선', '인천교통공사', '한국철도공사']
SIDO_PREFIX = [('서울', '서울'), ('경기', '경기'), ('인천', '인천'), ('부산', '부산'), ('대구', '대구'), ('대전', '대전'),
               ('광주', '광주'), ('울산', '울산'), ('세종', '세종'), ('강원', '강원'), ('충남', '충남'), ('충청남도', '충남'),
               ('충북', '충북'), ('충청북도', '충북'), ('경북', '경북'), ('경상북도', '경북'), ('경남', '경남'),
               ('경상남도', '경남'), ('전북', '전북'), ('전남', '전남'), ('제주', '제주')]
# 주소가 시도 없이 시군구로 시작하는 행 — 운영기관 기본 시도로 채우되, 시도가 다른 시군은 따로.
OPERATOR_SIDO = {'대구교통공사': '대구', '구리도시공사': '경기'}
CITY_SIDO = {'경산시': '경북'}

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}


def read_xlsx_sheet(path, name):
    z = zipfile.ZipFile(path)
    shared = []
    if 'xl/sharedStrings.xml' in z.namelist():
        for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', NS):
            shared.append(''.join(t.text or '' for t in si.iter('{%s}t' % NS['m'])))
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rels = {r.get('Id'): r.get('Target') for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    sheet = next((s for s in wb.find('m:sheets', NS) if s.get('name') == name), None)
    if sheet is None:
        sys.exit(f'원천에 시트 {name!r} 가 없다')
    target = rels[sheet.get('{%s}id' % NS['r'])].lstrip('/')
    target = target if target.startswith('xl/') else 'xl/' + target
    rows = []
    for row in ET.fromstring(z.read(target)).iter('{%s}row' % NS['m']):
        cells = {}
        for c in row.findall('m:c', NS):
            v = c.find('m:v', NS)
            kind = c.get('t')
            if kind == 'inlineStr':
                val = ''.join(x.text or '' for x in c.iter('{%s}t' % NS['m']))
            elif v is None:
                val = None
            elif kind == 's':
                val = shared[int(v.text)]
            else:
                val = v.text
            col = 0
            for ch in re.match(r'[A-Z]+', c.get('r')).group(0):
                col = col * 26 + ord(ch) - 64
            cells[col - 1] = val
        width = max(cells) + 1 if cells else 0
        rows.append([cells.get(i) for i in range(width)])
    return rows


def read_text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def read_bytes(path):
    with open(path, 'rb') as f:
        return f.read()


def clean(s):
    return re.sub(r'\s+', ' ', (s or '').replace('_x000D_', '').replace('\xa0', ' ')).strip()


def name_key(s):
    s = re.sub(r'\s+', '', re.sub(r'\([^)]*\)', '', clean(s)))
    return s[:-1] if s.endswith('역') and len(s) > 1 else s


def paren_aliases(s):
    out = []
    for m in re.findall(r'\(([^)]*)\)', clean(s)):
        m = re.sub(r'\s+', '', m)
        out.append(m[:-1] if m.endswith('역') and len(m) > 1 else m)
    return out


def clean_en(s):
    return clean(s).replace('\u2018', "'").replace('\u2019', "'")


def english(s):
    base = re.sub(r'\s*\([^)]*\)\s*$', '', clean_en(s)).strip()
    if base in NAME_EN_FIX:
        return NAME_EN_FIX[base]
    # 원천에 'hyochang park' 처럼 소문자로 시작하는 행이 있다 — 소문자로 시작하는 낱말의 첫 글자만 올린다.
    if base[:1].islower():
        base = ' '.join(w[:1].upper() + w[1:] if w[:1].islower() else w for w in base.split(' '))
    return base


def meters(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(math.sqrt(h))


def sido_of(address, operator):
    a = clean(address)
    for prefix, sido in SIDO_PREFIX:
        if a.startswith(prefix):
            return sido
    first = a.split(' ')[0] if a else ''
    return CITY_SIDO.get(first) or OPERATOR_SIDO.get(operator, '?')


def norm_date(v):
    v = clean(v)
    # 원천의 빈 날짜는 '1900-01-00'(엑셀 0일)로 온다.
    if not v or v.startswith('1900-01-00'):
        return ''
    if re.fullmatch(r'\d{5}', v):
        return (datetime.date(1899, 12, 30) + datetime.timedelta(days=int(v))).isoformat()
    if re.fullmatch(r'\d{8}', v):
        return f'{v[:4]}-{v[4:6]}-{v[6:]}'
    if re.fullmatch(r'\d{4}-\d{2}-\d{2}', v):
        return v
    return ''


CITY_LINE_PREFIX = {'': 'SEL-', '인천': 'ICN-', '부산': 'BUSAN-', '대구': 'DAEGU-', '광주': 'GWANGJU-', '대전': 'DAEJEON-'}


# 환승칸 한 칸에 노선이 여럿 붙어 올 수 있다('서울 도시철도 3호선 서울 도시철도 7호선'). 찾은 낱말은 지워 가며 모두 뽑는다 —
# 지우지 않으면 '신분당'에서 '분당'을 또 찾는다.
def transfer_lines(text):
    s = re.sub(r'\s+', '', text)
    found = set()
    for needle, code in (('GTX', 'GTX-A'), ('자기부상', 'MAGLEV'), ('부산김해', 'BUSAN-GIMHAE'), ('동해선', 'DONGHAE'),
                         ('대경선', 'DAEGYEONG'), ('신분당', 'SHINBUNDANG'), ('수인', 'SUIN-BUNDANG'),
                         ('분당', 'SUIN-BUNDANG'), ('경의', 'GYEONGUI-JUNGANG'), ('경춘', 'GYEONGCHUN'), ('경강', 'GYEONGGANG'),
                         ('서해', 'SEOHAE'), ('안산과천', 'SEL-4'), ('일산선', 'SEL-3'), ('진접', 'SEL-4'),
                         ('에버라인', 'EVERLINE'), ('김포', 'GIMPO-GOLD'), ('의정부경', 'UIJEONGBU'), ('신림', 'SILLIM'),
                         ('우이', 'UI-SINSEOL'), ('공항', 'AREX'), ('경인선', 'SEL-1'), ('경부선', 'SEL-1')):
        if needle in s:
            found.add(code)
            s = s.replace(needle, ' ')
    if s == '의정부':
        found.add('UIJEONGBU')
    # 도시 접두어가 붙은 'N호선' 은 그 도시 노선이다 — 접두어를 안 보면 '부산 도시철도 2호선' 이 서울 2호선이 된다.
    for city, n in re.findall(r'(인천|부산|대구|광주|대전)?(?:지하철|경량도시철도|도시철도)?(\d)호선', s):
        found.add(CITY_LINE_PREFIX[city] + n)
    return found


# 주소 → 시군구는 지역 장부(region_lookup.py · regions.tsv)가 정한다 — -I 는 이 폴더를 sys.path 에 넣지 않아 파일 경로로 읽는다.
def region_lookup():
    spec = importlib.util.spec_from_file_location('region_lookup', os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                                               'region_lookup.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load(path):
    rows = read_xlsx_sheet(path, SHEET)
    header = [clean(h) for h in rows[0]]
    need = ['역번호', '역사명', '노선번호', '노선명', '영문역사명', '환승노선명', '역위도', '역경도', '운영기관명',
            '역사도로명주소', '데이터기준일자']
    missing = [h for h in need if h not in header]
    if missing:
        sys.exit(f'원천 머리줄에 칸이 없다: {missing}')
    at = {h: header.index(h) for h in need}
    out = []
    for n, r in enumerate(rows[1:], start=2):
        if not any(r):
            continue
        get = lambda h: r[at[h]] if at[h] < len(r) else None
        row = {h: clean(get(h)) for h in need}
        row['_row'] = n
        row['pt'] = (float(row['역위도']), float(row['역경도']))
        row['sido'] = sido_of(row['역사도로명주소'], row['운영기관명'])
        row['key'] = name_key(row['역사명'])
        row['aliases'] = paren_aliases(row['역사명'])
        row['date'] = norm_date(row['데이터기준일자'])
        row['bad'] = BAD_COORD.get((row['노선명'], row['역사명'], f"{row['pt'][0]:.6f}", f"{row['pt'][1]:.6f}"))
        row['drop'] = DROP_ROWS.get((row['노선번호'], row['노선명'], row['역번호'], row['역사명']))
        out.append(row)
    stale = [k for k in DROP_ROWS if not any(r['drop'] and (r['노선번호'], r['노선명'], r['역번호'], r['역사명']) == k for r in out)]
    if stale:
        print(f'⚠ DROP_ROWS 에 원천에 없는 행이 있다(원천이 고쳐졌으면 표에서 뺀다): {stale}', file=sys.stderr)
    return out


def build(rows):
    dropped = [r for r in rows if r['drop']]
    nowhere = sorted({(r['역사명'], r['역사도로명주소'], r['운영기관명']) for r in rows if r['sido'] not in SCOPE})
    if nowhere:
        sys.exit(f'주소로 시도를 못 정한 행: {nowhere[:10]} — SIDO_PREFIX · OPERATOR_SIDO · CITY_SIDO 에 넣고 다시 만든다')
    scoped = [r for r in rows if not r['drop']]
    unknown = sorted({(r['노선번호'], r['노선명']) for r in scoped if (r['노선번호'], r['노선명']) not in SOURCE_LINE})
    if unknown:
        sys.exit(f'표에 없는 노선(범위 안): {unknown} — SOURCE_LINE 에 넣고 다시 만든다')
    for r in scoped:
        line = SOURCE_LINE[(r['노선번호'], r['노선명'])]
        if (r['노선번호'], r['노선명']) == ('I4102', '경원선') and r['key'] in GYEONGWON_AS_GJ:
            line = 'GYEONGUI-JUNGANG'
        r['line'] = line
    scoped = [r for r in scoped if r['line'] != EXCLUDED]

    groups = collections.defaultdict(list)
    for r in scoped:
        groups[(r['노선번호'], r['노선명'], r['역번호'], round(r['pt'][0], 6), round(r['pt'][1], 6))].append(r)
    kept, dropped_dups = [], 0
    for rs in groups.values():
        rs.sort(key=lambda x: (x['date'], -x['_row']), reverse=True)
        kept.append(rs[0])
        dropped_dups += len(rs) - 1
    kept.sort(key=lambda x: x['_row'])
    for r in kept:
        r['xfer'] = set()
        for part in re.split(r'[+,/]', r['환승노선명'] or ''):
            r['xfer'] |= transfer_lines(part)

    parent = {r['_row']: r['_row'] for r in kept}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    by_key = collections.defaultdict(list)
    for r in kept:
        by_key[r['key']].append(r)
    for rs in by_key.values():
        for i, a in enumerate(rs):
            for b in rs[i + 1:]:
                d = meters(a['pt'], b['pt'])
                if d <= SAME_NAME_METERS or (d <= TRANSFER_METERS and (b['line'] in a['xfer'] or a['line'] in b['xfer'])):
                    union(a['_row'], b['_row'])
    for a in kept:
        for alias in a['aliases']:
            for b in by_key.get(alias, []):
                if b is not a and meters(a['pt'], b['pt']) <= SAME_NAME_METERS:
                    union(a['_row'], b['_row'])

    clusters = collections.defaultdict(list)
    for r in kept:
        clusters[find(r['_row'])].append(r)

    # 원천 행 순서와 무관하게 고른다 — 행 순서가 바뀌어도 대표 이름 · 장부 대조가 흔들리지 않게.
    def priority(r):
        op = r['운영기관명']
        return (OPERATOR_PRIORITY.index(op) if op in OPERATOR_PRIORITY else len(OPERATOR_PRIORITY),
                LINE_ORDER.index(r['line']), r['역번호'], r['역사명'], r['_row'])

    stations, unplaced, xfer_gaps = [], [], []
    for rs in clusters.values():
        rep = min(rs, key=priority)
        good = [r for r in rs if not r['bad']]
        sido = collections.Counter(r['sido'] for r in rs).most_common(1)[0][0]
        lines = {r['line'] for r in rs} | set(EXTRA_LINES.get((sido, rep['key']), []))
        lines = sorted(lines, key=LINE_ORDER.index)
        gap = set().union(*(r['xfer'] for r in rs)) - set(lines) - set(XFER_IGNORE)
        if gap:
            xfer_gaps.append((rep['key'], sorted(gap)))
        if not good:
            unplaced.append((rep['key'] + '역', lines, [r['bad'] for r in rs]))
            continue
        lat = Decimal(repr(sum(r['pt'][0] for r in good) / len(good))).quantize(Decimal('0.0000001'), ROUND_HALF_UP)
        lng = Decimal(repr(sum(r['pt'][1] for r in good) / len(good))).quantize(Decimal('0.0000001'), ROUND_HALF_UP)
        # 이름이 다른 행을 묶은 역(총신대입구(이수) + 이수)은 괄호가 다른 노선의 역 이름이라 지우지 않는다 — 지우면 그 이름으로 못 찾는다.
        mixed = len({r['key'] for r in rs}) > 1
        full = re.sub(r'역$', '', clean(rep['역사명']))
        stations.append({
            'key': rep['key'], 'name_ko': (full if mixed else rep['key']) + '역',
            'name_en': clean_en(rep['영문역사명']) if mixed else english(rep['영문역사명']),
            'keys': sorted({r['key'] for r in rs}),
            'lat': lat, 'lng': lng, 'sido': sido, 'address': rep['역사도로명주소'], 'lines': lines,
            'dates': {r['date'] for r in rs if r['date']},
        })
    if xfer_gaps:
        sys.exit(f'환승칸이 적은 노선이 역에 없다 — EXTRA_LINES 로 더하거나 XFER_IGNORE 에 이유를 적는다: {sorted(xfer_gaps)}')
    stations.sort(key=lambda s: (SCOPE.index(s['sido']), LINE_ORDER.index(s['lines'][0]), s['key'], s['lat'], s['lng']))
    return stations, unplaced, {'source_rows': len(rows), 'scoped_rows': len(scoped), 'dup_rows': dropped_dups,
                                'dropped_rows': len(dropped)}


def assign_codes(stations, ledger_rows):
    taken = set()
    for s in stations:
        near = [l for l in ledger_rows if set(l['name_key'].split('|')) & set(s['keys']) and l['code'] not in taken
                and meters((float(l['lat']), float(l['lng'])), (float(s['lat']), float(s['lng']))) <= LEDGER_METERS]
        if near:
            best = min(near, key=lambda l: meters((float(l['lat']), float(l['lng'])), (float(s['lat']), float(s['lng']))))
            s['code'] = best['code']
            taken.add(best['code'])
    last = max((int(l['code'][2:]) for l in ledger_rows), default=0)
    for s in stations:
        if 'code' not in s:
            last += 1
            s['code'] = f'ST{last:04d}'
    current = {s['code']: s for s in stations}
    out = []
    for l in ledger_rows:
        if l['code'] in current:
            continue
        out.append(dict(l, active='0'))
    for s in stations:
        keys = '|'.join([s['key']] + [k for k in s['keys'] if k != s['key']])
        out.append({'code': s['code'], 'name_key': keys, 'lat': str(s['lat']), 'lng': str(s['lng']), 'active': '1'})
    out.sort(key=lambda l: l['code'])
    return out


def read_ledger(out):
    if not os.path.exists(LEDGER):
        if os.path.exists(out) and '--init' not in sys.argv:
            sys.exit(f'{LEDGER} 가 없는데 {out} 가 있다 — 장부 없이 다시 매기면 역 코드가 다른 역을 가리킨다. '
                     f'장부를 되살리거나(git), 정말 처음이면 --init')
        return []
    lines = read_text(LEDGER).splitlines()
    head = lines[0].split('\t')
    return [dict(zip(head, line.split('\t'))) for line in lines[1:] if line and not line.startswith('#')]


def render_ledger(rows):
    out = ['code\tname_key\tlat\tlng\tactive']
    out += [f"{r['code']}\t{r['name_key']}\t{r['lat']}\t{r['lng']}\t{r['active']}" for r in rows]
    return '\n'.join(out) + '\n'


def q(s):
    return 'NULL' if s is None else "'" + s.replace('\\', '\\\\').replace("'", "''") + "'"


def render(stations, unplaced, stats, sha, source_name, retired):
    rl = region_lookup()
    children, dongs = rl.load()
    used = [line for line in LINES if any(line[0] in s['lines'] for s in stations)]
    station_rows, no_region = [], []
    for s in stations:
        code = rl.region_of(s['address'], children, dongs, rl.sido_code(s['sido']))
        if code == rl.UNKNOWN or code is None:
            if s['sido'] == '서울':
                sys.exit(f"서울 역 주소에서 자치구를 못 찾았다: {s['name_ko']} {s['address']}")
            no_region.append(s['name_ko'])
        region = 'NULL' if code == rl.UNKNOWN or code is None else f"(SELECT id FROM regions WHERE code = '{code}')"
        if not s['name_en']:
            sys.exit(f"영문 역 이름이 비었다: {s['name_ko']}")
        station_rows.append(f"    ({q(s['code'])}, {q(s['name_ko'])}, {q(s['name_en'])}, {s['lat']}, {s['lng']}, {region}, 1)")
    pairs = [(s['code'], line, i) for s in stations for i, line in enumerate(s['lines'], start=1)]
    dates = sorted({d for s in stations for d in s['dates']})
    by_sido = collections.Counter(s['sido'] for s in stations)
    sido_text = ' · '.join(f'{sido} {by_sido[sido]}' for sido in SCOPE if by_sido[sido])
    transfer = sum(1 for s in stations if len(s['lines']) > 1)
    unplaced_text = ' · '.join(f"{name}({', '.join(lines)})" for name, lines, _ in unplaced) or '없음'
    head = f"""-- =============================================================================
-- dml/05_subway.sql — 지하철 노선 · 역 · 역-노선(전국 도시철도) · 생성 파일, 손으로 고치지 않는다
--
-- 만든 곳: dml/tools/gen_subway.py(규칙은 그 머리말) · 역 코드 장부 dml/tools/subway_station_codes.tsv
-- 원천: {SOURCE_ID} · {SOURCE_LICENSE}
--       파일 {source_name} · sha256 {sha} · 데이터기준일자 {dates[0]} ~ {dates[-1]}
-- 노선색: PO 제공 표(2026-10-12)
-- 행 수: 노선 {len(used)} · 역 {len(stations)}({sido_text}, 환승역 {transfer}) · 역-노선 {len(pairs)}
--        원천 {stats['source_rows']}행 중 넣은 노선 {stats['scoped_rows']}행(중복 {stats['dup_rows']}행 · 틀린 행 {stats['dropped_rows']}행(DROP_ROWS) · 자기부상 뺌)
-- 좌표가 틀려 넣지 않은 역: {unplaced_text} — 고친 좌표가 확인되면 넣는다
-- 시군구가 붙은 역 {len(station_rows) - len(no_region)} · 못 정한 역 {len(no_region)}: {' · '.join(no_region) or '없음'}
--
-- ⚠ subway_lines · subway_stations · subway_station_lines 를 만드는 V 마이그레이션이 먼저(또는 같은 커밋) 나가야 한다.
-- 멱등: 코드 upsert. 원천에서 사라진 역 · 노선은 지우지 않고 is_active = 0. 역-노선은 이번 역들의 것을 통째로 다시 맞춘다.
-- 실행: mysql --default-character-set=utf8mb4 <DB> < dml/05_subway.sql
-- =============================================================================

INSERT INTO subway_lines (code, name_ko, name_en, color, is_active, sort_order)
VALUES
"""
    lines_sql = ',\n'.join(f"    ({q(c)}, {q(ko)}, {q(en)}, {q(color)}, 1, {LINE_ORDER.index(c) + 1})" for c, ko, en, color in used)
    sql = head + lines_sql + """
ON DUPLICATE KEY UPDATE name_ko    = VALUES(name_ko),
                        name_en    = VALUES(name_en),
                        color      = VALUES(color),
                        is_active  = VALUES(is_active),
                        sort_order = VALUES(sort_order);

UPDATE subway_lines
SET is_active = 0
WHERE is_active = 1
  AND code NOT IN (""" + ', '.join(q(c) for c, _, _, _ in used) + """);

INSERT INTO subway_stations (code, name_ko, name_en, lat, lng, region_id, is_active)
VALUES
""" + ',\n'.join(station_rows) + """
ON DUPLICATE KEY UPDATE name_ko   = VALUES(name_ko),
                        name_en   = VALUES(name_en),
                        lat       = VALUES(lat),
                        lng       = VALUES(lng),
                        region_id = VALUES(region_id),
                        is_active = VALUES(is_active);

UPDATE subway_stations
SET is_active = 0
WHERE is_active = 1
  AND code NOT IN (""" + ', '.join(q(s['code']) for s in stations) + """);

-- 역-노선은 지웠다 다시 넣는다 — 한 트랜잭션이라 중간에 노선 없는 역이 보이지 않는다. (별칭에 ssl 을 쓰지 않는다 — MySQL 예약어다)
START TRANSACTION;

DELETE link
FROM subway_station_lines link
         JOIN subway_stations s ON s.id = link.station_id
WHERE s.code IN (""" + ', '.join(q(s['code']) for s in stations) + """);

INSERT INTO subway_station_lines (station_id, line_id, sort_order)
SELECT s.id, l.id, x.sort_order
FROM (""" + '\n      UNION ALL '.join(f"SELECT {q(a)} AS station_code, {q(b)} AS line_code, {i} AS sort_order" if n == 0
                                        else f"SELECT {q(a)}, {q(b)}, {i}" for n, (a, b, i) in enumerate(pairs)) + """) x
         JOIN subway_stations s ON s.code = x.station_code
         JOIN subway_lines l ON l.code = x.line_code;

COMMIT;
"""
    return sql


def schema_missing():
    text = ''
    if os.path.isdir(MIGRATIONS):
        for name in sorted(os.listdir(MIGRATIONS)):
            if re.fullmatch(r'V\d+__.+\.sql', name):
                text += read_text(os.path.join(MIGRATIONS, name)) + '\n'
    missing = []
    for table, columns in REQUIRED_COLUMNS.items():
        m = re.search(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?%s`?\s*\((.*?)\)\s*ENGINE' % table, text, re.I | re.S)
        if not m:
            missing.append(table)
            continue
        missing += [f'{table}.{c}' for c in columns if not re.search(r'^\s*`?%s`?\s' % c, m.group(1), re.M)]
    return missing


def guard_against_ledger(before, after):
    was_active = {l['code'] for l in before if l['active'] == '1'}
    known = {l['code'] for l in before}
    retired = [l for l in after if l['active'] == '0' and l['code'] in was_active]
    minted = [l for l in after if l['code'] not in known]
    problems = []
    if len(retired) > RETIRE_LIMIT:
        problems.append(f'장부보다 {len(retired)}역이 숨겨진다(한도 {RETIRE_LIMIT}): {[l["code"] for l in retired][:20]}')
    for old in retired:
        near = [n['code'] for n in minted if set(n['name_key'].split('|')) & set(old['name_key'].split('|')) and meters(
            (float(n['lat']), float(n['lng'])), (float(old['lat']), float(old['lng']))) <= RECODE_METERS]
        if near:
            problems.append(f"같은 역이 새 코드를 받는다: {old['name_key']} {old['code']} → {near}")
    return problems


def main():
    allow = '--allow-retire' in sys.argv
    args = [a for a in sys.argv[1:] if a not in ('--check', '--allow-retire', '--init')]
    if len(args) != 1:
        sys.exit('사용법: python3 -I dml/tools/gen_subway.py <원천.xlsx> [--check] [--allow-retire] [--init]')
    source = args[0]
    sha = hashlib.sha256(read_bytes(source)).hexdigest()
    stations, unplaced, stats = build(load(source))
    missing = schema_missing()
    out = PENDING_OUT if missing else OUT
    if missing:
        print(f'⚠ V 마이그레이션에 없음: {missing} → {PENDING_OUT} 에 쓴다(CD 대상 아님). 마이그레이션을 넣은 뒤 다시 돌리면 {OUT} 로 간다',
              file=sys.stderr)
        if os.path.exists(OUT):
            sys.exit(f'{OUT} 가 이미 있다 — 테이블이 없는데 dml/ 맨 위에 있으면 배포가 멈춘다. 지운 뒤 다시 돌린다')
    before = read_ledger(out)
    if os.path.exists(out):
        out_codes = set(re.findall(r"^\s+\('(ST\d{4})',", read_text(out), re.M))
        lost = out_codes - {l['code'] for l in before}
        if lost:
            sys.exit(f'{out} 의 역 코드 {len(lost)}개가 장부에 없다(장부가 잘렸거나 낡았다): {sorted(lost)[:10]}')
    ledger = assign_codes(stations, before)
    problems = guard_against_ledger(before, ledger)
    if problems and not allow:
        sys.exit('장부와 다르다 — 확인했으면 --allow-retire 로 다시 만든다:\n  ' + '\n  '.join(problems))
    retired = [l for l in ledger if l['active'] == '0']
    sql = render(stations, unplaced, stats, sha, os.path.basename(source), retired)
    ledger_text = render_ledger(ledger)
    if '--check' in sys.argv:
        same = (os.path.exists(out) and read_text(out) == sql
                and os.path.exists(LEDGER) and read_text(LEDGER) == ledger_text)
        print('같다' if same else '다르다 — 다시 만들어 커밋해야 한다')
        sys.exit(0 if same else 1)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(sql)
    if out == OUT and os.path.exists(PENDING_OUT):
        os.remove(PENDING_OUT)
    with open(LEDGER, 'w', encoding='utf-8', newline='\n') as f:
        f.write(ledger_text)
    by_sido = collections.Counter(s['sido'] for s in stations)
    print(f"{out}: 역 {len(stations)} {dict(by_sido)} · 노선 {len({l for s in stations for l in s['lines']})} · "
          f"역-노선 {sum(len(s['lines']) for s in stations)} · {stats}")
    print('넣지 않은 역(좌표):', unplaced)
    print('장부에서 숨김:', [(l['code'], l['name_key']) for l in retired] or '없음')


if __name__ == '__main__':
    main()
