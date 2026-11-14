package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.exception.ListingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 그린 구역의 입력 격자와 점 판정.
 * ★ 빈 값(polygon=)이 '그리기 없음'으로 읽히면 전체 목록이 "그린 구역 매물 N" 으로 찍힌다 — 400 이어야 한다.
 * ★ 반직선이 꼭짓점을 정확히 지나는 점은 고전적인 이중 계산 함정이다 — 높이가 꼭짓점과 같은 바깥 점을 따로 박는다.
 * ★ 경계 위의 점은 안이다(선언) — 변 · 꼭짓점 위 매물이 구역에서 빠지지 않는다.
 */
class ListingPolygonTest {

    // 경도 126.90 ~ 126.94 · 위도 37.55 ~ 37.59 사각형(꼭짓점 순서: 남서 → 남동 → 북동 → 북서).
    private static final String SQUARE = "37.55:126.90,37.55:126.94,37.59:126.94,37.59:126.90";
    // ㄷ자(오목) — 동쪽이 비어 있다. 비어 있는 홈: 위도 37.56~37.58 · 경도 126.92~126.94.
    private static final String NOTCH = "37.55:126.90,37.55:126.94,37.56:126.94,37.56:126.92,"
            + "37.58:126.92,37.58:126.94,37.59:126.94,37.59:126.90";

    private static String codeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    private static boolean in(ListingPolygon p, String lat, String lng) {
        return p.contains(new BigDecimal(lat), new BigDecimal(lng));
    }

    @Nested
    @DisplayName("입력")
    class Parse {

        @Test
        @DisplayName("파라미터가 없으면 null(그리기 안 씀)")
        void absent_is_null() {
            assertThat(ListingPolygon.parseOrNull(null)).isNull();
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "37.55:126.90", "37.55:126.90,37.56:126.91",
                "37.55:126.90,37.56:126.91,", "37.55-126.90,37.56:126.91,37.57:126.90",
                "37.55:126.90:1,37.56:126.91,37.57:126.90", "1e1:126.90,37.56:126.91,37.57:126.90",
                "37.55:126.90,37.56:1E+2,37.57:126.90", "37.5500000000000001:126.90,37.56:126.91,37.57:126.90",
                "90.1:126.90,37.56:126.91,37.57:126.90", "37.55:180.5,37.56:126.91,37.57:126.90",
                "+37.55:126.90,37.56:126.91,37.57:126.90", "37.:126.90,37.56:126.91,37.57:126.90",
                ".5:126.90,37.56:126.91,37.57:126.90", "abc",
                "-90.1:126.90,37.56:126.91,37.57:126.90", "37.55:-180.5,37.56:126.91,37.57:126.90",
                "37.55:126.90,37.56:126.91,37.57:126.90,", "37.55:126.90:,37.56:126.91,37.57:126.90"})
        @DisplayName("빈 값 · 꼭짓점 3개 미만 · 형식 · 끝 쉼표 · 끝 콜론 · 지수 표기 · 소수 16자리 · 범위 밖(음수 쪽 포함)은 400 LISTING_POLYGON_INVALID")
        void malformed_is_rejected(String raw) {
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(raw))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }

        @Test
        @DisplayName("앞뒤 공백은 받는다")
        void spaces_are_trimmed() {
            assertThat(ListingPolygon.parseOrNull(" 37.55 : 126.90 , 37.56 : 126.94 , 37.59 : 126.92 ").vertices())
                    .hasSize(3);
        }

        @Test
        @DisplayName("처음 점을 끝에 다시 써도 받고, 같은 점이 이어 오면 하나로 친다")
        void closing_and_consecutive_duplicates_are_merged() {
            ListingPolygon closed = ListingPolygon.parseOrNull(SQUARE + ",37.55:126.90");
            ListingPolygon stutter = ListingPolygon.parseOrNull(
                    "37.55:126.90,37.55:126.90,37.55:126.94,37.590:126.940,37.59:126.94,37.59:126.90");

            assertThat(closed.vertices()).hasSize(4);
            assertThat(stutter.vertices()).hasSize(4);
            // 자릿수만 다른 같은 점(37.550 = 37.55)도 닫는 점으로 합친다 — 값으로 비교해야 한다.
            assertThat(ListingPolygon.parseOrNull(SQUARE + ",37.550:126.900").vertices()).hasSize(4);
        }

        // 꼭짓점 수는 합친 뒤에 센다 — 30개 + 닫는 점은 받고, 서로 다른 31개는 거절한다.
        @Test
        @DisplayName("꼭짓점은 합친 뒤 30개까지 — 30 + 닫는 점은 받고 31은 400")
        void vertex_limit_after_merge() {
            assertThat(ListingPolygon.parseOrNull(circle(30) + "," + circle(30).split(",")[0]).vertices()).hasSize(30);
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(circle(31)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }

        @Test
        @DisplayName("2,000자를 넘거나 점이 60개를 넘으면 쪼개기 전에 400")
        void length_and_raw_count_are_bounded() {
            String tooMany = IntStream.range(0, 61).mapToObj(i -> "37.55:126.90").collect(Collectors.joining(","));
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(tooMany)).isInstanceOf(ListingException.class);
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(SQUARE + " ".repeat(2_000)))
                    .isInstanceOf(ListingException.class);
        }

        // 점 개수는 합치기 전에 센다 — 같은 점 연타로 합치면 사각형이어도 61개면 쪼개기 전에 거절한다.
        @Test
        @DisplayName("점 개수는 합치기 전에 센다 — 합치면 사각형이어도 61개는 400, 60개는 받는다")
        void raw_point_cap_is_counted_before_merge() {
            String[] corners = SQUARE.split(",");
            String sixty = IntStream.range(0, 60).mapToObj(i -> corners[i / 15]).collect(Collectors.joining(","));

            assertThat(ListingPolygon.parseOrNull(sixty).vertices()).hasSize(4);
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(sixty + "," + corners[3]))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }

        // 저장한 구역은 이 문자열을 같은 파서로 다시 읽는다 — BigDecimal.toString 은 0 근처 값을 1E-7 로 내 NUMBER 가 거절한다.
        @Test
        @DisplayName("toParam 은 다시 읽을 수 있는 모양 — 합친 꼭짓점, 0 근처 좌표도 지수 표기 없이")
        void to_param_round_trips() {
            String raw = "0.0000001:0,0.0000001:1,1:1";
            String param = ListingPolygon.parseOrNull(raw).toParam();

            assertThat(param).isEqualTo(raw);
            assertThat(ListingPolygon.parseOrNull(param).vertices()).hasSize(3);
            assertThat(ListingPolygon.parseOrNull(SQUARE + ",37.55:126.90").toParam()).isEqualTo(SQUARE);
        }

        @Test
        @DisplayName("정확히 2,000자는 받고 2,001자는 400")
        void length_boundary_is_exact() {
            assertThat(ListingPolygon.parseOrNull(SQUARE + " ".repeat(2_000 - SQUARE.length())).vertices()).hasSize(4);
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(SQUARE + " ".repeat(2_001 - SQUARE.length())))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }

        // 자바스크립트가 double 을 문자열로 바꾸면 소수 15자리까지 나온다.
        @Test
        @DisplayName("소수 15자리 · 정수 좌표 · 한 자리 정수부 · 위도 ±90 · 경도 ±180 은 받는다")
        void accepted_number_shapes() {
            assertThat(ListingPolygon.parseOrNull(
                    "37.550000000000001:126.90,37.55:126.940000000000001,37.590000000000001:126.92").vertices()).hasSize(3);
            assertThat(ListingPolygon.parseOrNull("37:127,37:128,38:127").vertices()).hasSize(3);
            assertThat(ListingPolygon.parseOrNull("1.29:103.85,1.29:103.86,1.30:103.85").vertices()).hasSize(3);
            assertThat(ListingPolygon.parseOrNull("90:0,89:1,89:-1").vertices()).hasSize(3);
            assertThat(ListingPolygon.parseOrNull("0:180,1:179,-1:179").vertices()).hasSize(3);
        }

        // 곧은 변 가운데 찍은 꼭짓점은 되접힘이 아니다 — 손그림을 단순화하면 흔히 남는다.
        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {
                "37.55:126.90,37.55:126.92,37.55:126.94,37.59:126.94,37.59:126.90",
                "37.55:126.92,37.55:126.94,37.59:126.94,37.59:126.90,37.55:126.90"})
        @DisplayName("곧은 변 가운데 찍은 꼭짓점은 받는다 — 중간 꼭짓점 · 처음 꼭짓점(닫는 쪽)")
        void straight_through_vertex_is_accepted(String raw) {
            assertThat(ListingPolygon.parseOrNull(raw).vertices()).hasSize(5);
        }

        /*
         * ★ 대칭 8자(꼭짓점 위도 · 경도가 두 값뿐)는 넓이가 0 이라 교차 검사 전에 걸린다 — 교차 검사를 통째로 지워도 모른다.
         *   손그림 8자는 두 고리 크기가 달라 넓이가 0 이 아니다. 닫는 변(끝 → 처음)이 꼬이는 경우도 따로 박는다.
         */
        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {
                "37.55:126.90,37.59:126.94,37.55:126.94,37.60:126.90",
                "37.59:126.94,37.55:126.94,37.60:126.90,37.55:126.90",
                "37.55:126.94,37.55:126.92,37.59:126.92,37.55:126.90"})
        @DisplayName("넓이가 0 이 아닌 짝짝이 8자 · 닫는 변이 가로지름 · 첫 꼭짓점에서 되접힘은 400")
        void crossing_with_area_is_rejected(String raw) {
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(raw))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {
                "37.55:126.90,37.55:126.94,37.59:126.94,37.55:126.92,37.59:126.90",
                "37.55:126.94,37.59:126.94,37.55:126.92,37.59:126.90,37.55:126.90"})
        @DisplayName("꼭짓점이 이웃 아닌 변 위에 닿으면(T 자 · 한 점에서 맞닿은 두 고리) 400 — 뒤 꼭짓점 · 앞 꼭짓점")
        void vertex_touching_other_edge_is_rejected(String raw) {
            assertThatThrownBy(() -> ListingPolygon.parseOrNull(raw))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }

        @Test
        @DisplayName("8자로 꼬인 구역 · 한 줄 위의 점들(넓이 0) · 되접힌 선은 400")
        void self_intersection_and_zero_area_are_rejected() {
            for (String raw : new String[]{
                    "37.55:126.90,37.59:126.94,37.55:126.94,37.59:126.90",
                    "37.55:126.90,37.56:126.91,37.57:126.92",
                    "37.55:126.90,37.55:126.94,37.55:126.92,37.59:126.92"}) {
                assertThatThrownBy(() -> ListingPolygon.parseOrNull(raw))
                        .as(raw)
                        .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
            }
        }

        @Test
        @DisplayName("외접 사각형 — 남 · 서 · 북 · 동")
        void bounding_box() {
            ListingPolygon p = ListingPolygon.parseOrNull(NOTCH);

            assertThat(p.minLat()).isEqualByComparingTo("37.55");
            assertThat(p.maxLat()).isEqualByComparingTo("37.59");
            assertThat(p.minLng()).isEqualByComparingTo("126.90");
            assertThat(p.maxLng()).isEqualByComparingTo("126.94");
        }

        // 원 위의 n 개 점(서로 다름 · 볼록) — 소수 7자리로 찍는다.
        private String circle(int n) {
            return IntStream.range(0, n).mapToObj(i -> {
                double t = 2 * Math.PI * i / n;
                return String.format(Locale.ROOT, "%.7f:%.7f", 37.57 + 0.01 * Math.sin(t), 126.92 + 0.01 * Math.cos(t));
            }).collect(Collectors.joining(","));
        }
    }

    @Nested
    @DisplayName("판정")
    class Contains {

        private final ListingPolygon square = ListingPolygon.parseOrNull(SQUARE);
        private final ListingPolygon notch = ListingPolygon.parseOrNull(NOTCH);

        @ParameterizedTest(name = "({0}, {1}) → {2}")
        @CsvSource({
                "37.57, 126.92, true",
                "37.60, 126.92, false", "37.57, 126.95, false", "37.54, 126.92, false", "37.57, 126.89, false",
                // 경계 위 — 꼭짓점 · 가로 변 · 세로 변
                "37.55, 126.90, true", "37.55, 126.92, true", "37.57, 126.94, true", "37.59, 126.91, true"})
        @DisplayName("사각형 — 안 · 밖 · 경계(안)")
        void square(String lat, String lng, boolean expected) {
            assertThat(in(square, lat, lng)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "({0}, {1}) → {2}")
        @CsvSource({
                "37.555, 126.93, true", "37.585, 126.93, true", "37.57, 126.91, true",
                // 홈 안 — 외접 사각형 안이지만 구역 밖
                "37.57, 126.93, false",
                // 홈의 경계 위 — 안
                "37.57, 126.92, true", "37.56, 126.93, true"})
        @DisplayName("ㄷ자(오목) — 홈 안은 밖, 홈 경계는 안")
        void concave(String lat, String lng, boolean expected) {
            assertThat(in(notch, lat, lng)).isEqualTo(expected);
        }

        // 반직선이 꼭짓점을 지나는 높이(위도 37.56 · 37.58 은 ㄷ자 꼭짓점 높이). 이중 계산이면 안 · 밖이 뒤집힌다.
        @ParameterizedTest(name = "({0}, {1}) → {2}")
        @CsvSource({"37.56, 126.89, false", "37.58, 126.89, false", "37.56, 126.95, false",
                "37.58, 126.91, true", "37.56, 126.91, true"})
        @DisplayName("꼭짓점 높이의 점 — 반직선이 꼭짓점을 지나도 한 번만 센다")
        void ray_through_vertex(String lat, String lng, boolean expected) {
            assertThat(in(notch, lat, lng)).isEqualTo(expected);
        }

        // 손가락은 시계 방향으로도 그린다 — 방향에 따라 넓이 부호만 바뀌고 받는 것 · 판정은 같아야 한다.
        @Test
        @DisplayName("시계 방향으로 그려도 받고, 판정은 같다")
        void clockwise_ring() {
            ListingPolygon cw = ListingPolygon.parseOrNull("37.55:126.90,37.59:126.90,37.59:126.94,37.55:126.94");

            assertThat(cw.vertices()).hasSize(4);
            assertThat(in(cw, "37.57", "126.92")).isTrue();
            assertThat(in(cw, "37.57", "126.95")).isFalse();
            assertThat(in(cw, "37.59", "126.91")).isTrue();
        }

        // 축에 나란한 변만 있으면 반직선 교차식의 기울기 항이 0 이라 부호가 틀려도 모른다.
        @Test
        @DisplayName("비스듬한 변 — 삼각형 · 마름모의 안 · 밖")
        void slanted_edges() {
            ListingPolygon triangle = ListingPolygon.parseOrNull("37.55:126.90,37.55:126.94,37.59:126.90");
            assertThat(in(triangle, "37.56", "126.91")).isTrue();
            assertThat(in(triangle, "37.58", "126.905")).isTrue();
            assertThat(in(triangle, "37.58", "126.93")).isFalse();

            ListingPolygon diamond = ListingPolygon.parseOrNull("37.55:126.92,37.57:126.94,37.59:126.92,37.57:126.90");
            assertThat(in(diamond, "37.57", "126.92")).isTrue();
            assertThat(in(diamond, "37.56", "126.925")).isTrue();
            assertThat(in(diamond, "37.56", "126.935")).isFalse();
            assertThat(in(diamond, "37.58", "126.905")).isFalse();
        }

        @Test
        @DisplayName("삼각형의 대각선 변 위 점은 안, 바로 바깥은 밖")
        void diagonal_edge() {
            ListingPolygon triangle = ListingPolygon.parseOrNull("37.55:126.90,37.55:126.94,37.59:126.90");

            assertThat(in(triangle, "37.57", "126.92")).isTrue();
            assertThat(in(triangle, "37.5700001", "126.9200001")).isFalse();
        }

        @Test
        @DisplayName("음수 좌표(남반구 · 서경)도 같은 규칙")
        void negative_coordinates() {
            ListingPolygon south = ListingPolygon.parseOrNull("-33.90:151.10,-33.90:151.30,-33.80:151.30,-33.80:151.10");

            assertThat(in(south, "-33.85", "151.20")).isTrue();
            assertThat(in(south, "-33.95", "151.20")).isFalse();
        }
    }
}
