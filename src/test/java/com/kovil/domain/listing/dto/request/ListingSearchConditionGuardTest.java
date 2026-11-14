package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.exception.ListingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 목록 · 지도 · 시세가 함께 쓰는 숫자 칸 검사.
 * ★ 지수 표기(1e-999999999)는 범위 검사를 통과하고 DB 바인딩에서 자릿수 10억짜리 문자열을 만든다 —
 *   API 테스트의 400 이 정말 이 검사에서 나는지(다른 이유가 아닌지) 여기서 따로 박는다.
 */
class ListingSearchConditionGuardTest {

    private static ListingSearchCondition withBox(String swLat, String swLng, String neLat, String neLng) {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, null,
                new BigDecimal(swLat), new BigDecimal(swLng), new BigDecimal(neLat), new BigDecimal(neLng));
    }

    private static ListingSearchCondition withArea(String areaMin) {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null,
                new BigDecimal(areaMin), null, false, false, null, null, null, null, null, null, null);
    }

    @ParameterizedTest(name = "neLat={0} → INVALID_REQUEST")
    @ValueSource(strings = {"1e-999999999", "1E+999999999", "90.0000001", "-90.1", "37.5600000000000001"})
    @DisplayName("위도는 ±90 · 소수 15자리까지")
    void rejects_bad_latitude(String value) {
        assertThatThrownBy(() -> withBox("37.55", "126.90", value, "126.94"))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @ParameterizedTest(name = "swLng={0} → INVALID_REQUEST")
    @ValueSource(strings = {"180.0000001", "-181", "1e-16"})
    @DisplayName("경도는 ±180 · 소수 15자리까지")
    void rejects_bad_longitude(String value) {
        assertThatThrownBy(() -> withBox("37.55", value, "37.56", "126.94"))
                .isInstanceOf(ListingException.class);
    }

    @ParameterizedTest(name = "areaMin={0} → INVALID_REQUEST")
    @ValueSource(strings = {"1e-999999999", "100000000.01", "-100000001"})
    @DisplayName("면적은 1억 이하 · 소수 15자리까지")
    void rejects_bad_area(String value) {
        assertThatThrownBy(() -> withArea(value)).isInstanceOf(ListingException.class);
    }

    @Test
    @DisplayName("경계값 · 소수 15자리 · 빈 칸은 통과한다")
    void accepts_limits_and_nulls() {
        assertThatCode(() -> withBox("-90", "-180", "90", "180")).doesNotThrowAnyException();
        assertThatCode(() -> withBox("37.550000000000001", "126.900000000000001", "37.56", "126.94"))
                .doesNotThrowAnyException();
        assertThatCode(() -> withArea("100000000")).doesNotThrowAnyException();
        assertThatCode(() -> new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null, null)).doesNotThrowAnyException();
    }

    // 빈 사무소 uuid 는 "필터 없음" — 빈 문자열 그대로 쿼리에 가면 어떤 사무소와도 안 맞아 빈 페이지가 된다.
    @Test
    @DisplayName("사무소 uuid — 비었거나 공백뿐이면 null(필터 없음), 앞뒤 공백은 뗀다")
    void office_uuid_blank_is_no_filter() {
        assertThat(withOffice(null).officeUuid()).isNull();
        assertThat(withOffice("").officeUuid()).isNull();
        assertThat(withOffice(" \t ").officeUuid()).isNull();
        assertThat(withOffice(" o-1 ").officeUuid()).isEqualTo("o-1");
        assertThat(withOffice("o-1").officeUuid()).isEqualTo("o-1");
    }

    private static ListingSearchCondition withOffice(String officeUuid) {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, officeUuid, null, null, null, null);
    }
}
