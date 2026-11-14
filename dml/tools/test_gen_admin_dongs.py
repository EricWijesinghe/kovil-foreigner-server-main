#!/usr/bin/env python3
# dml/tools/gen_admin_dongs.py 의 로마자 표기 · 경계 단순화 · 내부점 · SQL 규칙을 고정한다 — 원천 GeoJSON 없이 돈다.
# 실행(저장소 루트):  python3 -I -B -m unittest discover -s dml/tools -p 'test_*.py'
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('gen_admin_dongs', os.path.join(HERE, 'gen_admin_dongs.py'))
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)


class Romanization(unittest.TestCase):
    # 공식 표기(역명 · 구명 · 표기법 용례)와 같은지 — 각 줄이 규칙 하나씩을 건드린다.
    CASES = {
        '사직': 'Sajik',          # 기본 자모
        '종로': 'Jongno',         # ㅇ + ㄹ → n
        '정릉': 'Jeongneung',     # ㅇ + ㄹ → n
        '신림': 'Sillim',         # ㄴ + ㄹ → ll
        '신라': 'Silla',
        '선릉': 'Seolleung',
        '별내': 'Byeollae',       # ㄹ + ㄴ → ll
        '문래': 'Mullae',
        '왕십리': 'Wangsimni',    # ㅂ + ㄹ → m n
        '청량리': 'Cheongnyangni',
        '백마': 'Baengma',        # ㄱ + ㅁ → ng m
        '독립문': 'Dongnimmun',   # ㄱ+ㄹ → ng n, ㅂ+ㅁ → m m
        '합정': 'Hapjeong',       # 받침 ㅂ → p
        '압구정': 'Apgujeong',
        '신촌': 'Sinchon',
        '묵호': 'Mukho',          # 체언 ㄱ+ㅎ 거센소리 표기 안 함
        '집현': 'Jiphyeon',
        '해운대': 'Haeundae',
        '역삼': 'Yeoksam',
        '을지로': 'Euljiro',
        '대림': 'Daerim',         # 모음 뒤 ㄹ → r
        '해돋이': 'Haedoji',      # 구개음화
        '같이': 'Gachi',
        '좋다': 'Jota',           # ㅎ + ㄷ 거센소리
        '울산': 'Ulsan',
        '의정부': 'Uijeongbu',
    }

    def test_words(self):
        for ko, en in self.CASES.items():
            with self.subTest(ko=ko):
                self.assertEqual(g.romanize_word(ko), en)

    def test_dong_names(self):
        self.assertEqual(g.romanize_dong('사직동'), 'Sajik-dong')
        self.assertEqual(g.romanize_dong('역삼1동'), 'Yeoksam 1-dong')
        self.assertEqual(g.romanize_dong('종로1·2·3·4가동'), 'Jongno 1.2.3.4-ga-dong')
        self.assertEqual(g.romanize_dong('성수1가1동'), 'Seongsu 1-ga 1-dong')
        # 합쳐진 동: 조각마다 따로 표기(안림 Allim — 조각 안의 ㄴ+ㄹ 은 반영), 가운뎃점 유지
        self.assertEqual(g.romanize_dong('교현·안림동'), 'Gyohyeon·Allim-dong')
        self.assertEqual(g.romanize_dong('성화·개신·죽림동'), 'Seonghwa·Gaesin·Jungnim-dong')
        self.assertEqual(g.romanize_dong('봉명2·송정동'), 'Bongmyeong 2·Songjeong-dong')
        self.assertEqual(g.romanize_dong('탑·대성동'), 'Tap·Daeseong-dong')
        self.assertEqual(g.romanize_dong('상계3·4동'), 'Sanggye 3.4-dong')
        self.assertEqual(g.romanize_dong('신창읍'), 'Sinchang-eup')
        self.assertEqual(g.romanize_dong('남면'), 'Nam-myeon')
        # 붙임표 앞뒤는 소리 변화를 반영하지 않는다: 신림동 은 Sillim-dong (ㅁ+ㄷ 그대로)
        self.assertEqual(g.romanize_dong('신림동'), 'Sillim-dong')

    def test_unexpected_names_stop_instead_of_guessing(self):
        with self.assertRaises(ValueError):
            g.romanize_dong('Seoul')
        with self.assertRaises(ValueError):
            g.romanize_dong('사직')


class Geometry(unittest.TestCase):
    SQUARE = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0), (0.0, 0.0)]

    def test_simplify_removes_collinear_noise_and_keeps_closed(self):
        noisy = [(0.0, 0.0), (0.5, 0.000001), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0), (0.0, 0.0)]
        out = g.simplify_ring(noisy, tol=0.001)
        self.assertEqual(out[0], out[-1])
        self.assertNotIn((0.5, 0.000001), out)
        self.assertEqual(len(out), 5)

    def test_simplify_never_collapses_a_small_ring(self):
        tiny = [(0.0, 0.0), (0.00001, 0.0), (0.0, 0.00001), (0.0, 0.0)]
        self.assertEqual(g.simplify_ring(tiny, tol=1.0), tiny)

    def test_point_in_polygon_respects_holes(self):
        hole = [(0.4, 0.4), (0.6, 0.4), (0.6, 0.6), (0.4, 0.6), (0.4, 0.4)]
        self.assertTrue(g.point_in_polygon((0.2, 0.2), [self.SQUARE, hole]))
        self.assertFalse(g.point_in_polygon((0.5, 0.5), [self.SQUARE, hole]))
        self.assertFalse(g.point_in_polygon((1.5, 0.5), [self.SQUARE]))

    def test_interior_point_is_inside_even_for_a_concave_shape(self):
        # C-shape: the centroid falls in the empty bite on the right.
        c_shape = [(0, 0), (3, 0), (3, 1), (1, 1), (1, 2), (3, 2), (3, 3), (0, 3), (0, 0)]
        c_shape = [(float(x), float(y)) for x, y in c_shape]
        pt = g.interior_point([c_shape])
        self.assertTrue(g.point_in_polygon(pt, [c_shape]))


class Sql(unittest.TestCase):
    def feature(self, code='1111053000', name='서울특별시 종로구 사직동'):
        return {
            'properties': {'adm_nm': name, 'adm_cd2': code, 'sgg': '11110', 'adm_cd': '11010530'},
            'geometry': {'type': 'Polygon', 'coordinates': [[[126.96, 37.57], [126.98, 37.57], [126.98, 37.58],
                                                             [126.96, 37.58], [126.96, 37.57]]]},
        }

    def test_render_is_idempotent_upsert_with_attribution_and_retire(self):
        row = g.prepare(self.feature())
        self.assertEqual(row['name_en'], 'Sajik-dong')
        sql = g.render([row], 'ver20261001', '0' * 64, 'HangJeongDong_ver20261001.geojson')
        self.assertIn('SGIS', sql)
        self.assertIn('CC BY 4.0', sql)
        self.assertIn('ON DUPLICATE KEY UPDATE', sql)
        self.assertIn("ST_GeomFromGeoJSON('{\"type\":\"MultiPolygon\"", sql)
        self.assertIn("UPDATE admin_dongs SET is_active = 0 WHERE source_version <> 'ver20261001';", sql)
        self.assertIn("FROM regions r WHERE r.code = '11110'", sql)
        # One transaction: a failure mid-file rolls everything back (no half-updated boundaries).
        self.assertIn('START TRANSACTION;', sql)
        self.assertTrue(sql.rstrip().endswith('COMMIT;'))
        self.assertLess(sql.index('START TRANSACTION;'), sql.index('INSERT INTO admin_dongs'))

    def test_quotes_are_escaped(self):
        self.assertEqual(g.q("O'Brien"), "'O''Brien'")
        self.assertEqual(g.q(None), 'NULL')

    def test_center_is_inside_and_bbox_covers_it(self):
        row = g.prepare(self.feature())
        min_lat, min_lng, max_lat, max_lng = row['bbox']
        self.assertTrue(min_lat <= row['lat'] <= max_lat and min_lng <= row['lng'] <= max_lng)


if __name__ == '__main__':
    unittest.main()
