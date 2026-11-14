#!/usr/bin/env python3
# dml/tools/gen_regions.py 의 지역 규칙과 region_lookup.py 의 주소 → 시군구 규칙을 고정한다 — 원천 파일 없이 돈다(CI 에 원천이 없다).
# 실행(저장소 루트):  python3 -I -B -m unittest discover -s dml/tools -p 'test_*.py'
import importlib.util
import os
import re
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


g = _load('gen_regions')
rl = _load('region_lookup')

# 지금 열려 있는 서울 25개 구 — 매물 · 요청이 이 코드를 들고 있다. 새 원천으로 다시 만들어도 그대로여야 한다.
SEOUL = ['11110', '11140', '11170', '11200', '11215', '11230', '11260', '11290', '11305', '11320', '11350', '11380', '11410',
         '11440', '11470', '11500', '11530', '11545', '11560', '11590', '11620', '11650', '11680', '11710', '11740']


def setUpModule():
    os.chdir(ROOT)


def sido(code, name):
    return {'code': code, 'name': name}


def sgg(code, name):
    return {'code': code, 'parent': code[:2], 'name': name}


def en(**pairs):
    return {code + '0' * (10 - len(code)): value for code, value in pairs.items()}


ENGLISH = en(**{'11': ('서울특별시', 'Seoul'), '41': ('경기도', 'Gyeonggi-do'), '12': ('전남광주통합특별시', None),
                '11110': ('종로구', 'Jongno-gu'), '41110': ('수원시', 'Suwon-si'), '41111': ('수원시 장안구', 'Jangan-gu'),
                '41113': ('수원시 권선구', 'Gwonseon-gu'), '41290': ('과천시', 'Gwacheon-si'), '12110': ('목포시', 'Mokpo-si')})
SIDO = [sido('11', '서울특별시'), sido('41', '경기도'), sido('12', '전남광주통합특별시')]
SGG = [sgg('11110', '종로구'), sgg('41110', '수원시'), sgg('41111', '수원시장안구'), sgg('41113', '수원시권선구'),
       sgg('41290', '과천시'), sgg('12110', '목포시')]


class Build(unittest.TestCase):

    def build(self, sido_rows=SIDO, sgg_rows=SGG, english=ENGLISH):
        regions, cities = g.build(sido_rows, sgg_rows, english)
        return {r['code']: r for r in regions}, cities

    def test_cities_with_districts_are_replaced_by_their_districts(self):
        regions, cities = self.build()
        self.assertEqual(cities, ['41110'])
        self.assertNotIn('41110', regions)
        self.assertEqual((regions['41111']['name_ko'], regions['41111']['name_en']), ('수원시 장안구', 'Suwon-si Jangan-gu'))
        self.assertEqual((regions['41290']['name_ko'], regions['41290']['name_en']), ('과천시', 'Gwacheon-si'))

    def test_sido_order_and_sigungu_order_by_code(self):
        regions, _ = self.build()
        self.assertEqual([regions[c]['sort_order'] for c in ('11', '41', '12')], [1, 2, 7])
        self.assertEqual([regions[c]['sort_order'] for c in ('41111', '41113', '41290')], [1, 2, 3])
        self.assertEqual(regions['41111']['parent'], '41')

    def test_merged_sido_takes_the_declared_english_name(self):
        regions, _ = self.build()
        self.assertEqual(regions['12']['name_en'], 'Jeonnam-Gwangju')

    def test_missing_english_stops(self):
        english = dict(ENGLISH)
        english['4129000000'] = ('과천시', None)
        with self.assertRaises(SystemExit):
            self.build(english=english)

    def test_unknown_sido_stops(self):
        with self.assertRaises(SystemExit):
            self.build(sido_rows=SIDO + [sido('99', '새도')])

    def test_sigungu_without_sido_stops(self):
        with self.assertRaises(SystemExit):
            self.build(sgg_rows=SGG + [sgg('26410', '금정구')], english={**ENGLISH, '2641000000': ('금정구', 'Geumjeong-gu')})


class Render(unittest.TestCase):

    def render(self):
        regions, cities = g.build(SIDO, SGG, ENGLISH)
        return g.render(regions, cities, 'codes.csv', 'a' * 64, 'en.xlsx', 'b' * 64)

    # 이미 있는 행의 열림 · 순서는 관리자 몫 — 다시 돌려도 이름만 고친다. 새 행은 열린 채로(전국 서비스).
    def test_rerun_touches_names_only_and_new_rows_are_open(self):
        sql = self.render()
        self.assertEqual(sql.count('ON DUPLICATE KEY UPDATE name_ko = VALUES(name_ko),\n                        name_en = VALUES(name_en);'), 2)
        self.assertNotRegex(sql, r'(is_active|sort_order|parent_id) += VALUES')
        self.assertIn("(NULL, '12', '전남광주통합특별시', 'Jeonnam-Gwangju', 1, 7)", sql)
        self.assertIn("SELECT '41', '41111', '수원시 장안구', 'Suwon-si Jangan-gu', 1", sql)
        self.assertIn('SELECT p.id, g.code, g.name_ko, g.name_en, 1, g.sort_order', sql)

    # 닫는 것은 RETIRED 표의 코드뿐 — '원천에 없는 코드 전부' 로 닫으면 관리자가 웹으로 만든 지역을 배포마다 닫는다
    # (그 경로는 매물 내림 · 알림도 없다).
    def test_only_declared_retired_codes_are_closed_not_deleted(self):
        sql = self.render()
        self.assertNotIn('DELETE', sql)
        self.assertIn("UPDATE regions\nSET is_active = 0\nWHERE is_active = 1\n  AND code IN ('29', '46');", sql)
        self.assertNotIn('NOT IN', sql)
        self.assertNotIn("'41110'", sql)

    def test_no_close_statement_when_nothing_is_retired(self):
        saved = dict(g.RETIRED)
        g.RETIRED.clear()
        try:
            sql = self.render()
        finally:
            g.RETIRED.update(saved)
        self.assertNotIn('UPDATE regions', sql)
        self.assertTrue(sql.rstrip().endswith('name_en = VALUES(name_en);'))

    def test_same_input_same_bytes(self):
        self.assertEqual(self.render(), self.render())


class RetireGuard(unittest.TestCase):

    def previous(self, text):
        f = tempfile.NamedTemporaryFile('w', suffix='.sql', delete=False, encoding='utf-8')
        f.write(text)
        f.close()
        self.addCleanup(os.remove, f.name)
        return f.name

    def test_declared_retirements_pass_and_others_stop(self):
        regions, _ = g.build(SIDO, SGG, ENGLISH)
        out = self.previous("(NULL, '11', '서울특별시', 'Seoul', 1, 1),\n(NULL, '29', '광주광역시', 'Gwangju', 0, 7),\n"
                            "SELECT '11110' code\nUNION ALL SELECT '11140', '중구', 'Jung-gu', 2\n")
        problems = g.guard_against_previous(regions, out)
        self.assertEqual(len(problems), 1)
        self.assertIn('11140', problems[0])
        self.assertNotIn("'29'", problems[0])


class Lookup(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.children, cls.dongs = rl.load()

    def of(self, address, sido=None):
        return rl.region_of(address, self.children, self.dongs, sido)

    def test_full_short_and_old_sido_names(self):
        self.assertEqual(self.of('서울특별시 관악구 관악로 1'), '11620')
        self.assertEqual(self.of('서울시 구로구 경인로 40길 47'), '11530')
        self.assertEqual(self.of('광주광역시 북구 용봉로 77'), '12300')
        self.assertEqual(self.of('전라남도 목포시 영산로 1'), '12110')
        self.assertEqual(self.of('강원도 춘천시 강원대학길 1'), '51110')

    def test_districts_of_a_city_missing_suffix_and_renamed_districts(self):
        self.assertEqual(self.of('경기도 수원시 장안구 서부로 2066'), '41111')
        self.assertEqual(self.of('경기도 남양주 다산중앙로 123번길(다산동)'), '41360')
        self.assertEqual(self.of('인천시 남구 숙골로 24번길 9'), '28177')
        self.assertEqual(self.of('인천광역시 동구 재능로 178'), '28125')

    # 구가 없는 주소는 읍면동으로 가른다 — 그 시(또는 시도) 안에서 그 이름이 한 시군구에만 있을 때만.
    def test_dong_decides_when_the_district_is_missing(self):
        self.assertEqual(self.of('경기도 화성시 봉담읍 와우안길 17'), '41593')
        self.assertEqual(self.of('경기도 성남시 성남대로 지하 903(야탑동 688-1)'), '41135')
        self.assertEqual(self.of('인창동 679-8 일원(구리도매시장)', sido='41'), '41310')

    def test_split_old_districts_and_unknowns_are_not_guessed(self):
        self.assertIsNone(self.of('인천광역시 중구 공항로 271'))
        self.assertIsNone(self.of('인천광역시 서구 에코로 163'))
        self.assertEqual(self.of('경기도 용인시 중부대로 460'), rl.UNKNOWN)
        self.assertEqual(self.of('경기도 시승시 신현로 12번길 지하 2-10'), rl.UNKNOWN)
        self.assertEqual(self.of('어딘가 1'), rl.UNKNOWN)

    def test_sejong_has_one_sigungu(self):
        self.assertEqual(self.of('세종특별자치시 조치원읍 세종로 2511'), '36110')


# 커밋된 생성 파일 · 장부가 서로 맞는지 — 손으로 고치거나 한쪽만 다시 만든 것을 잡는다.
class CommittedFiles(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.sql = g.read_text(g.OUT)
        cls.ledger = [line.split('\t') for line in g.read_text(g.LEDGER).splitlines()[1:]]
        cls.sido = re.findall(r"^    \(NULL, '(\d{2})', ", cls.sql, re.M)
        cls.sigungu = re.findall(r"SELECT '(\d{2})'(?: parent_code)?, '(\d{5})'(?: code)?, ", cls.sql)

    def test_header_ledger_and_sql_agree(self):
        m = re.search(r'(?m)^-- 행 수: 시도 (\d+) · 시군구 (\d+)', self.sql)
        self.assertEqual((len(self.sido), len(self.sigungu)), tuple(map(int, m.groups())))
        self.assertEqual({c for c, *_ in self.ledger}, set(self.sido) | {c for _, c in self.sigungu})
        self.assertTrue(all(c.startswith(p) for p, c in self.sigungu))
        self.assertTrue({p for p, _ in self.sigungu} <= set(self.sido))
        retire = re.search(r'AND code IN \(([^)]*)\);', self.sql).group(1)
        self.assertEqual(set(re.findall(r"'(\d+)'", retire)), set(g.RETIRED))
        self.assertFalse(set(g.RETIRED) & {c for c, *_ in self.ledger})

    def test_seoul_codes_are_unchanged_and_merged_sido_replaces_the_old_ones(self):
        self.assertEqual(sorted(c for p, c in self.sigungu if p == '11'), SEOUL)
        self.assertIn('12', self.sido)
        self.assertNotIn('29', self.sido)
        self.assertNotIn('46', self.sido)
        self.assertEqual(sum(1 for p, _ in self.sigungu if p == '12'), 27)

    def test_dong_index_points_at_known_sigungu(self):
        known = {c for c, *_ in self.ledger}
        rows = [line.split('\t') for line in g.read_text(g.DONG_LEDGER).splitlines()[1:]]
        self.assertTrue(rows)
        self.assertTrue(all(s in known and s.startswith(p) for p, _, s in rows))


if __name__ == '__main__':
    unittest.main()
