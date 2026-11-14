#!/usr/bin/env python3
# dml/tools/gen_universities.py 의 넣는 규칙을 합성 행으로 고정한다 — 원천 xlsx 없이 돈다(CI 에 원천이 없다).
# 실행(저장소 루트):  python3 -I -B -m unittest discover -s dml/tools -p 'test_*.py'
# 표준 라이브러리만 쓴다. render · schema_missing 은 저장소 루트 기준 경로(dml/03_regions.sql · 마이그레이션)를 읽는다.
import importlib.util
import os
import re
import tempfile
import unittest
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
# -I 는 스크립트 폴더를 sys.path 에 넣지 않는다 — 파일 경로로 직접 읽는다.
_spec = importlib.util.spec_from_file_location('gen_universities', os.path.join(HERE, 'gen_universities.py'))
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)

# 위도 0.0085° ≈ 945m(1km 안) · 0.0095° ≈ 1,056m(1km 밖)
NEAR = Decimal('0.0085')
FAR = Decimal('0.0095')
BASE_LAT = Decimal('35.2300000')


def setUpModule():
    os.chdir(ROOT)


def row(code, name, kind='대학', status='기존', program='대학', campus='본교', name_en=None,
        address='부산광역시 금정구 테스트로 1', lat=BASE_LAT, lng='129.0800000'):
    return {'kind': kind, 'code': code, 'name': name, 'campus': campus, 'program': program, 'status': status,
            'name_en': name_en or f'Test {code}', 'address': address,
            'lat': Decimal(lat).quantize(Decimal('0.0000001')), 'lng': Decimal(lng).quantize(Decimal('0.0000001'))}


def grad(code, name, lat, status='기존', name_en=None):
    return row(code, name, kind='대학원', program='대학원', status=status, lat=lat, name_en=name_en or f'Grad {code}')


def codes(points):
    return [p['code'] for p in points]


class BuildRules(unittest.TestCase):

    def test_closed_and_placeholder_rows_never_become_points(self):
        final, stats, *_ = g.build([row('0000010', '가대학교'), row('0000020', '폐교대학교', status='폐교'),
                                    row('0000639', 'KCUE대학교')])
        self.assertEqual(codes(final), ['0000010'])
        self.assertEqual((stats['closed'], stats['excluded']), (1, 1))

    def test_new_school_status_is_a_point(self):
        final, *_ = g.build([row('0000010', '신설대학교', status='신설')])
        self.assertEqual(codes(final), ['0000010'])

    def test_remote_schools_by_program_or_name_and_their_grads_are_dropped(self):
        rows = [row('0000010', '가대학교'),
                row('0000011', '나대학교', program='사이버대학(대학)'),
                row('0000012', '다전문대학', kind='전문대학', program='사이버대학(전문)'),
                row('0000013', '한국방송통신대학교', program='방송통신대학'),
                row('0000014', '라사이버대학교', program='대학'),
                grad('0000015', '나대학교 대학원', BASE_LAT + 1),
                grad('0000016', '라사이버대학교 대학원', BASE_LAT + 2)]
        final, stats, remote, extras, lone = g.build(rows)
        self.assertEqual(codes(final), ['0000010'])
        self.assertEqual(sorted(codes(remote)), ['0000011', '0000012', '0000013', '0000014'])
        self.assertEqual((stats['remote'], stats['remote_grads']), (4, 2))
        self.assertEqual((extras, lone), ([], []))

    def test_grad_within_1km_of_its_school_merges_into_the_campus_point(self):
        final, stats, _, extras, _ = g.build([row('0000010', '가대학교'), grad('0000030', '가대학교 일반대학원', BASE_LAT + NEAR)])
        self.assertEqual(codes(final), ['0000010'])
        self.assertEqual((stats['merged_grads'], extras), (1, []))

    def test_grad_beyond_1km_becomes_a_separate_campus_of_the_parent(self):
        final, stats, _, extras, _ = g.build([row('0000010', '가대학교', name_en='Ga University'),
                                               grad('0000030', '가대학교 일반대학원', BASE_LAT + FAR)])
        self.assertEqual(codes(final), ['0000010', '0000030'])
        extra = extras[0]
        # 이름 · 영문은 모체 학교의 것, 캠퍼스 이름은 비운다(원천에 없다), 주소 · 좌표는 대학원 행의 것.
        self.assertEqual((extra['name'], extra['name_en'], extra['campus']), ('가대학교', 'Ga University', '본교'))
        self.assertEqual(extra['lat'], BASE_LAT + FAR)
        self.assertEqual(stats['extras'], 1)

    def test_extra_campus_code_is_the_smallest_in_its_place_counting_closed_grads(self):
        rows = [row('0000010', '가대학교'),
                grad('0000030', '가대학교 일반대학원', BASE_LAT + FAR),
                grad('0000025', '가대학교 옛대학원', BASE_LAT + FAR + Decimal('0.0002'), status='폐교')]
        _, _, _, extras, _ = g.build(rows)
        self.assertEqual(codes(extras), ['0000025'])

    def test_separate_campus_grads_within_1km_share_one_point_and_far_ones_get_their_own(self):
        rows = [row('0000010', '가대학교'),
                grad('0000030', '가대학교 일반대학원', BASE_LAT + 1),
                grad('0000031', '가대학교 경영대학원', BASE_LAT + 1 + NEAR),
                grad('0000040', '가대학교 의학대학원', BASE_LAT + 2)]
        _, _, _, extras, _ = g.build(rows)
        self.assertEqual(sorted(codes(extras)), ['0000030', '0000040'])

    # '연세대학교(미래) 대학원' 은 '연세대학교(' 로도 시작한다 — 가장 긴 모체 이름을 골라야 원주 캠퍼스에 붙는다.
    def test_longest_parent_name_wins(self):
        rows = [row('0000010', '가대학교', lat=BASE_LAT),
                row('0000020', '가대학교(미래)', lat=BASE_LAT + 1),
                grad('0000030', '가대학교(미래) 대학원', BASE_LAT + 1 + NEAR)]
        final, stats, _, extras, _ = g.build(rows)
        self.assertEqual((codes(final), extras, stats['merged_grads']), (['0000010', '0000020'], [], 1))

    def test_main_school_for_english_name_is_the_smallest_code_of_that_name(self):
        rows = [row('0000012', '가대학교', campus='제2캠퍼스', name_en='Ga University Campus', lat=BASE_LAT + Decimal('0.5')),
                row('0000010', '가대학교', name_en='Ga University'),
                grad('0000030', '가대학교 대학원', BASE_LAT + 2)]
        _, _, _, extras, _ = g.build(rows)
        self.assertEqual(extras[0]['name_en'], 'Ga University')

    def test_grad_without_parent_merges_into_any_point_within_1km_else_stands_alone(self):
        rows = [row('0000010', '가대학교'),
                grad('0000030', '홀로대학원 대학원', BASE_LAT + NEAR),
                grad('0000040', '외딴대학원 대학원', BASE_LAT + 1, name_en='Lone Graduate School')]
        final, stats, _, _, lone = g.build(rows)
        self.assertEqual(codes(final), ['0000010', '0000040'])
        self.assertEqual((lone[0]['name'], lone[0]['name_en'], lone[0]['campus']),
                         ('외딴대학원 대학원', 'Lone Graduate School', '본교'))
        self.assertEqual((stats['merged_grads'], stats['orphans']), (1, 1))

    def test_points_are_sorted_by_code(self):
        final, *_ = g.build([row('0000300', '다대학교'), row('0000100', '가대학교', lat=BASE_LAT + 1),
                             row('0000200', '나대학교', lat=BASE_LAT + 2)])
        self.assertEqual(codes(final), ['0000100', '0000200', '0000300'])


class Normalisers(unittest.TestCase):

    def test_english_fixes_only_case_slips(self):
        self.assertEqual(g.english('University Of  Seoul'), 'University of Seoul')
        self.assertEqual(g.english('Of Something And The For'), 'Of Something and the for')
        self.assertEqual(g.english("Ewha Womans' Women'S University"), "Ewha Womans' Women's University")
        self.assertEqual(g.english('Yong In University'), 'Yong In University')

    def test_seven_rounds_half_up_to_seven_places(self):
        self.assertEqual(g.seven(' 37.12345678 '), Decimal('37.1234568'))
        self.assertEqual(g.seven('37.12345665'), Decimal('37.1234567'))
        self.assertEqual(g.seven('127'), Decimal('127.0000000'))

    def test_sql_text_escapes_quote_and_backslash(self):
        self.assertEqual(g.sql_text(None), 'NULL')
        self.assertEqual(g.sql_text("Int'l \\ U"), "'Int''l \\\\ U'")

    def test_meters_is_about_111km_per_degree_of_latitude(self):
        d = g.meters({'lat': 35, 'lng': 129}, {'lat': 36, 'lng': 129})
        self.assertAlmostEqual(d, 111_195, delta=5)


class Render(unittest.TestCase):

    def render(self, rows):
        final, stats, *_ = g.build(rows)
        return g.render(final, stats, 'x' * 64, 'fixture.xlsx')

    def test_rows_carry_seoul_gu_campus_languages_and_upsert_every_column(self):
        sql = self.render([row('0000010', '가대학교', address='서울특별시 관악구 관악로 1', name_en="Ga Int'l University"),
                           row('0000011', '가대학교', campus='제2캠퍼스', lat=BASE_LAT + 1),
                           row('0000012', '나대학교', campus='분교', lat=BASE_LAT + 2)])
        self.assertIn("    ('0000010', '가대학교', 'Ga Int''l University', NULL, NULL, '서울특별시 관악구 관악로 1', "
                      "35.2300000, 129.0800000, (SELECT id FROM regions WHERE code = '11620'), 1)", sql)
        self.assertIn("'제2캠퍼스', 'Campus 2', '부산광역시 금정구 테스트로 1', 36.2300000, 129.0800000, "
                      "(SELECT id FROM regions WHERE code = '26410'), 1)", sql)
        self.assertIn("'분교', 'Branch Campus'", sql)
        for col in ('name_ko', 'name_en', 'campus_name_ko', 'campus_name_en', 'address_road', 'lat', 'lng', 'region_id', 'is_active'):
            self.assertRegex(sql, r'(?:UPDATE|\n) +%s += VALUES\(%s\)[,;]' % (col, col))
        self.assertIn("SET is_active = 0\nWHERE is_active = 1\n  AND code NOT IN ('0000010', '0000011', '0000012');", sql)
        # MasterDataDmlMySqlTest 가 이 머리말로 활성 행 수를 맞춘다.
        self.assertRegex(sql, r'(?m)^-- 행 수: 3 = 학교 캠퍼스 3\(')
        self.assertIn('시군구가 붙은 행 3', sql)
        self.assertIn('-- 시군구를 못 정한 행: 없음', sql)

    def test_unknown_campus_value_stops(self):
        with self.assertRaises(SystemExit):
            self.render([row('0000010', '가대학교', campus='제5캠퍼스')])

    # 원천이 줄인 이름 · 개편 전 이름을 써도 지금 시군구에 붙는다(전남광주 · 강원 · 인천 개편 — region_lookup.py).
    def test_short_and_old_sido_names_still_map(self):
        sql = self.render([row('0000010', '가대학교', address='서울 관악구 관악로 1'),
                           row('0000011', '나대학교', address='광주광역시 북구 테스트로 1', lat=BASE_LAT + 1),
                           row('0000012', '다대학교', address='전라남도 목포시 테스트로 1', lat=BASE_LAT + 2),
                           row('0000013', '라대학교', address='강원도 춘천시 테스트로 1', lat=BASE_LAT + 3),
                           row('0000014', '마대학교', address='인천광역시 동구 재능로 1', lat=BASE_LAT + 4)])
        for code, region in (('0000010', '11620'), ('0000011', '12300'), ('0000012', '12110'), ('0000013', '51110'),
                             ('0000014', '28125')):
            self.assertRegex(sql, r"\('%s', .*\(SELECT id FROM regions WHERE code = '%s'\), 1\)" % (code, region))

    # 개편으로 쪼개진 옛 구(인천 중구)는 도로명만으로 새 구를 모른다 — 비워 두고 머리말에 적는다(서울 밖이라 멈추지 않는다).
    def test_unknowable_region_is_null_and_listed(self):
        sql = self.render([row('0000010', '가대학교', address='인천광역시 중구 공항로 271')])
        self.assertRegex(sql, r"\('0000010', .*, NULL, 1\)")
        self.assertIn('-- 시군구를 못 정한 행: 0000010 가대학교', sql)

    def test_seoul_address_without_known_gu_stops(self):
        with self.assertRaises(SystemExit):
            self.render([row('0000010', '가대학교', address='서울특별시 없는구 관악로 1')])

    def test_blank_english_name_stops(self):
        r = row('0000010', '가대학교')
        r['name_en'] = ''
        final, stats, *_ = g.build([r])
        with self.assertRaises(SystemExit):
            g.render(final, stats, 'x' * 64, 'fixture.xlsx')

    def test_value_longer_than_column_stops(self):
        with self.assertRaises(SystemExit):
            self.render([row('0000010', '가' * 101)])

    def test_same_input_gives_same_bytes(self):
        rows = [row('0000010', '가대학교'), grad('0000030', '가대학교 대학원', BASE_LAT + 1)]
        self.assertEqual(self.render(rows), self.render(rows))


class GuardAgainstPrevious(unittest.TestCase):

    def previous(self, rows):
        final, stats, *_ = g.build(rows)
        f = tempfile.NamedTemporaryFile('w', suffix='.sql', delete=False, encoding='utf-8')
        f.write(g.render(final, stats, 'x' * 64, 'fixture.xlsx'))
        f.close()
        self.addCleanup(os.remove, f.name)
        return f.name

    @staticmethod
    def spread(n):
        return [row(f'{i:07d}', f'학교{i}', lat=BASE_LAT + i) for i in range(1, n + 1)]

    def test_no_previous_file_has_nothing_to_compare(self):
        self.assertEqual(g.guard_against_previous(g.build(self.spread(1))[0], '/nonexistent/04.sql'), [])

    def test_more_than_ten_retired_stops(self):
        out = self.previous(self.spread(12))
        problems = g.guard_against_previous(g.build(self.spread(1))[0], out)
        self.assertEqual(len(problems), 1)
        self.assertIn('11개', problems[0])

    def test_exactly_ten_retired_is_allowed(self):
        out = self.previous(self.spread(12))
        self.assertEqual(g.guard_against_previous(g.build(self.spread(2))[0], out), [])

    def test_same_school_in_the_same_place_under_a_new_code_stops(self):
        out = self.previous([row('0000100', '가대학교')])
        problems = g.guard_against_previous(g.build([row('0000101', '가대학교', lat=BASE_LAT + Decimal('0.0018'))])[0], out)
        self.assertEqual(len(problems), 1)
        self.assertIn('0000101', problems[0])

    def test_retiring_next_to_a_surviving_sibling_is_not_a_move(self):
        out = self.previous([row('0000200', '나대학교', campus='제2캠퍼스'),
                             row('0000201', '나대학교', lat=BASE_LAT + Decimal('0.0018'))])
        self.assertEqual(g.guard_against_previous(g.build([row('0000201', '나대학교', lat=BASE_LAT + Decimal('0.0018'))])[0], out), [])


class SchemaGate(unittest.TestCase):

    def migrations(self, sql):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        with open(os.path.join(d.name, 'V1__x.sql'), 'w', encoding='utf-8') as f:
            f.write(sql)
        old = g.MIGRATIONS
        g.MIGRATIONS = d.name
        self.addCleanup(setattr, g, 'MIGRATIONS', old)

    def test_missing_table_or_column_sends_output_to_pending(self):
        self.migrations('CREATE TABLE other (id BIGINT) ENGINE = InnoDB;')
        self.assertEqual(g.schema_missing(), ['universities'])
        cols = ',\n'.join(f'    {c} VARCHAR(10)' for c in g.REQUIRED_COLUMNS['universities'] if c != 'region_id')
        self.migrations(f'CREATE TABLE universities\n(\n{cols}\n) ENGINE = InnoDB;')
        self.assertEqual(g.schema_missing(), ['universities.region_id'])

    # 배포 순서 — V 마이그레이션이 테이블을 만들었으면 생성 파일은 dml/ 맨 위(CD 대상)에 있어야 하고 pending 에는 없어야 한다.
    def test_repository_ships_04_at_top_level(self):
        self.assertEqual(g.schema_missing(), [])
        self.assertTrue(os.path.exists(g.OUT))
        self.assertFalse(os.path.exists(g.PENDING_OUT))


# 커밋된 생성 파일이 스스로 맞는지 — 손으로 고치거나 반쯤 다시 만든 파일을 잡는다(원천 없이 본다).
class CommittedFile(unittest.TestCase):

    def test_header_insert_and_retire_list_agree(self):
        text = open(g.OUT, encoding='utf-8').read()
        inserted = re.findall(r"^    \('(\d{7})', ", text, re.M)
        total = int(re.search(r'(?m)^-- 행 수: (\d+) = ', text).group(1))
        retire = re.search(r'AND code NOT IN \(([^)]*)\);', text).group(1)
        self.assertEqual(len(inserted), total)
        self.assertEqual(len(set(inserted)), total)
        self.assertEqual(inserted, sorted(inserted))
        self.assertEqual(re.findall(r"'(\d{7})'", retire), inserted)
        self.assertEqual(text.count('(SELECT id FROM regions WHERE code ='),
                         int(re.search(r'시군구가 붙은 행 (\d+)', text).group(1)))
        self.assertTrue(set(g.EXCLUDE).isdisjoint(inserted))


if __name__ == '__main__':
    unittest.main()
