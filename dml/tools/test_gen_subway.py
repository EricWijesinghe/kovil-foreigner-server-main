#!/usr/bin/env python3
# dml/tools/gen_subway.py 의 묶기 · 코드 · 출력 규칙을 합성 행으로 고정한다 — 원천 xlsx 없이 돈다(CI 에 원천이 없다).
# 실행(저장소 루트):  python3 -I -B -m unittest discover -s dml/tools -p 'test_*.py'
# 표준 라이브러리만 쓴다. render · schema_missing 은 저장소 루트 기준 경로(dml/03_regions.sql · 마이그레이션)를 읽는다.
import collections
import importlib.util
import os
import re
import tempfile
import unittest
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
# -I 는 스크립트 폴더를 sys.path 에 넣지 않는다 — 파일 경로로 직접 읽는다.
_spec = importlib.util.spec_from_file_location('gen_subway', os.path.join(HERE, 'gen_subway.py'))
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)

L1 = ('I4101', '1호선')
L2 = ('S1102', '2호선')
L4 = ('I1104', '4호선')
L5 = ('S1105', '5호선')
L7 = ('S1107', '7호선')
GJ = ('I4108', '경의중앙선')
GW = ('I4102', '경원선')
ICN1 = ('S2801', '인천지하철 1호선')
BUSAN1 = ('S2601', '부산 도시철도 1호선')
BUSAN2 = ('S2602', '부산 도시철도 2호선')
DAEGU1 = ('S2701', '대구 도시철도 1호선')
DAEGYEONG = ('I27K7', '대경선')
BUSAN_ADDR = '부산광역시 부산진구 테스트로 1'
SEOUL = '서울특별시 마포구 테스트로 1'
# MasterDataDmlMySqlTest 도 같은 식으로 머리말을 읽는다 — 시도 목록은 역이 있는 시도만, 서울이 맨 앞.
JSON_HEADER = r'(?m)^-- 행 수: 노선 (\d+) · 역 (\d+)\(서울 (\d+)(?: · \S+ \d+)*, 환승역 (\d+)\) · 역-노선 (\d+)$'


def setUpModule():
    os.chdir(ROOT)


# load() 가 만드는 행과 같은 모양 — 파생 칸은 변환기 함수로 채운다(테스트가 규칙을 따로 흉내 내지 않게).
def src(n, line, station_no, name, en, lat, lng, xfer='', op='서울교통공사', addr=SEOUL, date='2026-01-01'):
    row = {'역번호': station_no, '역사명': name, '노선번호': line[0], '노선명': line[1], '영문역사명': en,
           '환승노선명': xfer, '역위도': str(lat), '역경도': str(lng), '운영기관명': op, '역사도로명주소': addr,
           '데이터기준일자': date, '_row': n, 'pt': (float(lat), float(lng))}
    row['sido'] = g.sido_of(addr, op)
    row['key'] = g.name_key(name)
    row['aliases'] = g.paren_aliases(name)
    row['date'] = g.norm_date(date)
    row['bad'] = g.BAD_COORD.get((line[1], name, f"{row['pt'][0]:.6f}", f"{row['pt'][1]:.6f}"))
    row['drop'] = g.DROP_ROWS.get((line[0], line[1], station_no, name))
    return row


def by_key(stations):
    return {s['key']: s for s in stations}


class Grouping(unittest.TestCase):

    def test_same_name_within_500m_is_one_marker_with_lines_in_line_order(self):
        stations, unplaced, stats = g.build([
            src(2, L5, '2541', '왕십리', 'Wangsimni', 37.5610, 127.0370),
            src(3, L2, '0208', '왕십리(성동구청)', 'Wangsimni(Seongdong-gu Office)', 37.5612, 127.0373)])
        self.assertEqual(len(stations), 1)
        s = stations[0]
        self.assertEqual((s['name_ko'], s['name_en'], s['lines']), ('왕십리역', 'Wangsimni', ['SEL-2', 'SEL-5']))
        # 좌표 = 묶은 행의 평균, 소수 7자리
        self.assertEqual((s['lat'], s['lng']), (Decimal('37.5611000'), Decimal('127.0371500')))
        self.assertEqual((unplaced, stats['scoped_rows']), ([], 2))

    def test_same_name_farther_than_500m_without_transfer_note_stays_two_markers(self):
        stations, _, _ = g.build([
            src(2, L2, '0240', '신촌', 'Sinchon', 37.5552, 126.9369),
            src(3, GJ, '1262', '신촌', 'Sinchon', 37.5598, 126.9423, op='한국철도공사', addr='서울특별시 서대문구 신촌역로 1')])
        self.assertEqual(sorted(s['lines'][0] for s in stations), ['GYEONGUI-JUNGANG', 'SEL-2'])

    def test_transfer_note_joins_same_name_up_to_1km(self):
        stations, _, _ = g.build([
            src(2, L2, '0240', '신촌', 'Sinchon', 37.5552, 126.9369, xfer='경의중앙선'),
            src(3, GJ, '1262', '신촌', 'Sinchon', 37.5598, 126.9423, xfer='2호선', op='한국철도공사')])
        self.assertEqual([s['lines'] for s in stations], [['SEL-2', 'GYEONGUI-JUNGANG']])

    # 1km 밖이면 환승칸이 있어도 묶지 않는다 — 그러면 그 역에 환승칸의 노선이 없어 멈춘다(사람이 보게).
    def test_transfer_note_beyond_1km_does_not_join_and_stops(self):
        with self.assertRaises(SystemExit):
            g.build([src(2, L2, '0240', '신촌', 'Sinchon', 37.5552, 126.9369, xfer='경의중앙선'),
                     src(3, GJ, '1262', '신촌', 'Sinchon', 37.5652, 126.9369, xfer='2호선', op='한국철도공사')])

    def test_parenthesised_alias_joins_and_keeps_the_full_name(self):
        stations, _, _ = g.build([
            src(2, L4, '0433', '총신대입구(이수)', 'Chongshin Univ.(Isu)', 37.4865, 126.9819),
            src(3, L7, '2737', '이수', 'Isu', 37.4867, 126.9821)])
        s = stations[0]
        self.assertEqual((s['name_ko'], s['name_en'], s['keys'], s['lines']),
                         ('총신대입구(이수)역', 'Chongshin Univ.(Isu)', ['이수', '총신대입구'], ['SEL-4', 'SEL-7']))

    # 노선 순서로는 1호선(코레일) 행이 앞이지만 운영기관 순서가 먼저다 — 대표 이름은 2호선(서울교통공사) 행의 것.
    def test_representative_row_follows_operator_before_line_order_and_ignores_row_order(self):
        stations, _, _ = g.build([
            src(2, L1, '0141', '신도림', 'Sindorim Korail', 37.5089, 126.8913, op='한국철도공사', xfer='2호선'),
            src(3, L2, '0234', '신도림', 'Sindorim', 37.5088, 126.8912, xfer='1호선')])
        self.assertEqual((stations[0]['name_en'], stations[0]['lines']), ('Sindorim', ['SEL-1', 'SEL-2']))
        stations, _, _ = g.build([
            src(2, GJ, '1262', '공덕', 'Gongdeok Korail', 37.5440, 126.9516, op='한국철도공사', xfer='5호선'),
            src(3, L5, '2529', '공덕', 'Gongdeok', 37.5441, 126.9517, xfer='경의중앙선')])
        self.assertEqual((stations[0]['name_en'], stations[0]['lines']), ('Gongdeok', ['SEL-5', 'GYEONGUI-JUNGANG']))

    def test_duplicate_source_rows_keep_the_newest(self):
        stations, _, stats = g.build([
            src(2, L2, '0201', '시청', 'Old Name', 37.5657, 126.9772, date='2024-01-01'),
            src(3, L2, '0201', '시청', 'City Hall', 37.5657, 126.9772, date='2026-01-01')])
        self.assertEqual((len(stations), stations[0]['name_en'], stats['dup_rows']), (1, 'City Hall', 1))

    def test_dropped_and_maglev_rows_are_left_out_and_other_cities_are_in(self):
        stations, _, stats = g.build([
            src(2, ('I4108', '경의중앙선'), '1019', '광운대역', 'Kwangwoon Univ.', 37.6236, 127.0619, op='한국철도공사'),
            src(3, GW, '1018', '광운대', 'Kwangwoon Univ.', 37.6236, 127.0619, op='한국철도공사'),
            src(4, ('S28M1', '자기부상철도'), 'M01', '용유', 'Yongyu', 37.4400, 126.4000, op='인천교통공사',
                addr='인천광역시 중구 테스트로 1'),
            src(5, BUSAN1, '119', '서면', 'Seomyeon', 35.1578, 129.0592, op='부산광역시 부산교통공사', addr=BUSAN_ADDR)])
        self.assertEqual([(s['key'], s['sido'], s['lines']) for s in stations],
                         [('광운대', '서울', ['SEL-1']), ('서면', '부산', ['BUSAN-1'])])
        self.assertEqual((stats['dropped_rows'], stats['scoped_rows'], stats['source_rows']), (1, 2, 4))

    # 주소로 시도를 못 정하면(시도 없는 주소 · 모르는 운영기관) 조용히 빼지 않고 멈춘다.
    def test_row_without_a_known_sido_stops(self):
        with self.assertRaises(SystemExit):
            g.build([src(2, L2, '0201', '시청', 'City Hall', 37.5657, 126.9772, op='모르는기관', addr='테스트로 110')])

    # 부산 · 대구 환승칸은 '부산 도시철도 2호선' 처럼 도시가 붙는다 — 도시를 안 보면 서울 2호선으로 읽어 역에 없는 노선이 되고 멈춘다.
    def test_other_city_transfer_notes_join_their_own_lines(self):
        stations, _, _ = g.build([
            src(2, BUSAN1, '119', '서면', 'Seomyeon', 35.1578, 129.0592, xfer='부산 도시철도 2호선',
                op='부산광역시 부산교통공사', addr=BUSAN_ADDR),
            src(3, BUSAN2, '219', '서면', 'Seomyeon', 35.1572, 129.0596, xfer='부산 도시철도 1호선',
                op='부산광역시 부산교통공사', addr=BUSAN_ADDR),
            src(4, DAEGU1, '133', '대구', 'Daegu', 35.8792, 128.6283, xfer='대경선', op='대구교통공사',
                addr='대구광역시 북구 테스트로 1'),
            src(5, DAEGYEONG, 'K01', '대구', 'Daegu', 35.8790, 128.6286, xfer='대구도시철도1호선', op='한국철도공사',
                addr='대구광역시 북구 테스트로 2')])
        self.assertEqual({k: s['lines'] for k, s in by_key(stations).items()},
                         {'서면': ['BUSAN-1', 'BUSAN-2'], '대구': ['DAEGU-1', 'DAEGYEONG']})

    def test_gyeongwon_rows_between_yongsan_and_wangsimni_are_gyeongui_jungang(self):
        stations, _, _ = g.build([
            src(2, GW, '1011', '옥수', 'Oksu', 37.5406, 127.0186, op='한국철도공사'),
            src(3, GW, '1018', '광운대', 'Kwangwoon Univ.', 37.6236, 127.0619, op='한국철도공사')])
        self.assertEqual({k: s['lines'] for k, s in by_key(stations).items()},
                         {'옥수': ['GYEONGUI-JUNGANG'], '광운대': ['SEL-1']})

    def test_unknown_line_in_scope_stops(self):
        with self.assertRaises(SystemExit):
            g.build([src(2, ('Z0000', '새노선'), '1', '새역', 'New', 37.5, 127.0)])

    def test_transfer_to_a_line_missing_from_the_marker_stops_unless_ignored(self):
        with self.assertRaises(SystemExit):
            g.build([src(2, L2, '0222', '강남', 'Gangnam', 37.4979, 127.0276, xfer='신분당선')])
        stations, _, _ = g.build([src(2, L2, '0222', '강남', 'Gangnam', 37.4979, 127.0276, xfer='GTX-A')])
        self.assertEqual(stations[0]['lines'], ['SEL-2'])

    def test_extra_lines_add_lines_known_only_from_transfer_notes(self):
        stations, _, _ = g.build([src(2, L5, '2519', '까치산', 'Kkachisan', 37.5317, 126.8467, xfer='2호선',
                                      addr='서울특별시 강서구 테스트로 1')])
        self.assertEqual(stations[0]['lines'], ['SEL-2', 'SEL-5'])

    def test_bad_coordinate_rows_do_not_move_the_marker(self):
        stations, unplaced, _ = g.build([
            src(2, L4, '0430', '이촌(국립중앙박물관)', 'Ichon', '37.529190', '126.967982'),
            src(3, GW, '1012', '이촌', 'Ichon', 37.5270, 126.9690, op='한국철도공사')])
        self.assertEqual(unplaced, [])
        s = stations[0]
        self.assertEqual((s['lat'], s['lng'], s['lines']), (Decimal('37.5270000'), Decimal('126.9690000'),
                                                             ['SEL-4', 'GYEONGUI-JUNGANG']))

    def test_marker_with_only_bad_rows_is_reported_not_placed(self):
        stations, unplaced, _ = g.build([src(2, L5, '2513', '마곡', 'Magok', '37.558650', '126.837693',
                                             addr='서울특별시 강서구 테스트로 1')])
        self.assertEqual(stations, [])
        self.assertEqual([(name, lines) for name, lines, _ in unplaced], [('마곡역', ['SEL-5'])])

    def test_stations_are_ordered_by_sido_then_first_line_then_name(self):
        stations, _, _ = g.build([
            src(7, BUSAN1, '119', '서면', 'Seomyeon', 35.1578, 129.0592, op='부산광역시 부산교통공사', addr=BUSAN_ADDR),
            src(8, L1, '0170', '천안', 'Cheonan', 36.8101, 127.1464, op='한국철도공사', addr='충청남도 천안시 동남구 테스트로 1'),
            src(2, ICN1, 'I01', '계양', 'Gyeyang', 37.5714, 126.7360, op='인천교통공사', addr='인천광역시 계양구 테스트로 1'),
            src(3, L4, '0450', '과천', 'Gwacheon', 37.4330, 126.9965, addr='경기도 과천시 테스트로 1'),
            src(4, L2, '0201', '나역', 'Na', 37.50, 127.00),
            src(5, L1, '0150', '다역', 'Da', 37.52, 127.02),
            src(6, L1, '0151', '가역', 'Ga', 37.54, 127.04)])
        self.assertEqual([s['key'] for s in stations], ['가', '다', '나', '과천', '계양', '서면', '천안'])


class Normalisers(unittest.TestCase):

    def test_name_key_drops_parentheses_spaces_and_trailing_station(self):
        self.assertEqual(g.name_key('서울역'), '서울')
        self.assertEqual(g.name_key('역'), '역')
        self.assertEqual(g.name_key('총신대입구(이수)'), '총신대입구')
        self.assertEqual(g.name_key(' 디지털 미디어시티 '), '디지털미디어시티')
        self.assertEqual(g.paren_aliases('총신대입구(이수역)'), ['이수'])

    def test_english_strips_trailing_parenthesis_fixes_known_typos_and_capitalises(self):
        self.assertEqual(g.english('Isu (Chongshin Univ.)'), 'Isu')
        self.assertEqual(g.english('Danggogae'), 'Buramsan')
        self.assertEqual(g.english('hyochang park'), 'Hyochang Park')
        self.assertEqual(g.english('Gimpo Int’l Airport'), "Gimpo Int'l Airport")
        self.assertEqual(g.clean_en('Chongshin Univ.(Isu)'), 'Chongshin Univ.(Isu)')

    def test_norm_date_reads_excel_serials_and_blanks(self):
        self.assertEqual(g.norm_date('1900-01-00'), '')
        self.assertEqual(g.norm_date('45658'), '2025-01-01')
        self.assertEqual(g.norm_date('20260618'), '2026-06-18')
        self.assertEqual(g.norm_date('2026-06-18'), '2026-06-18')
        self.assertEqual(g.norm_date('어제'), '')

    def test_transfer_lines_reads_every_line_once(self):
        self.assertEqual(g.transfer_lines('신분당선'), {'SHINBUNDANG'})
        self.assertEqual(g.transfer_lines('서울 도시철도 3호선 서울 도시철도 7호선'), {'SEL-3', 'SEL-7'})
        self.assertEqual(g.transfer_lines('인천지하철 1호선'), {'ICN-1'})
        self.assertEqual(g.transfer_lines('의정부'), {'UIJEONGBU'})
        self.assertEqual(g.transfer_lines('수인분당선'), {'SUIN-BUNDANG'})
        self.assertEqual(g.transfer_lines('경인선'), {'SEL-1'})
        self.assertEqual(g.transfer_lines('GTX-A'), {'GTX-A'})

    def test_transfer_lines_reads_other_cities_by_their_prefix(self):
        self.assertEqual(g.transfer_lines('부산 도시철도 2호선'), {'BUSAN-2'})
        self.assertEqual(g.transfer_lines('부산 경량도시철도 4호선'), {'BUSAN-4'})
        self.assertEqual(g.transfer_lines('대구도시철도1호선'), {'DAEGU-1'})
        self.assertEqual(g.transfer_lines('광주도시철도 1호선'), {'GWANGJU-1'})
        self.assertEqual(g.transfer_lines('대전 도시철도 1호선'), {'DAEJEON-1'})
        self.assertEqual(g.transfer_lines('부산김해경전철'), {'BUSAN-GIMHAE'})
        self.assertEqual(g.transfer_lines('동해선'), {'DONGHAE'})
        self.assertEqual(g.transfer_lines('대경선'), {'DAEGYEONG'})
        self.assertEqual(g.transfer_lines('서울 도시철도 2호선'), {'SEL-2'})

    def test_sido_of_reads_prefix_then_city_then_operator(self):
        self.assertEqual(g.sido_of('서울특별시 마포구 테스트로 1', '서울교통공사'), '서울')
        self.assertEqual(g.sido_of('충청남도 천안시', '한국철도공사'), '충남')
        self.assertEqual(g.sido_of('경산시 하양읍', '대구교통공사'), '경북')
        self.assertEqual(g.sido_of('달서구 테스트로 1', '대구교통공사'), '대구')
        self.assertEqual(g.sido_of('', '모르는기관'), '?')

    def test_q_escapes_quote_and_backslash(self):
        self.assertEqual(g.q(None), 'NULL')
        self.assertEqual(g.q("Int'l \\"), "'Int''l \\\\'")


def ledger_row(code, keys, lat, lng, active='1'):
    return {'code': code, 'name_key': keys, 'lat': str(lat), 'lng': str(lng), 'active': active}


def station(key, lat, lng, keys=None):
    return {'key': key, 'keys': keys or [key], 'lat': Decimal(str(lat)), 'lng': Decimal(str(lng))}


class Codes(unittest.TestCase):

    def test_station_within_300m_of_its_ledger_row_keeps_its_code(self):
        s = station('신촌', '37.5560000', '126.9369000')
        g.assign_codes([s], [ledger_row('ST0001', '신촌', '37.5552000', '126.9369000')])
        self.assertEqual(s['code'], 'ST0001')

    def test_new_station_gets_the_next_number_after_every_ledger_code_even_retired(self):
        s = station('신촌', '37.5600000', '126.9369000')
        out = g.assign_codes([s], [ledger_row('ST0001', '신촌', '37.5552000', '126.9369000'),
                                   ledger_row('ST0005', '옛역', '37.0000000', '127.0000000', active='0')])
        self.assertEqual(s['code'], 'ST0006')
        self.assertEqual([(r['code'], r['active']) for r in out], [('ST0001', '0'), ('ST0005', '0'), ('ST0006', '1')])

    def test_any_name_in_a_merged_ledger_row_matches(self):
        s = station('이수', '37.4866000', '126.9820000')
        out = g.assign_codes([s], [ledger_row('ST0010', '총신대입구|이수', '37.4866000', '126.9820000')])
        self.assertEqual(s['code'], 'ST0010')
        self.assertEqual(out[0]['name_key'], '이수')

    def test_two_stations_never_share_one_ledger_code(self):
        a = station('신촌', '37.5552000', '126.9369000')
        b = station('신촌', '37.5560000', '126.9369000')
        g.assign_codes([a, b], [ledger_row('ST0001', '신촌', '37.5552000', '126.9369000')])
        self.assertEqual(sorted([a['code'], b['code']]), ['ST0001', 'ST0002'])

    def test_ledger_keeps_the_representative_name_first_and_sorts_by_code(self):
        s = station('총신대입구', '37.4866000', '126.9820000', keys=['이수', '총신대입구'])
        out = g.assign_codes([s], [ledger_row('ST0002', '가', '37.0000000', '127.0000000')])
        self.assertEqual([(r['code'], r['name_key']) for r in out], [('ST0002', '가'), ('ST0003', '총신대입구|이수')])


class LedgerGuard(unittest.TestCase):

    @staticmethod
    def spread(n, active='1'):
        return [ledger_row(f'ST{i:04d}', f'역{i}', 37 + i * 0.1, 127, active) for i in range(1, n + 1)]

    def test_more_than_ten_retired_stops_and_ten_is_allowed(self):
        before = self.spread(12)
        eleven = [dict(r, active='0') if i > 0 else r for i, r in enumerate(before)]
        ten = [dict(r, active='0') if i > 1 else r for i, r in enumerate(before)]
        self.assertEqual(len(g.guard_against_ledger(before, eleven)), 1)
        self.assertEqual(g.guard_against_ledger(before, ten), [])

    def test_already_retired_codes_do_not_count_again(self):
        before = self.spread(12, active='0')
        self.assertEqual(g.guard_against_ledger(before, before), [])

    def test_same_station_under_a_new_code_stops(self):
        before = [ledger_row('ST0001', '총신대입구|이수', '37.4866000', '126.9820000')]
        after = [dict(before[0], active='0'), ledger_row('ST0002', '이수', '37.4880000', '126.9820000')]
        problems = g.guard_against_ledger(before, after)
        self.assertEqual(len(problems), 1)
        self.assertIn('ST0002', problems[0])


class Render(unittest.TestCase):

    def build(self, rows):
        stations, unplaced, stats = g.build(rows)
        g.assign_codes(stations, [])
        return stations, g.render(stations, unplaced, stats, 'x' * 64, 'fixture.xlsx', [])

    def test_sql_carries_used_lines_regions_links_and_a_parsable_header(self):
        stations, sql = self.build([
            src(2, L5, '2541', '왕십리', 'Wangsimni', 37.5610, 127.0370, addr='서울특별시 성동구 왕십리로 1'),
            src(3, L2, '0208', '왕십리(성동구청)', 'Wangsimni', 37.5612, 127.0373, addr='서울특별시 성동구 왕십리로 1'),
            src(4, L5, '2561', '김포공항', "Gimpo Int'l Airport", 37.5622, 126.8017, addr='서울특별시 강서구 하늘길 1'),
            src(5, L4, '0450', '과천', 'Gwacheon', 37.4330, 126.9965, addr='경기도 과천시 테스트로 1')])
        self.assertIn("    ('SEL-2', '2호선', 'Line 2', '#00A84D', 1, 2),", sql)
        self.assertIn("    ('SEL-5', '5호선', 'Line 5', '#996CAC', 1, 5)\n", sql)
        self.assertNotIn("'SEL-1'", sql)
        self.assertIn("AND code NOT IN ('SEL-2', 'SEL-4', 'SEL-5');", sql)
        codes = {s['key']: s['code'] for s in stations}
        self.assertIn(f"    ('{codes['왕십리']}', '왕십리역', 'Wangsimni', 37.5611000, 127.0371500, "
                      "(SELECT id FROM regions WHERE code = '11200'), 1)", sql)
        self.assertIn(f"('{codes['김포공항']}', '김포공항역', 'Gimpo Int''l Airport', ", sql)
        self.assertIn(f"('{codes['과천']}', '과천역', 'Gwacheon', 37.4330000, 126.9965000, "
                      "(SELECT id FROM regions WHERE code = '41290'), 1)", sql)
        self.assertIn('-- 시군구가 붙은 역 3 · 못 정한 역 0: 없음', sql)
        self.assertIn(f"'{codes['왕십리']}' AS station_code, 'SEL-2' AS line_code, 1 AS sort_order", sql)
        self.assertIn(f"UNION ALL SELECT '{codes['왕십리']}', 'SEL-5', 2", sql)
        # 역-노선 지우기 · 넣기는 한 트랜잭션 — 사이에 노선 없는 역이 보이지 않는다.
        self.assertRegex(sql, r'START TRANSACTION;\n\nDELETE link\n(?:.*\n)+?INSERT INTO subway_station_lines (?:.*\n)+?COMMIT;\n$')
        for col in ('name_ko', 'name_en', 'color', 'is_active', 'sort_order', 'lat', 'lng', 'region_id'):
            self.assertRegex(sql, r'(?:UPDATE|\n) +%s += VALUES\(%s\)[,;]' % (col, col))
        m = re.search(JSON_HEADER, sql)
        self.assertEqual(m.groups(), ('3', '3', '2', '1', '4'))

    def test_seoul_station_without_a_known_gu_stops(self):
        with self.assertRaises(SystemExit):
            self.build([src(2, L2, '0201', '시청', 'City Hall', 37.5657, 126.9772, addr='서울특별시 세종대로 110')])

    def test_blank_english_name_stops(self):
        with self.assertRaises(SystemExit):
            self.build([src(2, L2, '0201', '시청', '', 37.5657, 126.9772)])

    def test_same_input_gives_same_bytes(self):
        rows = [src(2, L2, '0201', '시청', 'City Hall', 37.5657, 126.9772)]
        self.assertEqual(self.build(rows)[1], self.build(rows)[1])


class SchemaGate(unittest.TestCase):

    def test_missing_table_or_column_sends_output_to_pending(self):
        with tempfile.TemporaryDirectory() as d:
            sql = ''.join(f'CREATE TABLE {t}\n(\n' + ',\n'.join(f'    {c} INT' for c in cols if c != 'color') + '\n) ENGINE = InnoDB;\n'
                          for t, cols in g.REQUIRED_COLUMNS.items() if t != 'subway_station_lines')
            with open(os.path.join(d, 'V1__x.sql'), 'w', encoding='utf-8') as f:
                f.write(sql)
            old = g.MIGRATIONS
            g.MIGRATIONS = d
            try:
                self.assertEqual(g.schema_missing(), ['subway_lines.color', 'subway_station_lines'])
            finally:
                g.MIGRATIONS = old

    # 배포 순서 — V 마이그레이션이 세 테이블을 만들었으면 생성 파일은 dml/ 맨 위(CD 대상)에 있어야 하고 pending 에는 없어야 한다.
    def test_repository_ships_05_at_top_level(self):
        self.assertEqual(g.schema_missing(), [])
        self.assertTrue(os.path.exists(g.OUT))
        self.assertFalse(os.path.exists(g.PENDING_OUT))


# 커밋된 생성 파일 · 장부가 스스로 맞는지 — 손으로 고치거나 한쪽만 다시 만든 것을 잡는다(원천 없이 본다).
class CommittedFiles(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.sql = g.read_text(g.OUT)
        cls.lines = re.findall(r"^    \('([A-Z0-9-]+)', '[^']+', '[^']+', '(#[0-9A-Fa-f]{6})', 1, (\d+)\)", cls.sql, re.M)
        cls.stations = re.findall(r"^    \('(ST\d{4})', ", cls.sql, re.M)
        cls.pairs = re.findall(r"SELECT '(ST\d{4})'(?: AS station_code)?, '([A-Z0-9-]+)'(?: AS line_code)?, (\d+)", cls.sql)
        cls.ledger = g.read_ledger(g.OUT)

    def test_header_counts_match_the_rows(self):
        lines, stations, seoul, transfers, links = map(int, re.search(JSON_HEADER, self.sql).groups())
        per_station = collections.Counter(code for code, _, _ in self.pairs)
        self.assertEqual(len(self.lines), lines)
        self.assertEqual(len(self.stations), stations)
        self.assertEqual(len(set(self.stations)), stations)
        self.assertEqual(len(self.pairs), links)
        self.assertEqual(sum(1 for n in per_station.values() if n > 1), transfers)
        placed, unplaced = map(int, re.search(r'(?m)^-- 시군구가 붙은 역 (\d+) · 못 정한 역 (\d+)', self.sql).groups())
        self.assertEqual(self.sql.count('(SELECT id FROM regions WHERE code ='), placed)
        self.assertEqual(placed + unplaced, stations)
        self.assertGreaterEqual(placed, seoul)

    def test_retire_and_link_lists_cover_exactly_the_inserted_codes(self):
        line_codes = [c for c, _, _ in self.lines]
        lists = re.findall(r'code (?:NOT )?IN \(([^)]*)\);', self.sql)
        self.assertEqual(re.findall(r"'([^']+)'", lists[0]), line_codes)
        self.assertEqual(re.findall(r"'([^']+)'", lists[1]), self.stations)
        self.assertEqual(re.findall(r"'([^']+)'", lists[2]), self.stations)
        self.assertEqual({s for s, _, _ in self.pairs}, set(self.stations))
        self.assertTrue({l for _, l, _ in self.pairs} <= set(line_codes))

    def test_lines_follow_the_table_and_colours_are_upper_case(self):
        for code, color, order in self.lines:
            self.assertEqual(int(order), g.LINE_ORDER.index(code) + 1)
            self.assertRegex(color, r'^#[0-9A-F]{6}$')
            self.assertEqual(color, next(c for k, _, _, c in g.LINES if k == code))

    def test_each_station_lists_its_lines_once_in_line_order(self):
        per_station = collections.defaultdict(list)
        for code, line, order in self.pairs:
            per_station[code].append((int(order), line))
        for code, items in per_station.items():
            self.assertEqual([o for o, _ in items], list(range(1, len(items) + 1)), code)
            self.assertEqual([l for _, l in items], sorted((l for _, l in items), key=g.LINE_ORDER.index), code)

    def test_ledger_matches_the_sql(self):
        codes = [r['code'] for r in self.ledger]
        self.assertEqual(len(codes), len(set(codes)))
        self.assertEqual(codes, sorted(codes))
        self.assertEqual({r['code'] for r in self.ledger if r['active'] == '1'}, set(self.stations))
        self.assertEqual(g.read_text(g.LEDGER).splitlines()[0], 'code\tname_key\tlat\tlng\tactive')


if __name__ == '__main__':
    unittest.main()
