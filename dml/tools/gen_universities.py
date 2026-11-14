#!/usr/bin/env python3
# =============================================================================
# dml/tools/gen_universities.py — 교육부 「대학교 주소기반 좌표정보」 xlsx → dml/04_universities.sql
#
# 실행:  python3 -I dml/tools/gen_universities.py <원천.xlsx>            (dml/04_universities.sql 을 다시 쓴다)
#        python3 -I dml/tools/gen_universities.py <원천.xlsx> --check    (다시 만든 결과가 커밋본과 같은지만 본다)
#        --allow-retire : 직전 결과보다 많이 숨겨지거나 같은 자리 코드가 바뀌어도 쓴다(확인한 뒤에만)
# 표준 라이브러리만 쓴다(xlsx 는 zip + xml 로 직접 읽는다). 같은 입력이면 같은 바이트가 나온다.
# 서버 저장소 루트에서 실행한다 — 시군구 코드는 dml/03_regions.sql 에서 읽는다.
#
# 원천: data.go.kr 15138981 「교육부_대학교 주소기반 좌표정보」(이용허락범위 제한 없음). 학교코드 · 위경도 · 학교상태가 있다.
#       좌표를 카카오로 얻지 않는다(카카오 로컬 결과는 저장이 허용되지 않는다).
#
# ★ 넣는 규칙 — 바꾸려면 아래 상수를 고치고 다시 만든다.
#   1. 폐교 행은 넣지 않는다.
#   2. 한 점 = 학부 단위 학교(대학 · 전문대학)와 대학원대학의 캠퍼스 행 하나. 코드는 교육부 학교코드(캠퍼스마다 다르다) 그대로.
#   3. 원격 대학(사이버대학 · 방송통신대학, 이름에 '사이버')은 뺀다 — 통학하는 캠퍼스가 없어 '주변 1km 매물'이 뜻이 없다.
#      그 대학의 대학원 행도 함께 뺀다.
#   4. 대학원 행은 모체 학교(이름이 '모체 이름 + 공백/괄호'로 시작)의 캠퍼스 점에서 1km 안이면 그 점에 합친다.
#      1km 밖이면 모체 학교의 **별도 캠퍼스 점**이 된다 — 학부 행 없이 대학원 행만 있는 캠퍼스가 있다(성균관대 수원 ·
#      경희대 용인 · 부산대 양산 · 가천대 인천 …). 같은 모체의 별도 점끼리는 1km 안이면 하나로 묶고,
#      주소 · 좌표는 묶음에서 학교코드가 가장 작은 살아 있는 행의 것을 쓴다. 코드는 그 자리(1km 안)의 같은 모체 대학원 행 중
#      **폐교 행까지 넣어** 가장 작은 학교코드다 — 살아 있는 행만 보면 대학원 하나가 폐교될 때마다 캠퍼스 코드가 바뀐다.
#      이름은 모체 학교 이름, 캠퍼스 이름은 비운다.
#      모체를 이름으로 못 찾은 대학원 행은 아무 학교 점이든 1km 안이면 합치고, 아니면 제 이름으로 한 점이 된다.
#   5. 캠퍼스 이름은 원천 '본분교' 값만 쓴다(본교 = 비움). 원천에 실제 캠퍼스 이름이 없어서다 — 지어 넣지 않는다.
#   6. 시군구(region_id)는 주소로 지역 장부에서 찾는다(전국 — region_lookup.py: 옛 시도 이름 · 일반구 · 읍면동으로 가르기).
#      서울 주소인데 못 찾으면 멈춘다. 그 밖에 못 찾은 행(개편으로 쪼개진 옛 구 등)은 NULL 로 두고 머리말에 적는다 — 지어내지 않는다.
#   7. 원천에서 사라진 학교는 지우지 않고 숨긴다(is_active = 0) — 앱의 최근 검색 · 공유 링크가 코드를 들고 있을 수 있다.
#      그래서 다시 만들 때 직전 결과보다 10개 넘게 숨겨지거나, 같은 학교 · 1km 안 점의 코드가 바뀌면 멈춘다(--allow-retire 로 넘긴다).
#   8. 실재 학교가 아닌 행(자리표시)은 EXCLUDE 표로 뺀다. 학교구분 · 학교상태에 모르는 값이 오면 멈춘다 — 규칙을 정하고 다시 만든다.
#   9. V 마이그레이션에 universities 테이블(필요 칸 포함)이 아직 없으면 dml/pending/ 에 쓴다 — CD 는 dml/*.sql 맨 위만 배포하므로
#      테이블보다 먼저 커밋돼도 배포가 ERROR 1146 으로 멈추지 않는다. 마이그레이션이 들어온 뒤 다시 돌리면 dml/04 로 옮겨 간다.
# 개인정보(총장명) · 전화 · 팩스 · 홈페이지 칸은 읽지 않는다.
# =============================================================================
import hashlib
import importlib.util
import math
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from decimal import Decimal, ROUND_HALF_UP

OUT = 'dml/04_universities.sql'
PENDING_OUT = 'dml/pending/04_universities.sql'
MIGRATIONS = 'src/main/resources/db/migration'
REQUIRED_COLUMNS = {'universities': ['code', 'name_ko', 'name_en', 'campus_name_ko', 'campus_name_en', 'address_road',
                                     'lat', 'lng', 'region_id', 'is_active']}
SOURCE_ID = 'data.go.kr 15138981 「교육부_대학교 주소기반 좌표정보」'
SOURCE_LICENSE = '이용허락범위 제한 없음'

CLOSED = {'폐교'}
KNOWN_STATUS = {'기존', '신설'} | CLOSED
# 실재 학교가 아닌 행 — 코드: 이유. 원천에서 사라진 코드가 남으면 멈춘다(표가 낡지 않게).
EXCLUDE = {
    '0000639': '한국대학교육협의회 자리표시 행(KCUE대학교 · 홈페이지 kcue.or.kr · 주소가 대교협 사무실 층) — 실재 학교 아님',
}
# 원천 영문명이 깨진 행의 공식 영문명 — 학교 공식 표기로 확인한 값만 넣는다(지어내지 않는다).
EN_OVERRIDE = {}
RETIRE_LIMIT = 10
POINT_KINDS = {'대학', '전문대학', '대학원대학'}
GRAD_KIND = '대학원'
REMOTE_PROGRAMS = {'사이버대학(대학)', '사이버대학(전문)', '방송통신대학'}
REMOTE_NAME = '사이버'
MERGE_METERS = 1000
CAMPUS_EN = {'제2캠퍼스': 'Campus 2', '제3캠퍼스': 'Campus 3', '제4캠퍼스': 'Campus 4', '분교': 'Branch Campus'}
# 'In' · 'On' 은 넣지 않는다 — 'Yong In University' 처럼 로마자 지명 안에 들어 있다.
EN_SMALL_WORDS = {'Of', 'And', 'The', 'For'}
LIMITS = {'name_ko': 100, 'name_en': 200, 'campus_name_ko': 50, 'campus_name_en': 100, 'address_road': 255}

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}


def read_xlsx_first_sheet(path):
    z = zipfile.ZipFile(path)
    shared = []
    if 'xl/sharedStrings.xml' in z.namelist():
        for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', NS):
            shared.append(''.join(t.text or '' for t in si.iter('{%s}t' % NS['m'])))
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rels = {r.get('Id'): r.get('Target') for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    sheet = wb.find('m:sheets', NS)[0]
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
            letters = re.match(r'[A-Z]+', c.get('r')).group(0)
            col = 0
            for ch in letters:
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
    return re.sub(r'\s+', ' ', s or '').strip()


# 원천 영문명은 손으로 친 값이라 'Of' · "Women'S" 같은 대소문자 깨짐이 있다. 뜻이 바뀌지 않는 것만 기계적으로 고친다.
def english(s):
    words = clean(s).split(' ')
    joined = ' '.join(w.lower() if i > 0 and w in EN_SMALL_WORDS else w for i, w in enumerate(words))
    return re.sub(r"'S\b", "'s", joined)


def seven(value):
    return Decimal(clean(value)).quantize(Decimal('0.0000001'), rounding=ROUND_HALF_UP)


def meters(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (float(a['lat']), float(a['lng']), float(b['lat']), float(b['lng'])))
    x = (lo2 - lo1) * math.cos((la1 + la2) / 2)
    return 6_371_000 * math.hypot(x, la2 - la1)


# 주소 → 시군구는 지역 장부(region_lookup.py · regions.tsv)가 정한다 — -I 는 이 폴더를 sys.path 에 넣지 않아 파일 경로로 읽는다.
def region_lookup():
    spec = importlib.util.spec_from_file_location('region_lookup', os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                                               'region_lookup.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sql_text(s):
    if s is None:
        return 'NULL'
    return "'" + s.replace('\\', '\\\\').replace("'", "''") + "'"


def load(path):
    rows = read_xlsx_first_sheet(path)
    header = [clean(h) for h in rows[0]]
    need = ['학교구분', '학교코드', '학교명', '본분교', '학제', '학교상태', '학교명(영문)', '도로명 주소', '위도', '경도']
    missing = [h for h in need if h not in header]
    if missing:
        sys.exit(f'원천 머리줄에 칸이 없다: {missing}')
    at = {h: header.index(h) for h in need}
    out = []
    for r in rows[1:]:
        if not any(r):
            continue
        get = lambda h: r[at[h]] if at[h] < len(r) else None
        out.append({
            'kind': clean(get('학교구분')), 'code': clean(get('학교코드')), 'name': clean(get('학교명')),
            'campus': clean(get('본분교')), 'program': clean(get('학제')), 'status': clean(get('학교상태')),
            'name_en': EN_OVERRIDE.get(clean(get('학교코드'))) or english(get('학교명(영문)')),
            'address': clean(get('도로명 주소')),
            'lat': seven(get('위도')), 'lng': seven(get('경도')),
        })
    codes = [r['code'] for r in out]
    if len(codes) != len(set(codes)) or not all(re.fullmatch(r'\d{7}', c) for c in codes):
        sys.exit('학교코드가 7자리 숫자로 유일하지 않다')
    unknown = sorted({(r['kind'], r['status']) for r in out
                      if r['kind'] not in POINT_KINDS | {GRAD_KIND} or r['status'] not in KNOWN_STATUS})
    if unknown:
        sys.exit(f'모르는 학교구분/학교상태 — 규칙을 정하고 상수를 고친 뒤 다시 만든다: {unknown}')
    stale = sorted((set(EXCLUDE) | set(EN_OVERRIDE)) - set(codes))
    if stale:
        sys.exit(f'EXCLUDE · EN_OVERRIDE 에 원천에 없는 코드가 있다(표를 고친다): {stale}')
    return out


def build(rows):
    live = [r for r in rows if r['status'] not in CLOSED and r['code'] not in EXCLUDE]
    all_grads = [r for r in rows if r['kind'] == GRAD_KIND and r['code'] not in EXCLUDE]
    remote = [r for r in live if r['kind'] in POINT_KINDS
              and (r['program'] in REMOTE_PROGRAMS or REMOTE_NAME in r['name'])]
    remote_names = {r['name'] for r in remote}
    points = [r for r in live if r['kind'] in POINT_KINDS and r not in remote]
    names = {p['name'] for p in points}

    def starts(name, prefix):
        return name.startswith(prefix + ' ') or name.startswith(prefix + '(')

    merged, remote_grads, far, orphans = [], [], {}, []
    for g in sorted((r for r in live if r['kind'] == GRAD_KIND), key=lambda r: r['code']):
        if any(starts(g['name'], n) for n in remote_names):
            remote_grads.append(g)
            continue
        parents = [n for n in names if starts(g['name'], n)]
        parent = max(parents, key=len) if parents else None
        pool = [p for p in points if p['name'] == parent] if parent else points
        if min(meters(g, p) for p in pool) < MERGE_METERS:
            merged.append(g)
        elif parent:
            far.setdefault(parent, []).append(g)
        else:
            orphans.append(g)

    extras = []
    for parent, grads in sorted(far.items()):
        main = min((p for p in points if p['name'] == parent), key=lambda p: p['code'])
        seeds = []
        for g in grads:
            if not any(meters(g, s) < MERGE_METERS for s in seeds):
                seeds.append(g)
        for s in seeds:
            family = [r for r in all_grads if starts(r['name'], parent) and meters(r, s) < MERGE_METERS]
            extras.append(dict(s, code=min(r['code'] for r in family), name=parent, name_en=main['name_en'],
                               campus='본교', extra_of=parent))

    lone = [dict(o, campus='본교') for o in orphans]
    final = sorted(points + extras + lone, key=lambda r: r['code'])
    if len({r['code'] for r in final}) != len(final):
        sys.exit('점 코드가 겹친다 — 별도 캠퍼스 코드가 다른 점의 코드와 같다')
    stats = {
        'source_rows': len(rows), 'closed': sum(1 for r in rows if r['status'] in CLOSED),
        'excluded': sum(1 for r in rows if r['code'] in EXCLUDE and r['status'] not in CLOSED), 'remote': len(remote),
        'remote_grads': len(remote_grads), 'merged_grads': len(merged),
        'points': len(points), 'extras': len(extras), 'orphans': len(lone), 'total': len(final),
        'by_kind': {k: sum(1 for p in points if p['kind'] == k) for k in ('대학', '전문대학', '대학원대학')},
    }
    return final, stats, remote, extras, lone


def render(final, stats, source_sha, source_name):
    rl = region_lookup()
    children, dongs = rl.load()
    lines, no_region = [], []
    for p in final:
        region = rl.region_of(p['address'], children, dongs)
        if region == rl.UNKNOWN or region is None:
            if rl.sido_code((p['address'].split() or [''])[0]) == '11':
                sys.exit(f"서울 주소에서 자치구를 못 찾았다: {p['code']} {p['address']}")
            no_region.append(f"{p['code']} {p['name']}")
            region = None
        campus_ko = None if p['campus'] == '본교' else p['campus']
        campus_en = None if campus_ko is None else CAMPUS_EN.get(campus_ko)
        if campus_ko is not None and campus_en is None:
            sys.exit(f"모르는 본분교 값: {p['code']} {campus_ko}")
        values = {'name_ko': p['name'], 'name_en': p['name_en'], 'campus_name_ko': campus_ko,
                  'campus_name_en': campus_en, 'address_road': p['address']}
        for col, limit in LIMITS.items():
            if values[col] is not None and len(values[col]) > limit:
                sys.exit(f"{col} 가 {limit}자를 넘는다: {p['code']} {values[col]}")
        if not values['name_en']:
            sys.exit(f"영문 이름이 비었다: {p['code']}")
        region_sql = 'NULL' if region is None else f"(SELECT id FROM regions WHERE code = '{region}')"
        lines.append(f"    ({sql_text(p['code'])}, {sql_text(p['name'])}, {sql_text(p['name_en'])}, "
                     f"{sql_text(campus_ko)}, {sql_text(campus_en)}, {sql_text(p['address'])}, "
                     f"{p['lat']}, {p['lng']}, {region_sql}, 1)")
    with_region = sum(1 for line in lines if '(SELECT id FROM regions WHERE code =' in line)
    k = stats['by_kind']
    head = f"""-- =============================================================================
-- dml/04_universities.sql — 대학교(캠퍼스 = 한 점) · 생성 파일, 손으로 고치지 않는다
--
-- 만든 곳: dml/tools/gen_universities.py — 규칙(폐교 · 원격 제외, 대학원 1km 합치기, 별도 캠퍼스 점)은 그 머리말에 있다.
-- 원천: {SOURCE_ID} · {SOURCE_LICENSE}
--       파일 {source_name} · sha256 {source_sha}
-- 행 수: {stats['total']} = 학교 캠퍼스 {stats['points']}(대학 {k['대학']} · 전문대학 {k['전문대학']} · 대학원대학 {k['대학원대학']})
--        + 대학원만 있는 별도 캠퍼스 {stats['extras']} + 모체 없는 대학원 {stats['orphans']} · 시군구가 붙은 행 {with_region}
-- 시군구를 못 정한 행: {' · '.join(no_region) or '없음'}
-- 뺀 것: 원천 {stats['source_rows']}행 중 폐교 {stats['closed']} · 실재 학교 아님 {stats['excluded']} · 원격 대학 {stats['remote']}(그 대학원 {stats['remote_grads']}) ·
--        캠퍼스 점에 합친 대학원 {stats['merged_grads']}
--
-- ⚠ universities 테이블을 만드는 V 마이그레이션이 먼저(또는 같은 커밋) 나가야 한다 — 없으면 ERROR 1146 으로 CD 의 dml 단계가 멈춘다.
-- 멱등: 학교코드(code) upsert. 원천에서 사라진 학교는 지우지 않고 is_active = 0.
-- 실행: mysql --default-character-set=utf8mb4 <DB> < dml/04_universities.sql
-- =============================================================================

INSERT INTO universities (code, name_ko, name_en, campus_name_ko, campus_name_en, address_road, lat, lng, region_id, is_active)
VALUES
"""
    body = ',\n'.join(lines) + '\n'
    tail = """ON DUPLICATE KEY UPDATE name_ko        = VALUES(name_ko),
                        name_en        = VALUES(name_en),
                        campus_name_ko = VALUES(campus_name_ko),
                        campus_name_en = VALUES(campus_name_en),
                        address_road   = VALUES(address_road),
                        lat            = VALUES(lat),
                        lng            = VALUES(lng),
                        region_id      = VALUES(region_id),
                        is_active      = VALUES(is_active);

UPDATE universities
SET is_active = 0
WHERE is_active = 1
  AND code NOT IN (""" + ', '.join(sql_text(p['code']) for p in final) + """);
"""
    return head + body + tail


# 직전 결과와 비교한다 — 원천 형식이 바뀌어 한꺼번에 숨겨지거나, 같은 자리 점의 코드가 바뀌면 앱이 들고 있는 코드가 깨진다.
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


def guard_against_previous(final, out):
    if not os.path.exists(out):
        return []
    previous = {}
    for code, name, lat, lng in re.findall(r"^    \('(\d{7})', '((?:[^']|'')*)', .*?, (-?[\d.]+), (-?[\d.]+), ", read_text(out), re.M):
        previous[code] = {'name': name.replace("''", "'"), 'lat': Decimal(lat), 'lng': Decimal(lng)}
    now = {p['code'] for p in final}
    gone = [c for c in previous if c not in now]
    problems = []
    if len(gone) > RETIRE_LIMIT:
        problems.append(f'직전보다 {len(gone)}개가 숨겨진다(한도 {RETIRE_LIMIT}): {gone[:20]}')
    for c in gone:
        old = previous[c]
        moved = [p['code'] for p in final if p['name'] == old['name'] and meters(p, old) < MERGE_METERS and p['code'] not in previous]
        if moved:
            problems.append(f"같은 자리 점의 코드가 바뀐다: {old['name']} {c} → {moved}")
    return problems


def main():
    allow = '--allow-retire' in sys.argv
    args = [a for a in sys.argv[1:] if a not in ('--check', '--allow-retire')]
    if len(args) != 1:
        sys.exit('사용법: python3 -I dml/tools/gen_universities.py <원천.xlsx> [--check] [--allow-retire]')
    source = args[0]
    sha = hashlib.sha256(read_bytes(source)).hexdigest()
    final, stats, remote, extras, lone = build(load(source))
    sql = render(final, stats, sha, os.path.basename(source))
    missing = schema_missing()
    out = PENDING_OUT if missing else OUT
    if missing:
        print(f'⚠ V 마이그레이션에 없음: {missing} → {PENDING_OUT} 에 쓴다(CD 대상 아님). 마이그레이션을 넣은 뒤 다시 돌리면 {OUT} 로 간다',
              file=sys.stderr)
        if os.path.exists(OUT):
            sys.exit(f'{OUT} 가 이미 있다 — 테이블이 없는데 dml/ 맨 위에 있으면 배포가 멈춘다. 지운 뒤 다시 돌린다')
    problems = guard_against_previous(final, out)
    if problems and not allow:
        sys.exit('직전 결과와 다르다 — 확인했으면 --allow-retire 로 다시 만든다:\n  ' + '\n  '.join(problems))
    if '--check' in sys.argv:
        same = os.path.exists(out) and read_text(out) == sql
        print('같다' if same else '다르다 — 다시 만들어 커밋해야 한다')
        sys.exit(0 if same else 1)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(sql)
    if out == OUT and os.path.exists(PENDING_OUT):
        os.remove(PENDING_OUT)
    print(f"{out}: {stats['total']}행 {stats}")
    print('원격(뺌):', ', '.join(sorted(r['name'] for r in remote)))
    print('별도 캠퍼스:', ', '.join(f"{e['name']}({e['code']} {e['address']})" for e in extras))
    print('모체 없는 대학원:', ', '.join(f"{o['name']}({o['code']})" for o in lone) or '없음')
    suspicious = [f"{p['code']} {p['name_en']}" for p in final if p['code'] not in EN_OVERRIDE and (
        p['name_en'] == p['name_en'].upper() and ' ' in p['name_en'] or re.search(r"[a-z][A-Z]|[^\x00-\x7f]", p['name_en'])
        or re.search(r'(?i)\S(university|college|campus)\b', p['name_en']))]
    print('영문명 확인 필요(EN_OVERRIDE 후보):', '; '.join(suspicious) or '없음')


if __name__ == '__main__':
    main()
