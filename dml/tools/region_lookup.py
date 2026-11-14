# dml/tools/region_lookup.py — 주소 → 시군구 코드. 변환기들(gen_universities · gen_subway)이 파일 경로로 읽어 쓴다.
# 지역 장부(dml/tools/regions.tsv)와 읍면동 색인(dml/tools/region_dongs.tsv — 둘 다 gen_regions.py 가 만든다)만 본다. 표준 라이브러리만.
#
# ★ 규칙
#   1. 주소 첫 낱말이 시도다. 옛 이름 · 줄인 이름도 받는다 — 원천 주소가 개편 전 이름을 쓴다(광주광역시 · 전라남도 → 전남광주 12,
#      강원도 → 51, 전라북도 → 52, 서울시 → 11 …). 첫 낱말이 시도가 아니면 부르는 쪽이 준 시도를 쓴다.
#   2. 그 시도의 시군구 중 '둘째 + 셋째 낱말'(일반구 — 수원시 장안구) → '둘째 낱말' → '둘째 낱말 + 시 · 군'(남양주) →
#      옛 이름(RENAMED — 인천 남구 → 미추홀구 · 동구 → 제물포구) 순으로 이름이 같은 것. 세종은 시군구가 하나뿐이다.
#   3. 그래도 모르면(일반구가 있는 시인데 주소에 구가 없음 · 개편으로 쪼개진 옛 구 · 시군구가 없는 주소) 주소의 읍면동(괄호 안 포함)으로
#      가른다 — 그 시도(시를 알면 그 시) 안에서 그 이름의 읍면동이 한 시군구에만 있을 때만.
#   4. 쪼개진 옛 구(SPLIT)인데 읍면동도 없으면 None, 그 밖에 못 찾으면 UNKNOWN — 지어내지 않는다. 부르는 쪽이 멈출지 정한다.
import re

LEDGER = 'dml/tools/regions.tsv'
DONG_LEDGER = 'dml/tools/region_dongs.tsv'

SIDO = {
    '서울특별시': '11', '부산광역시': '26', '대구광역시': '27', '인천광역시': '28', '대전광역시': '30', '울산광역시': '31',
    '세종특별자치시': '36', '경기도': '41', '충청북도': '43', '충청남도': '44', '경상북도': '47', '경상남도': '48',
    '제주특별자치도': '50', '강원특별자치도': '51', '전북특별자치도': '52', '전남광주통합특별시': '12',
    # 옛 이름 — 원천 주소가 아직 쓴다
    '광주광역시': '12', '전라남도': '12', '강원도': '51', '전라북도': '52', '제주도': '50',
    # 줄인 이름 — 지하철 원천의 시도 판정 값 · 주소 표기. '광주시' 는 경기도 광주시와 겹쳐 받지 않는다.
    '서울': '11', '부산': '26', '대구': '27', '인천': '28', '대전': '30', '울산': '31', '세종': '36', '경기': '41',
    '충북': '43', '충남': '44', '경북': '47', '경남': '48', '제주': '50', '강원': '51', '전북': '52', '광주': '12', '전남': '12',
    '서울시': '11', '부산시': '26', '대구시': '27', '인천시': '28', '대전시': '30', '울산시': '31',
}
# 이름이 바뀌었거나 통째로 다른 구에 들어간 시군구 — (시도 코드, 옛 이름): 지금 이름.
RENAMED = {('28', '남구'): '미추홀구', ('28', '동구'): '제물포구'}
# 개편으로 쪼개져 도로명 주소만으로는 새 구를 모르는 옛 구 — (시도 코드, 옛 구 이름): 사정.
SPLIT = {
    ('28', '중구'): '2026-07-01 제물포구 · 영종구로 나뉨',
    ('28', '서구'): '2026-07-01 서해구 · 검단구로 나뉨',
}
UNKNOWN = 'UNKNOWN'


def _rows(path):
    with open(path, encoding='utf-8') as f:
        lines = f.read().splitlines()
    head = lines[0].split('\t')
    return [dict(zip(head, line.split('\t'))) for line in lines[1:] if line]


def load(path=LEDGER, dong_path=DONG_LEDGER):
    children = {}
    for r in _rows(path):
        if r['parent_code']:
            children.setdefault(r['parent_code'], {})[r['name_ko']] = r['code']
    dongs = {}
    for r in _rows(dong_path):
        dongs.setdefault((r['sido_code'], r['dong']), set()).add(r['sigungu_code'])
    return children, dongs


def sido_code(token):
    return SIDO.get(token)


# 주소의 읍면동 후보 — 낱말과 괄호 속 낱말에서 '…동 · …읍 · …면 · …가' 로 끝나는 것(번지 · 쉼표는 떼고).
def _dong_tokens(address):
    out = []
    for token in re.split(r'[\s(),]+', address or ''):
        token = re.sub(r'\d[\d-]*$', '', token)
        if re.fullmatch(r'[가-힣0-9]+(동|읍|면|가)', token):
            out.append(token)
    return out


def _by_dong(address, sido, dongs, allowed):
    found = set()
    for token in _dong_tokens(address):
        hits = dongs.get((sido, token), set()) & allowed
        if len(hits) == 1:
            found |= hits
    return next(iter(found)) if len(found) == 1 else None


# 주소 → 시군구 코드 · None(쪼개진 옛 구인데 읍면동이 없음) · UNKNOWN(못 찾음).
def region_of(address, children, dongs, sido=None):
    tokens = (address or '').split()
    if tokens and sido_code(tokens[0]):
        sido = sido_code(tokens[0])
        tokens = tokens[1:]
    if not sido:
        return UNKNOWN
    kids = children.get(sido, {})
    if sido == '36' and len(kids) == 1:
        return next(iter(kids.values()))
    allowed = set(kids.values())
    first = tokens[0] if tokens else ''
    if len(tokens) >= 2 and f'{first} {tokens[1]}' in kids:
        return kids[f'{first} {tokens[1]}']
    for name in (first, first + '시', first + '군', RENAMED.get((sido, first))):
        if name and name in kids:
            return kids[name]
    # 일반구가 있는 시인데 구가 없는 주소 — 그 시의 일반구 안에서 읍면동으로 가른다.
    city = next((first for name in kids if first.endswith('시') and name.startswith(first + ' ')), None)
    if city:
        allowed = {code for name, code in kids.items() if name.startswith(city + ' ')}
    hit = _by_dong(address, sido, dongs, allowed)
    if hit:
        return hit
    if (sido, first) in SPLIT:
        return None
    return UNKNOWN
