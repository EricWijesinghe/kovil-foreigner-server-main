package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.exception.ListingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/*
 * 반경 조건의 입력 격자와 계산.
 * ★ 일부만 온 반경을 조용히 무시하면 "○○ 주변" 제목 아래 전체 매물이 나온다 — 400 이어야 한다.
 * ★ 원을 감싼 사각형이 원보다 작으면 가장자리 매물이 빠진다 — 사각형이 원을 덮는지 여기서 박는다.
 */
class ListingRadiusTest {

    private static final BigDecimal LAT = new BigDecimal("37.5600000");
    private static final BigDecimal LNG = new BigDecimal("126.9400000");

    private static String codeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("셋 다 비면 반경 없음(null)")
    void all_empty_is_no_radius() {
        assertThat(ListingRadius.ofNullable(null, null, null)).isNull();
    }

    @ParameterizedTest(name = "lat={0} lng={1} radius={2}")
    @CsvSource(value = {
            "37.56, , ", ", 126.94, ", ", , 500", "37.56, 126.94, ", "37.56, , 500", ", 126.94, 500",
            "37.56, 126.94, 99", "37.56, 126.94, 3001", "37.56, 126.94, 0", "37.56, 126.94, -500",
            "90.0000001, 126.94, 500", "37.56, 180.1, 500", "1e-999999999, 126.94, 500", "37.56, 1E+999999999, 500",
            "37.5600000000000001, 126.94, 500"})
    @DisplayName("일부만 · 범위 밖 · 지수 표기 · 소수 16자리는 400 LISTING_RADIUS_INVALID")
    void invalid_is_rejected(String lat, String lng, Integer radius) {
        assertThatThrownBy(() -> ListingRadius.ofNullable(
                lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng), radius))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_RADIUS_INVALID"));
    }

    @Test
    @DisplayName("경계값 100 · 3,000m 와 세계 끝 좌표는 받는다")
    void limits_are_accepted() {
        assertThat(ListingRadius.ofNullable(LAT, LNG, 100)).isNotNull();
        assertThat(ListingRadius.ofNullable(LAT, LNG, 3_000)).isNotNull();
        assertThat(ListingRadius.ofNullable(new BigDecimal("-90"), new BigDecimal("-180"), 500)).isNotNull();
    }

    @Test
    @DisplayName("중심은 칼럼과 같은 소수 7자리로 맞춘다")
    void centre_is_rounded_to_seven_places() {
        ListingRadius r = ListingRadius.ofNullable(new BigDecimal("37.56000004"), new BigDecimal("126.94"), 500);

        assertThat(r.lat()).isEqualByComparingTo("37.5600000");
        assertThat(r.lat().scale()).isEqualTo(7);
        assertThat(r.lng().scale()).isEqualTo(7);
    }

    // 위도 37.56 에서 경도 1도 ≈ 88.1km · 위도 1도 ≈ 111.2km. 사각형 반폭이 반지름(도 환산) 이상이어야 원을 덮는다.
    @Test
    @DisplayName("원을 감싼 사각형은 원을 덮는다 — 남북 · 동서 반폭이 반지름 이상")
    void bounding_box_covers_the_circle() {
        ListingRadius r = ListingRadius.ofNullable(LAT, LNG, 1_000);
        double northMeters = r.maxLat().subtract(LAT).doubleValue() * 111_195.08;
        double eastMeters = r.maxLng().subtract(LNG).doubleValue() * 111_195.08 * Math.cos(Math.toRadians(37.56));

        assertThat(northMeters).isGreaterThanOrEqualTo(1_000).isLessThan(1_001);
        assertThat(eastMeters).isGreaterThanOrEqualTo(1_000).isLessThan(1_001);
        assertThat(LAT.subtract(r.minLat())).isEqualByComparingTo(r.maxLat().subtract(LAT));
        assertThat(LNG.subtract(r.minLng())).isEqualByComparingTo(r.maxLng().subtract(LNG));
    }

    @Test
    @DisplayName("쿼리 계수 — cos²(위도) 와 (반지름/위도 1도)²")
    void query_factors() {
        ListingRadius r = ListingRadius.ofNullable(LAT, LNG, 500);
        double cos = Math.cos(Math.toRadians(37.56));

        assertThat(r.lngScaleSq().doubleValue()).isCloseTo(cos * cos, within(1e-11));
        assertThat(r.radiusDegSq().doubleValue()).isCloseTo(Math.pow(500 / 111_195.08, 2), within(1e-16));
        assertThat(r.radiusDegSq().scale()).isEqualTo(18);
    }

    // 극에서는 cos 이 0 이라 경도 폭이 무한대가 된다 — 180도로 잘라 쿼리가 터지지 않게.
    @Test
    @DisplayName("극점에서도 경도 폭은 180도를 넘지 않는다")
    void pole_is_clamped() {
        ListingRadius r = ListingRadius.ofNullable(new BigDecimal("90"), LNG, 3_000);

        assertThat(r.maxLng().subtract(LNG)).isEqualByComparingTo("180");
    }
}
