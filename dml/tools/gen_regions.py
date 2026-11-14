#!/usr/bin/env python3
# =============================================================================
# dml/tools/gen_regions.py — 법정동 · 행정구역분류 원천 → dml/03_regions.sql (+ 지역 장부 regions.tsv · 읍면동 색인 region_dongs.tsv)
#
# 실행:  python3 -I dml/tools/gen_regions.py <국토교통부_전국 법정동.csv> <한국행정구역분류.xlsx>            (다시 쓴다)
#        python3 -I dml/tools/gen_regions.py <법정동.csv> <행정구역분류.xlsx> --check    (다시 만든 결과가 커밋본과 같은지만 본다)
# 표준 라이브러리만 쓴다. 같은 입력이면 같은 바이트가 나온다. 서버 저장소 루트에서 실행한다.
#
# 원천: ① 국토교통부_전국 법정동(data.go.kr 15063424 · 이용허락범위 제한 없음) — 코드 · 국문 이름. 법정동코드 10자리 중 앞 2(시도) · 5(시군구).
#       ② 국가데이터처 「한국행정구역분류」 항목표 · 연계표(통계분류포털) — 영문 표기. 연계표가 행정구역분류 ↔ 법정동코드를 잇는다
#          (통계청 코드 체계는 종로구 11010 처럼 법정동코드와 다르다 — 이름이 아니라 연계표의 법정동코드로 맞춘다).
#
# ★ 규칙 — 바꾸려면 아래 표를 고치고 다시 만든다.
#   1. regions 는 2단(시도 → 시군구)이다. 시군구 = 법정동코드 앞 5자리 행. 일반구가 있는 시(수원 · 성남 · 고양 · 청주 · 창원 …)는
#      시 행을 빼고 일반구를 넣는다 — 이름은 '수원시 장안구', 영문은 'Suwon-si Jangan-gu'(시 영문 + 구 영문).
#      카카오 주소의 시군구(region_2depth)와 법정동코드 앞 5자리가 이 단위라 매물 주소와 맞는다.
#   2. 국문 이름은 연계표의 행정구역명(일반구는 띄어 쓴다)이 법정동 이름과 띄어쓰기만 다를 때 그것을, 아니면 법정동 이름을 쓴다.
#   3. 영문이 비면 멈춘다 — EN_OVERRIDE 에 근거와 함께 넣는다(지어내지 않는다).
#   4. 원천에서 사라진 코드는 지우지 않고 닫는다(is_active = 0) — 옛 요청 · 매물이 그 코드를 들고 있을 수 있다.
#      닫는 것은 RETIRED 표에 이유와 함께 적은 코드뿐이다 — 관리자가 만들거나 연 원천 밖 행(관리자 웹 지역 추가)은 건드리지 않는다.
#      직전 결과에 있던 코드가 원천에서 사라졌는데 RETIRED 에 없으면 멈춘다(적으면 다시 만든다 — 넘기는 옵션은 없다).
#      ★ RETIRED 에 넣기 전에 관리자 웹으로 그 지역을 먼저 닫는다 — 관리자 경로만 그 지역의 공개 매물을 내리고 중개사에게 알린다
#        (ListingRegionClosureService). 이 DML 은 is_active 만 바꾼다.
#   5. 새로 들어가는 행은 열린 채로(is_active = 1 — 전국 서비스, D-96) 들어간다. 이미 있는 행의 열림 · 순서는 건드리지 않는다
#      (관리자가 닫거나 옮긴 것을 배포가 되돌리지 않게). 처음 한 번 여는 것은 V 마이그레이션 몫이다.
# =============================================================================
import collections
import csv
import hashlib
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

OUT = 'dml/03_regions.sql'
LEDGER = 'dml/tools/regions.tsv'
# 읍면동 → 시군구 색인 — 주소에 구가 없거나(화성시 봉담읍 · 성남시 …(야탑동)) 구가 쪼개진(인천 옛 중구) 경우 읍면동으로 가른다.
DONG_LEDGER = 'dml/tools/region_dongs.tsv'
# 같은 색인을 서버도 읽는다(주소 검색의 시군구 판정 — AddressRegionResolver). 둘이 어긋나면 매물 지역과 대학교 · 역 지역이 갈린다.
DONG_RESOURCE = 'src/main/resources/region/region_dongs.tsv'
LINK_SHEET = '2-2. 연계표_행정동 및 법정동(기준시점)'
SOURCE_CODES = '국토교통부_전국 법정동(data.go.kr 15063424) · 이용허락범위 제한 없음'
SOURCE_EN = '국가데이터처 「한국행정구역분류」 연계표(통계분류포털)'

# 시도 표시 순서 — 이미 있는 행은 DB 의 순서를 지키고, 새 행만 이 순서를 받는다. 전남광주는 옛 광주(7) 자리.
SIDO_ORDER = ['11', '41', '28', '26', '27', '30', '12', '31', '36', '51', '43', '44', '52', '47', '48', '50']
# 원천에 영문이 없는 행 — 코드: (영문, 근거). 공식 고시가 나오면 그 값으로 바꾼다.
EN_OVERRIDE = {
    '12': ('Jeonnam-Gwangju', '행정구역분류 7.10판 영문 칸이 비어 있다. 준비위원회안 Jeonnam-Gwangju Special Metropolitan City(2026-05) · '
                              '영문 위키백과 · 위키데이터 표제의 짧은 형태 — 다른 시도(Seoul · Busan)와 같은 짧은 표기'),
}
# 원천에서 사라져 닫는 것이 맞다고 확인한 코드 — 코드: 이유.
RETIRED = {
    '29': '광주광역시 — 2026-07-01 전남광주통합특별시(12)로 통합(행정안전부 2026.7.1. 시행 변경내역)',
    '46': '전라남도 — 같음',
}

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}


def read_text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def read_bytes(path):
    with open(path, 'rb') as f:
        return f.read()


def read_xlsx_sheet(path, name):
    with zipfile.ZipFile(path) as z:
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


def clean(s):
    return re.sub(r'\s+', ' ', s or '').strip()


def load_codes(path):
    with open(path, encoding='utf-8-sig', newline='') as f:
        rows = list(csv.DictReader(f))
    need = ['법정동코드', '시도명', '시군구명', '읍면동명', '리명']
    missing = [h for h in need if h not in (rows[0].keys() if rows else [])]
    if missing:
        sys.exit(f'법정동 원천 머리줄에 칸이 없다: {missing}')
    sido, sigungu, dongs = [], [], []
    for r in rows:
        code = clean(r['법정동코드'])
        if not re.fullmatch(r'\d{10}', code):
            sys.exit(f'법정동코드가 10자리 숫자가 아니다: {code!r}')
        if clean(r['리명']):
            continue
        if clean(r['읍면동명']):
            dongs.append({'sigungu': code[:5], 'name': clean(r['읍면동명'])})
            continue
        if not clean(r['시군구명']):
            if code[2:] != '00000000':
                sys.exit(f'시도 행인데 코드 뒤가 0 이 아니다: {code}')
            sido.append({'code': code[:2], 'name': clean(r['시도명'])})
        else:
            if code[5:] != '00000':
                sys.exit(f'시군구 행인데 코드 뒤가 0 이 아니다: {code}')
            sigungu.append({'code': code[:5], 'parent': code[:2], 'name': clean(r['시군구명'])})
    for level in (sido, sigungu):
        codes = [x['code'] for x in level]
        if len(codes) != len(set(codes)):
            sys.exit('같은 코드가 두 번 나온다')
    return sido, sigungu, dongs


# 연계표 — 법정동코드(10자리) → (행정구역명, 영문). 시도 · 시군구 단위 행만(행정구역분류 2 · 5자리).
def load_english(path):
    rows = read_xlsx_sheet(path, LINK_SHEET)
    header = next((i for i, r in enumerate(rows[:5]) if r and '법정동코드' in [clean(c) for c in r if c]), None)
    if header is None:
        sys.exit(f'연계표 머리줄을 못 찾았다: {LINK_SHEET}')
    cols = {clean(c): i for i, c in enumerate(rows[header]) if c}
    need = ['행정구역명', '행정구역분류', '법정동코드', '행정동 영문명칭']
    missing = [h for h in need if h not in cols]
    if missing:
        sys.exit(f'연계표 머리줄에 칸이 없다: {missing}')
    out = {}
    for r in rows[header + 1:]:
        get = lambda h: clean(r[cols[h]]) if cols[h] < len(r) and r[cols[h]] else ''
        if len(get('행정구역분류')) not in (2, 5) or not get('법정동코드'):
            continue
        entry = (get('행정구역명'), get('행정동 영문명칭') or None)
        if out.setdefault(get('법정동코드'), entry) != entry:
            sys.exit(f"연계표에서 한 법정동코드에 다른 이름 · 영문이 붙는다: {get('법정동코드')} {out[get('법정동코드')]} / {entry}")
    return out


def build(sido, sigungu, english):
    problems = []
    names = {(s['parent'], s['name']) for s in sigungu}
    # 일반구가 있는 시 — 같은 시도 안에 '그 이름 + 구' 행이 있다(수원시 → 수원시장안구).
    cities = {s['code'] for s in sigungu if s['name'].endswith('시') and any(
        p == s['parent'] and n != s['name'] and n.startswith(s['name']) for p, n in names)}
    by_code = {s['code']: s for s in sigungu}
    regions = []
    for i, s in enumerate(sorted(sido, key=lambda x: SIDO_ORDER.index(x['code']) if x['code'] in SIDO_ORDER else 99)):
        if s['code'] not in SIDO_ORDER:
            problems.append(f"SIDO_ORDER 에 없는 시도: {s['code']} {s['name']}")
        name_ko, en = english.get(s['code'] + '00000000', (None, None))
        if s['code'] in EN_OVERRIDE:
            en = EN_OVERRIDE[s['code']][0]
        if not en:
            problems.append(f"시도 영문이 없다: {s['code']} {s['name']}")
        regions.append({'code': s['code'], 'parent': None, 'name_ko': s['name'], 'name_en': en,
                        'sort_order': SIDO_ORDER.index(s['code']) + 1 if s['code'] in SIDO_ORDER else 99})
    order = collections.Counter()
    for s in sorted(sigungu, key=lambda x: x['code']):
        if s['code'] in cities:
            continue
        link_name, en = english.get(s['code'] + '00000', (None, None))
        name_ko = link_name if link_name and link_name.replace(' ', '') == s['name'] else s['name']
        city = next((by_code[c] for c in cities if by_code[c]['parent'] == s['parent']
                     and s['name'].startswith(by_code[c]['name']) and s['name'] != by_code[c]['name']), None)
        if city is not None:
            city_en = english.get(city['code'] + '00000', (None, None))[1]
            if not city_en or not en:
                problems.append(f"일반구 영문을 못 만든다: {s['code']} {s['name']}")
            else:
                en = f'{city_en} {en}'
            if ' ' not in name_ko:
                name_ko = f"{city['name']} {s['name'][len(city['name']):]}"
        if s['code'] in EN_OVERRIDE:
            en = EN_OVERRIDE[s['code']][0]
        if not en:
            problems.append(f"시군구 영문이 없다: {s['code']} {s['name']}")
        order[s['parent']] += 1
        regions.append({'code': s['code'], 'parent': s['parent'], 'name_ko': name_ko, 'name_en': en,
                        'sort_order': order[s['parent']]})
    parents = {r['code'] for r in regions if r['parent'] is None}
    orphans = [r['code'] for r in regions if r['parent'] is not None and r['parent'] not in parents]
    if orphans:
        problems.append(f'시도가 없는 시군구: {orphans[:10]}')
    stale = sorted(set(EN_OVERRIDE) - {r['code'] for r in regions})
    if stale:
        problems.append(f'EN_OVERRIDE 에 원천에 없는 코드가 있다(표를 고친다): {stale}')
    if problems:
        sys.exit('\n'.join(problems))
    return regions, sorted(cities)


def q(s):
    return 'NULL' if s is None else "'" + s.replace('\\', '\\\\').replace("'", "''") + "'"


def render(regions, cities, codes_name, codes_sha, en_name, en_sha):
    sido = [r for r in regions if r['parent'] is None]
    sigungu = [r for r in regions if r['parent'] is not None]
    per = collections.Counter(r['parent'] for r in sigungu)
    names = {r['code']: r['name_ko'] for r in sido}
    head = f"""-- =============================================================================
-- dml/03_regions.sql — 지역 마스터(시도 → 시군구, 법정동코드) · 생성 파일, 손으로 고치지 않는다
--
-- 만든 곳: dml/tools/gen_regions.py(규칙은 그 머리말) · 지역 장부 dml/tools/regions.tsv(다른 변환기가 주소 → 시군구에 쓴다)
-- 원천: 코드 · 국문 = {SOURCE_CODES}
--       파일 {codes_name} · sha256 {codes_sha}
--       영문 = {SOURCE_EN} · 파일 {en_name} · sha256 {en_sha}
-- 행 수: 시도 {len(sido)} · 시군구 {len(sigungu)}(일반구가 있는 시 {len(cities)}곳은 시 행 대신 일반구)
--        {' · '.join(f"{names[c]} {per[c]}" for c in [r['code'] for r in sido])}
-- 닫는 옛 코드: {' · '.join(f'{c}({RETIRED[c].split(" — ")[0]})' for c in sorted(RETIRED))}
--
-- 멱등: 코드 upsert. 다시 돌려도 이름만 고친다 — 이미 있는 행의 열림(is_active) · 순서(sort_order)는 관리자 몫이라 건드리지 않는다.
--       새 행은 열린 채로 들어간다(전국 서비스 D-96). 원천에서 사라진 코드(생성기의 RETIRED 표)만 닫는다(지우지 않는다) —
--       관리자가 만든 원천 밖 행은 그대로 둔다.
-- 실행: mysql --default-character-set=utf8mb4 <DB> < dml/03_regions.sql
-- =============================================================================

INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order)
VALUES
"""
    sido_sql = ',\n'.join(f"    (NULL, {q(r['code'])}, {q(r['name_ko'])}, {q(r['name_en'])}, 1, {r['sort_order']})" for r in sido)
    first = sigungu[0]
    union = [f"SELECT {q(first['parent'])} parent_code, {q(first['code'])} code, {q(first['name_ko'])} name_ko, "
             f"{q(first['name_en'])} name_en, {first['sort_order']} sort_order"]
    union += [f"SELECT {q(r['parent'])}, {q(r['code'])}, {q(r['name_ko'])}, {q(r['name_en'])}, {r['sort_order']}"
              for r in sigungu[1:]]
    return head + sido_sql + """
ON DUPLICATE KEY UPDATE name_ko = VALUES(name_ko),
                        name_en = VALUES(name_en);

-- ── 시군구(부모 = 같은 앞 2자리 시도)
INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order)
SELECT p.id, g.code, g.name_ko, g.name_en, 1, g.sort_order
FROM regions p
         JOIN (""" + '\n               UNION ALL '.join(union) + """) g
              ON p.code = g.parent_code
ON DUPLICATE KEY UPDATE name_ko = VALUES(name_ko),
                        name_en = VALUES(name_en);
""" + render_retire()


def render_retire():
    if not RETIRED:
        return ''
    return """
-- ── 원천에서 사라진 코드(RETIRED 표)는 닫는다(지우지 않는다) — 관리자가 만든 원천 밖 행은 건드리지 않는다
UPDATE regions
SET is_active = 0
WHERE is_active = 1
  AND code IN (""" + ', '.join(q(c) for c in sorted(RETIRED)) + """);
"""


# 읍면동 색인 — 시군구 장부에 있는 시군구 아래의 것만(일반구가 있는 시의 시 행 아래 읍면동은 없다 — 모두 일반구 아래다).
def render_dong_ledger(dongs, regions):
    known = {r['code']: r['parent'] for r in regions if r['parent']}
    rows = sorted({(known[d['sigungu']], d['name'], d['sigungu']) for d in dongs if d['sigungu'] in known})
    lines = ['sido_code\tdong\tsigungu_code'] + [f'{a}\t{b}\t{c}' for a, b, c in rows]
    return '\n'.join(lines) + '\n'


def render_ledger(regions):
    lines = ['code\tparent_code\tname_ko\tname_en']
    lines += [f"{r['code']}\t{r['parent'] or ''}\t{r['name_ko']}\t{r['name_en']}" for r in regions]
    return '\n'.join(lines) + '\n'


def guard_against_previous(regions, out):
    if not os.path.exists(out):
        return []
    previous = set(re.findall(r"\(NULL, '(\d{2})', |SELECT (?:'\d{2}'(?: parent_code)?, )?'(\d{5})'", read_text(out)))
    previous = {a or b for a, b in previous}
    gone = sorted(previous - {r['code'] for r in regions} - set(RETIRED))
    return [f'원천에서 사라진 코드(RETIRED 표에 없음): {gone[:20]}'] if gone else []


def main():
    args = [a for a in sys.argv[1:] if a != '--check']
    if len(args) != 2:
        sys.exit('사용법: python3 -I dml/tools/gen_regions.py <법정동.csv> <한국행정구역분류.xlsx> [--check]')
    codes_path, en_path = args
    sido, sigungu, dongs = load_codes(codes_path)
    regions, cities = build(sido, sigungu, load_english(en_path))
    sql = render(regions, cities, os.path.basename(codes_path), hashlib.sha256(read_bytes(codes_path)).hexdigest(),
                 os.path.basename(en_path), hashlib.sha256(read_bytes(en_path)).hexdigest())
    ledger = render_ledger(regions)
    dong_ledger = render_dong_ledger(dongs, regions)
    problems = guard_against_previous(regions, OUT)
    if problems:
        sys.exit('직전 결과와 다르다 — 사라진 코드를 확인하고 RETIRED 표에 이유와 함께 적은 뒤 다시 만든다:\n  ' + '\n  '.join(problems))
    if '--check' in sys.argv:
        same = (os.path.exists(OUT) and read_text(OUT) == sql
                and os.path.exists(LEDGER) and read_text(LEDGER) == ledger
                and os.path.exists(DONG_LEDGER) and read_text(DONG_LEDGER) == dong_ledger
                and os.path.exists(DONG_RESOURCE) and read_text(DONG_RESOURCE) == dong_ledger)
        print('같다' if same else '다르다 — 다시 만들어 커밋해야 한다')
        sys.exit(0 if same else 1)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(sql)
    with open(LEDGER, 'w', encoding='utf-8', newline='\n') as f:
        f.write(ledger)
    with open(DONG_LEDGER, 'w', encoding='utf-8', newline='\n') as f:
        f.write(dong_ledger)
    with open(DONG_RESOURCE, 'w', encoding='utf-8', newline='\n') as f:
        f.write(dong_ledger)
    print(f"{OUT}: 시도 {sum(1 for r in regions if r['parent'] is None)} · 시군구 {sum(1 for r in regions if r['parent'])} · "
          f"일반구가 있는 시(빼고 일반구) {len(cities)}")


if __name__ == '__main__':
    main()
