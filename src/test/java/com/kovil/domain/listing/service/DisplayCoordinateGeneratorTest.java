package com.kovil.domain.listing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// 흐리기가 **실제로 흐리는지**, 그리고 **너무 흐리지 않는지**를 고정한다.
// 0이면 보호가 없고, 과하면 지도에서 엉뚱한 골목에 찍혀 기능이 죽는다.
class DisplayCoordinateGeneratorTest {

    private static final BigDecimal LAT = new BigDecimal("37.4862378");
    private static final BigDecimal LNG = new BigDecimal("126.9331220");

    private final DisplayCoordinateGenerator generator = new DisplayCoordinateGenerator();

    @Test
    @DisplayName("실좌표와 다른 점이 나온다 — 같으면 흐린 의미가 없다")
    void always_moves_the_point() {
        for (int i = 0; i < 50; i++) {
            BigDecimal[] out = generator.obfuscate(LAT, LNG);
            assertThat(out[0].compareTo(LAT) != 0 || out[1].compareTo(LNG) != 0).isTrue();
        }
    }

    /*
     * ★ 상한이 없으면 지도에서 **다른 동네에 찍힌다.** 이용자는 그 매물을 보러 가서
     *   엉뚱한 골목에 선다 — 흐리기가 기능을 죽이는 지점이다.
     */
    @Test
    @DisplayName("이동 거리가 약 10m 안쪽이다")
    void stays_within_about_ten_meters() {
        double maxMeters = 0;
        for (int i = 0; i < 200; i++) {
            BigDecimal[] out = generator.obfuscate(LAT, LNG);
            maxMeters = Math.max(maxMeters, metersBetween(out[0], out[1]));
        }
        // 반올림(소수 7자리 ≈ 1cm)과 경도 보정 오차를 감안해 여유를 둔다.
        assertThat(maxMeters).isBetween(1d, 12d);
    }

    // 같은 입력이라도 매번 달라야 한다 — 고정값이면 역산으로 실좌표가 복원된다.
    @Test
    @DisplayName("호출마다 다른 점이 나온다")
    void is_not_deterministic() {
        BigDecimal[] a = generator.obfuscate(LAT, LNG);
        BigDecimal[] b = generator.obfuscate(LAT, LNG);
        assertThat(a[0].compareTo(b[0]) != 0 || a[1].compareTo(b[1]) != 0).isTrue();
    }

    private double metersBetween(BigDecimal lat, BigDecimal lng) {
        double dLat = (lat.doubleValue() - LAT.doubleValue()) * 111_320d;
        double dLng = (lng.doubleValue() - LNG.doubleValue()) * 111_320d
                * Math.cos(Math.toRadians(LAT.doubleValue()));
        return Math.hypot(dLat, dLng);
    }
}
