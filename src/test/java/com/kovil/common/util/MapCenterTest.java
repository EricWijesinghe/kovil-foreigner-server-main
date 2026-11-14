package com.kovil.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// 가운데는 네 좌표의 평균, 경도 가중치는 cos²(가운데 위도) — 가까운 순 정렬이 이 둘에 기댄다.
class MapCenterTest {

    @Test
    @DisplayName("가운데는 네 좌표의 평균이고, 경도 가중치는 가운데 위도의 cos² 이다")
    void center_and_weight() {
        MapCenter seoul = MapCenter.of(new BigDecimal("37.50"), new BigDecimal("126.90"),
                new BigDecimal("37.60"), new BigDecimal("127.00"));

        assertThat(seoul.lat()).isEqualByComparingTo("37.55");
        assertThat(seoul.lng()).isEqualByComparingTo("126.95");
        double cos = Math.cos(Math.toRadians(37.55));
        assertThat(seoul.lngWeight().doubleValue()).isCloseTo(cos * cos, within(1e-9));
    }

    @Test
    @DisplayName("적도에서는 1, 극에서는 0 에 가깝다")
    void weight_follows_latitude() {
        assertThat(MapCenter.of(new BigDecimal("-1"), new BigDecimal("0"), new BigDecimal("1"), new BigDecimal("1"))
                .lngWeight().doubleValue()).isCloseTo(1.0, within(1e-9));
        assertThat(MapCenter.of(new BigDecimal("89"), new BigDecimal("0"), new BigDecimal("90"), new BigDecimal("1"))
                .lngWeight().doubleValue()).isLessThan(0.001);
    }
}
