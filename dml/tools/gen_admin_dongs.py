#!/usr/bin/env python3
# =============================================================================
# dml/tools/gen_admin_dongs.py — 행정동 경계 GeoJSON → dml/08_admin_dongs.sql (읍면동 단계 · 좌표 · 경계)
#
# 실행:  python3 -I dml/tools/gen_admin_dongs.py <HangJeongDong_verYYYYMMDD.geojson>          (dml/08 을 다시 쓴다)
#        python3 -I dml/tools/gen_admin_dongs.py <HangJeongDong_verYYYYMMDD.geojson> --check  (커밋본과 같은지만 본다)
# 표준 라이브러리만 쓴다. 같은 입력이면 같은 바이트가 나온다. 서버 저장소 루트에서 실행한다.
#
# 원천: vuski/admdongkor (https://github.com/vuski/admdongkor) 의 HangJeongDong_ver*.geojson — CRS84(WGS84 경도,위도).
#   라이선스: 가공물 CC BY 4.0 · 원자료 통계청 SGIS 공공누리 제1유형(출처표시). 두 출처표시를 SQL 머리말과 NOTICE 에 남긴다.
#
# ★ 규칙
#   1. 행 = 행정동 하나(adm_cd2, 행정안전부 10자리). 시군구는 sgg 5자리로 regions 에서 찾는다 — 없으면 멈춘다(지역 장부가 먼저다).
#   2. 경계는 링마다 Douglas-Peucker 로 줄인다(TOLERANCE_DEG ≈ 10 m, 좌표 소수 5자리 ≈ 1 m). "내가 어느 동에 있나"에는 이 오차로 충분하고 파일이 1/5 로 준다.
#      줄인 링이 4점 미만이면 원래 링을 쓴다(작은 섬이 사라지지 않게). Polygon 은 MultiPolygon 으로 맞춘다(칸 타입이 MULTIPOLYGON).
#   3. 중심점 = 가장 큰 조각 안에 반드시 들어가는 점(무게중심이 안이면 그것, 밖이면 그 위도의 가장 넓은 내부 구간 가운데).
#      지도 이름표 · 가까운 동 찾기에 쓴다 — 오목한 동의 무게중심은 바다나 옆 동에 떨어질 수 있다.
#   4. 영문명 = 국어의 로마자 표기법(문화체육관광부 고시) — 이름 안의 소리 변화는 반영하고(종로 Jongno · 신림 Sillim),
#      행정 단위(동 · 읍 · 면) 앞은 붙임표, 붙임표 앞뒤 소리 변화는 반영하지 않는다(사직동 Sajik-dong). 숫자는 띄어 둔다(역삼1동 Yeoksam 1-dong).
#      공식 영문 DB 와 다를 수 있다(관용 표기) — OFFICIAL_EN 표로 덮는다.
#   5. 원천에서 사라진 동은 지우지 않고 숨긴다(source_version 이 이번 판이 아니면 is_active = 0) — 앱이 동 코드를 들고 있을 수 있다.
#   6. V40 마이그레이션(admin_dongs 테이블)이 없으면 멈춘다.
# =============================================================================
import hashlib
import json
import math
import os
import re
import sys

TOLERANCE_DEG = 0.0001
COORD_DECIMALS = 5
OUT = 'dml/08_admin_dongs.sql'
MIGRATION = 'src/main/resources/db/migration/V40__admin_dongs.sql'
REGIONS_SQL = 'dml/03_regions.sql'

# 관용 · 공식 영문이 규칙 결과와 다른 것만 적는다(원천 값 → 영문). 지금은 비어 있다 — 검수에서 찾으면 넣는다.
OFFICIAL_EN = {}

# ── 로마자 표기 ───────────────────────────────────────────────────────────────
_INIT = ['g', 'kk', 'n', 'd', 'tt', 'r', 'm', 'b', 'pp', 's', 'ss', '', 'j', 'jj', 'ch', 'k', 't', 'p', 'h']
_VOW = ['a', 'ae', 'ya', 'yae', 'eo', 'e', 'yeo', 'ye', 'o', 'wa', 'wae', 'oe', 'yo', 'u', 'wo', 'we', 'wi', 'yu',
        'eu', 'ui', 'i']
# 받침(0 = 없음) → 다음 소리가 자음일 때 · 끝일 때의 대표음
_CODA = ['', 'k', 'k', 'k', 'n', 'n', 'n', 't', 'l', 'k', 'm', 'l', 'l', 'l', 'p', 'l', 'm', 'p', 'p', 't', 't', 'ng',
         't', 't', 'k', 't', 'p', 't']
# 받침 → 다음 음절 첫소리가 ㅇ 일 때 (남는 받침, 넘어가는 소리) — 연음
_LIAISON = ['', ('', 'g'), ('', 'kk'), ('k', 's'), ('', 'n'), ('n', 'j'), ('', 'n'), ('', 'd'), ('', 'r'), ('l', 'g'),
            ('l', 'm'), ('l', 'b'), ('l', 's'), ('l', 't'), ('l', 'p'), ('', 'r'), ('', 'm'), ('', 'b'), ('p', 's'),
            ('', 's'), ('', 'ss'), ('ng', ''), ('', 'j'), ('', 'ch'), ('', 'k'), ('', 't'), ('', 'p'), ('', '')]
_H_FINALS = {27: '', 6: 'n', 15: 'l'}  # ㅎ · ㄶ · ㅀ 뒤 ㄱ/ㄷ/ㅈ 는 거센소리(좋다 jota) — 남는 받침
_ASPIRATE = {'g': 'k', 'd': 't', 'j': 'ch'}


def _syllables(word):
    out = []
    for ch in word:
        code = ord(ch) - 0xAC00
        if not 0 <= code < 11172:
            raise ValueError(f'not a Hangul syllable: {ch!r} in {word!r}')
        out.append((code // 588, (code % 588) // 28, code % 28))
    return out


def romanize_word(word):
    """Revised Romanization of one Hangul word, with the sound changes the standard reflects inside a name."""
    syl = _syllables(word)
    parts = []
    for i, (lead, vowel, final) in enumerate(syl):
        initial = _INIT[lead]
        if i > 0:
            prev_final = syl[i - 1][2]
            coda, initial = _join(prev_final, lead, vowel)
            parts[-1] = parts[-1][0], coda
        parts.append((initial + _VOW[vowel], _CODA[final]))
    text = ''.join(body + coda for body, coda in parts)
    return text[:1].upper() + text[1:]


def _join(prev_final, lead, vowel):
    """Coda of the previous syllable and initial of this one after the boundary sound rules."""
    initial = _INIT[lead]
    if prev_final == 0:
        return '', initial
    if lead == 11:  # ㅇ: 연음 — 받침이 다음 첫소리로 넘어간다
        coda, moved = _LIAISON[prev_final]
        if vowel == 20 and prev_final == 7:  # ㄷ + 이 → 지 (구개음화: 해돋이 haedoji)
            moved = 'j'
        elif vowel == 20 and prev_final == 25:  # ㅌ + 이 → 치 (같이 gachi)
            moved = 'ch'
        return coda, moved
    if prev_final in _H_FINALS and initial in _ASPIRATE:
        return _H_FINALS[prev_final], _ASPIRATE[initial]
    coda = _CODA[prev_final]
    if initial == 'r':  # ㄹ 첫소리
        if coda == 'n':
            return 'l', 'l'  # 신라 Silla
        if coda == 'l':
            return 'l', 'l'
        if coda in ('m', 'ng'):
            return coda, 'n'  # 종로 Jongno · 침류 Chimnyu
        if coda == 'k':
            return 'ng', 'n'  # 백리 Baengni
        if coda == 'p':
            return 'm', 'n'  # 십리 Simni
        if coda == 't':
            return 'n', 'n'
    if initial in ('n', 'm'):  # 비음화
        if coda == 'k':
            return 'ng', initial  # 백마 Baengma
        if coda == 't':
            return 'n', initial
        if coda == 'p':
            return 'm', initial
        if coda == 'l' and initial == 'n':
            return 'l', 'l'  # 별내 Byeollae
    return coda, initial


_SUFFIX = {'동': 'dong', '읍': 'eup', '면': 'myeon'}
# 한 조각 = 이름 [번호 [가 [번호]]] — 성수1가1 · 종로1·2·3·4가 · 역삼1 · 사직
_SEGMENT = re.compile(r'^(?P<base>[가-힣]+?)(?P<num>[0-9][0-9·.,]*)?(?P<ga>가)?(?P<num2>[0-9]+)?$')


def _romanize_segment(seg, name):
    m = _SEGMENT.match(seg)
    if not m:
        raise ValueError(f'unexpected dong name: {name!r}')
    base, num, ga, num2 = m.group('base'), m.group('num'), m.group('ga'), m.group('num2')
    if ga and not num:  # 번호 없이 '가'로 끝나면 이름의 일부다(마가동) — 단위가 아니다
        base, ga = base + '가', None
        if num2:
            raise ValueError(f'unexpected dong name: {name!r}')
    out = romanize_word(base)
    if num:
        out += ' ' + num.replace('·', '.').replace(',', '.')
    if ga:
        out += '-ga'  # '가'도 행정 단위 — 붙임표(로마자 표기법 제3장 제5항)
    if num2:
        out += ' ' + num2
    return out


def romanize_dong(name):
    """'사직동' -> 'Sajik-dong', '역삼1동' -> 'Yeoksam 1-dong', '성수1가1동' -> 'Seongsu 1-ga 1-dong',
    '교현·안림동' -> 'Gyohyeon·Allim-dong' (merged dongs keep the middle dot; each part romanized on its own)."""
    if name in OFFICIAL_EN:
        return OFFICIAL_EN[name]
    if not name or name[-1] not in _SUFFIX or len(name) < 2:
        raise ValueError(f'unexpected dong name: {name!r}')
    core, unit = name[:-1], name[-1]
    # 가운뎃점은 동 이름 사이(교현·안림)와 번호 사이(1·2·3·4가)에 다 쓰인다 — 뒤가 한글일 때만 이름 경계다.
    segments = re.split(r'·(?=[가-힣])', core)
    return '·'.join(_romanize_segment(s, name) for s in segments) + '-' + _SUFFIX[unit]


# ── 도형 ─────────────────────────────────────────────────────────────────────
def _perp_dist(p, a, b):
    (x, y), (x1, y1), (x2, y2) = p, a, b
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(x - x1, y - y1)
    t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))


def simplify_ring(ring, tol=TOLERANCE_DEG):
    """Douglas-Peucker on a closed ring (first == last). Keeps the original if fewer than 4 points would remain."""
    pts = ring[:-1] if ring[0] == ring[-1] else ring[:]
    if len(pts) < 4:
        return ring
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        s, e = stack.pop()
        best, idx = 0.0, -1
        for i in range(s + 1, e):
            d = _perp_dist(pts[i], pts[s], pts[e])
            if d > best:
                best, idx = d, i
        if best > tol and idx != -1:
            keep[idx] = True
            stack.append((s, idx))
            stack.append((idx, e))
    out = [p for p, k in zip(pts, keep) if k]
    if len(out) < 3:
        return ring
    return out + [out[0]]


def _ring_area(ring):
    return sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(ring, ring[1:])) / 2.0


def point_in_ring(pt, ring):
    x, y = pt
    inside = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def point_in_polygon(pt, polygon):
    return point_in_ring(pt, polygon[0]) and not any(point_in_ring(pt, hole) for hole in polygon[1:])


def interior_point(polygon):
    """A point guaranteed inside the polygon: its centroid if inside, else the middle of the widest
    interior span on the centroid's latitude."""
    outer = polygon[0]
    a = _ring_area(outer)
    if a == 0:
        return outer[0]
    cx = sum((x1 + x2) * (x1 * y2 - x2 * y1) for (x1, y1), (x2, y2) in zip(outer, outer[1:])) / (6 * a)
    cy = sum((y1 + y2) * (x1 * y2 - x2 * y1) for (x1, y1), (x2, y2) in zip(outer, outer[1:])) / (6 * a)
    if point_in_polygon((cx, cy), polygon):
        return cx, cy
    xs = []
    for ring in polygon:
        for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
            if (y1 > cy) != (y2 > cy):
                xs.append((x2 - x1) * (cy - y1) / (y2 - y1) + x1)
    xs.sort()
    spans = [(xs[i + 1] - xs[i], (xs[i] + xs[i + 1]) / 2) for i in range(0, len(xs) - 1, 2)]
    return (max(spans)[1], cy) if spans else outer[0]


def as_multipolygon(geometry):
    if geometry['type'] == 'Polygon':
        return [geometry['coordinates']]
    if geometry['type'] == 'MultiPolygon':
        return geometry['coordinates']
    raise ValueError(f"unexpected geometry {geometry['type']}")


def prepare(feature):
    props = feature['properties']
    polys = []
    for polygon in as_multipolygon(feature['geometry']):
        rings = [[(round(x, COORD_DECIMALS), round(y, COORD_DECIMALS)) for x, y in simplify_ring(r)] for r in polygon]
        polys.append(rings)
    largest = max(polys, key=lambda p: abs(_ring_area(p[0])))
    lng, lat = interior_point(largest)
    xs = [x for p in polys for x, _ in p[0]]
    ys = [y for p in polys for _, y in p[0]]
    full_name = props['adm_nm']
    name_ko = full_name.split(' ')[-1]
    return {
        'code': props['adm_cd2'],
        'stat_code': props.get('adm_cd'),
        'sgg': props['sgg'],
        'name_ko': name_ko,
        'name_en': romanize_dong(name_ko),
        'lat': round(lat, 7), 'lng': round(lng, 7),
        'bbox': (min(ys), min(xs), max(ys), max(xs)),
        'geojson': json.dumps({'type': 'MultiPolygon', 'coordinates': [[[list(pt) for pt in r] for r in p] for p in polys]},
                              separators=(',', ':')),
        'vertices': sum(len(r) for p in polys for r in p),
    }


# ── SQL ──────────────────────────────────────────────────────────────────────
def q(s):
    return 'NULL' if s is None else "'" + s.replace('\\', '\\\\').replace("'", "''") + "'"


def dec(v):
    return f'{v:.7f}'


def render(rows, version, sha, source_name):
    head = f"""-- =============================================================================
-- dml/08_admin_dongs.sql — 행정동(읍면동) {len(rows)}개: 코드 · 시군구 · 국문/영문명 · 중심점 · 경계
--
-- 생성물이다. 손으로 고치지 말고 다시 생성한다:
--   python3 -I dml/tools/gen_admin_dongs.py <{source_name}>
-- 원천: {source_name} (sha256 {sha[:16]}…), 판 {version}
-- 출처표시(필수): 본 데이터는 통계청 통계지리정보서비스(SGIS, https://sgis.kostat.go.kr)에서 공공누리 제1유형으로 개방한
--   행정동 경계를 가공한 것이며(가공: vuski/admdongkor, https://github.com/vuski/admdongkor), CC BY 4.0으로 배포됩니다.
--   KOVIL 은 경계를 단순화(Douglas-Peucker, 약 10 m)하고 영문명(국어의 로마자 표기법)을 더했다.
-- 선행: V40(admin_dongs) · dml/03(regions). 멱등: code upsert. 이번 판에 없는 동은 is_active = 0.
-- 한 트랜잭션이다: mysql 클라이언트는 첫 오류에서 멈추고, 커밋 전에 끊기면 전부 되돌린다 — 반쯤 갱신된 경계가 남지 않는다.
-- =============================================================================

START TRANSACTION;

"""
    body = []
    for r in rows:
        min_lat, min_lng, max_lat, max_lng = r['bbox']
        body.append(
            'INSERT INTO admin_dongs (code, stat_code, region_id, name_ko, name_en, center_lat, center_lng, '
            'min_lat, min_lng, max_lat, max_lng, boundary, source_version, is_active)\n'
            f"SELECT {q(r['code'])}, {q(r['stat_code'])}, r.id, {q(r['name_ko'])}, {q(r['name_en'])}, "
            f"{dec(r['lat'])}, {dec(r['lng'])}, {dec(min_lat)}, {dec(min_lng)}, {dec(max_lat)}, {dec(max_lng)}, "
            f"ST_GeomFromGeoJSON({q(r['geojson'])}, 2, 4326), {q(version)}, 1\n"
            f"FROM regions r WHERE r.code = {q(r['sgg'])}\n"
            'ON DUPLICATE KEY UPDATE stat_code = VALUES(stat_code), region_id = VALUES(region_id), name_ko = VALUES(name_ko), '
            'name_en = VALUES(name_en), center_lat = VALUES(center_lat), center_lng = VALUES(center_lng), '
            'min_lat = VALUES(min_lat), min_lng = VALUES(min_lng), max_lat = VALUES(max_lat), max_lng = VALUES(max_lng), '
            'boundary = VALUES(boundary), source_version = VALUES(source_version), is_active = 1;'
        )
    tail = f"\n\n-- 이번 판에 없는 동은 숨긴다(지우지 않는다 — 앱이 코드를 들고 있을 수 있다).\nUPDATE admin_dongs SET is_active = 0 WHERE source_version <> {q(version)};\n\nCOMMIT;\n"
    return head + '\n'.join(body) + tail


def region_codes():
    """5-digit 시군구 codes present in dml/03_regions.sql."""
    with open(REGIONS_SQL, encoding='utf-8') as f:
        return set(re.findall(r"'(\d{5})'", f.read()))


def main(argv):
    args = [a for a in argv[1:] if not a.startswith('--')]
    if len(args) != 1:
        sys.exit('usage: gen_admin_dongs.py <HangJeongDong_verYYYYMMDD.geojson> [--check]')
    src = args[0]
    if not os.path.exists(MIGRATION):
        sys.exit(f'{MIGRATION} missing: create the admin_dongs table first')
    m = re.search(r'ver(\d{8})', os.path.basename(src))
    if not m:
        sys.exit('source file name must contain verYYYYMMDD')
    version = 'ver' + m.group(1)
    raw = open(src, 'rb').read()
    features = json.loads(raw.decode('utf-8'))['features']
    known = region_codes()
    missing = sorted({f['properties']['sgg'] for f in features} - known)
    if missing:
        sys.exit(f'시군구 codes not in {REGIONS_SQL}: {missing[:10]} (update the region ledger first)')
    rows = sorted((prepare(f) for f in features), key=lambda r: r['code'])
    codes = [r['code'] for r in rows]
    if len(codes) != len(set(codes)):
        sys.exit('duplicate adm_cd2 in source')
    sql = render(rows, version, hashlib.sha256(raw).hexdigest(), os.path.basename(src))
    if '--check' in argv:
        same = os.path.exists(OUT) and open(OUT, encoding='utf-8').read() == sql
        print('up to date' if same else f'{OUT} differs from a fresh build')
        sys.exit(0 if same else 1)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(sql)
    before = sum(len(r) for f in features for p in as_multipolygon(f['geometry']) for r in p)
    print(f'{OUT}: {len(rows)} dongs, vertices {before} -> {sum(r["vertices"] for r in rows)}, {len(sql) / 1e6:.1f} MB')


if __name__ == '__main__':
    main(sys.argv)
