package com.kovil.domain.transit.service;

import com.kovil.common.exception.CustomException;
import com.kovil.domain.transit.dto.response.SubwayStationResponse;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.repository.SubwayStationLineRepository;
import com.kovil.domain.transit.repository.SubwayStationLineRow;
import com.kovil.domain.transit.repository.SubwayStationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 지하철역 레이어의 조립부.
 * ★ 노선은 역들 것을 한 번에 읽고 역마다 나눈다 — 저장소가 준 순서(마커 색 점 순서)를 그대로 지킨다.
 *   역 id 를 서로 엇갈리게 둬, 역마다 나눌 때 순서가 섞이면 보이게 한다.
 * ★ 영역 숫자는 자릿수부터 본다 — 지수 표기(1e-999999999)는 범위 비교에서 서버를 멈추게 한다.
 */
class SubwayStationQueryServiceTest {

    private static final BigDecimal SW_LAT = new BigDecimal("37.50");
    private static final BigDecimal SW_LNG = new BigDecimal("126.90");
    private static final BigDecimal NE_LAT = new BigDecimal("37.60");
    private static final BigDecimal NE_LNG = new BigDecimal("127.00");
    // 자릿수 16 — 상한(15) 바로 위
    private static final BigDecimal SCALE_16 = new BigDecimal("37.5000000000000001");

    private SubwayStationRepository stationRepository;
    private SubwayStationLineRepository stationLineRepository;
    private SubwayStationQueryService service;

    @BeforeEach
    void setUp() {
        stationRepository = mock(SubwayStationRepository.class);
        stationLineRepository = mock(SubwayStationLineRepository.class);
        service = new SubwayStationQueryService(stationRepository, stationLineRepository);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static SubwayStation station(Long id, String code, String ko, String en) {
        return SubwayStation.builder().id(id).code(code).nameKo(ko).nameEn(en)
                .lat(new BigDecimal("37.5552000")).lng(new BigDecimal("126.9368000")).build();
    }

    private static SubwayStationLineRow row(Long stationId, String code, String ko, String en, String color) {
        return new SubwayStationLineRow(stationId, code, ko, en, color);
    }

    @Test
    @DisplayName("역마다 노선을 저장소 순서대로 붙이고, 역 이름 · 노선 이름은 한국어면 국문 · 그 밖이면 영문")
    void stations_carry_lines_in_order_and_language() {
        given(stationRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of(
                station(20L, "ST0065", "신촌역", "Sinchon"), station(10L, "ST0070", "왕십리역", "Wangsimni")));
        given(stationLineRepository.findLinesOf(List.of(20L, 10L))).willReturn(List.of(
                row(10L, "SEL-2", "2호선", "Line 2", "#00A84D"), row(10L, "SEL-5", "5호선", "Line 5", "#996CAC"),
                row(10L, "SUIN-BUNDANG", "수인분당선", "Suin-Bundang Line", "#FABE00"),
                row(20L, "SEL-2", "2호선", "Line 2", "#00A84D")));

        LocaleContextHolder.setLocale(Locale.KOREAN);
        List<SubwayStationResponse> ko = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<SubwayStationResponse> en = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        assertThat(ko).extracting(SubwayStationResponse::code).containsExactly("ST0065", "ST0070");
        assertThat(ko.get(0).name()).isEqualTo("신촌역");
        assertThat(ko.get(0).lines()).extracting(SubwayStationResponse.Line::name).containsExactly("2호선");
        assertThat(ko.get(1).name()).isEqualTo("왕십리역");
        assertThat(ko.get(1).lines()).extracting(SubwayStationResponse.Line::code)
                .containsExactly("SEL-2", "SEL-5", "SUIN-BUNDANG");
        assertThat(ko.get(1).lines()).extracting(SubwayStationResponse.Line::name)
                .containsExactly("2호선", "5호선", "수인분당선");
        assertThat(ko.get(1).lines()).extracting(SubwayStationResponse.Line::color)
                .containsExactly("#00A84D", "#996CAC", "#FABE00");
        assertThat(en).extracting(SubwayStationResponse::name).containsExactly("Sinchon", "Wangsimni");
        assertThat(en.get(1).lines()).extracting(SubwayStationResponse.Line::name)
                .containsExactly("Line 2", "Line 5", "Suin-Bundang Line");
        assertThat(en.get(1).lines()).extracting(SubwayStationResponse.Line::code)
                .containsExactly("SEL-2", "SEL-5", "SUIN-BUNDANG");
        assertThat(en.get(0).lat()).isEqualByComparingTo("37.5552");
        assertThat(en.get(0).lng()).isEqualByComparingTo("126.9368");
    }

    // 앱은 기기 언어를 그대로 보낸다 — ko-KR 도 한국어다(언어 부분만 본다). 그 밖 언어(vi)는 영문.
    @Test
    @DisplayName("ko-KR 도 국문, vi 는 영문 — 언어 부분만 본다")
    void language_part_only() {
        given(stationRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(station(1L, "ST0001", "가산디지털단지역", "Gasan Digital Complex")));
        given(stationLineRepository.findLinesOf(any()))
                .willReturn(List.of(row(1L, "SEL-1", "1호선", "Line 1", "#0052A4")));

        LocaleContextHolder.setLocale(Locale.KOREA);
        SubwayStationResponse koKr = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG).get(0);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        SubwayStationResponse vi = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG).get(0);

        assertThat(koKr.name()).isEqualTo("가산디지털단지역");
        assertThat(koKr.lines().get(0).name()).isEqualTo("1호선");
        assertThat(vi.name()).isEqualTo("Gasan Digital Complex");
        assertThat(vi.lines().get(0).name()).isEqualTo("Line 1");
    }

    // API 테스트는 바깥(테스트) 트랜잭션에 얹히고 저장소 쿼리에는 기본 트랜잭션이 없다 — 선언이 빠져도 아무 테스트도 깨지지 않아 선언을 못 박는다.
    @Test
    @DisplayName("조회는 읽기 전용 트랜잭션이다 — 역과 노선을 한 스냅숏에서 읽는다")
    void runs_in_read_only_transaction() {
        Transactional tx = SubwayStationQueryService.class
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
    }

    @Test
    @DisplayName("노선이 하나도 안 남은 역은 빈 lines(null 아님)")
    void station_without_lines_has_empty_list() {
        given(stationRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(station(30L, "ST0100", "역", "Station")));
        given(stationLineRepository.findLinesOf(any())).willReturn(List.of());

        assertThat(service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG).get(0).lines()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("영역 안에 역이 없으면 노선을 조회하지 않는다")
    void no_line_query_without_stations() {
        given(stationRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of());

        assertThat(service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG)).isEmpty();
        verifyNoInteractions(stationLineRepository);
    }

    @Test
    @DisplayName("영역 네 값을 그대로 넘기고, 한 번에 300개까지만 읽는다")
    void passes_box_and_caps_at_300() {
        given(stationRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of());

        service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(stationRepository).findActiveWithin(eq(SW_LAT), eq(SW_LNG), eq(NE_LAT), eq(NE_LNG),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.95")), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(300);
    }

    // 지도 SDK 는 JS double 을 그대로 넘긴다(소수 15자리까지 나온다). 극점 · 날짜변경선도 받는다 — 막는 것은 그 바깥만.
    @Test
    @DisplayName("자릿수 15 · 위도 ±90 · 경도 ±180 은 받는다")
    void accepts_widest_sane_box() {
        given(stationRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of());

        service.findWithin(new BigDecimal("37.123456789012345"), new BigDecimal("126.123456789012345"),
                new BigDecimal("37.123456789012346"), new BigDecimal("126.123456789012346"));
        service.findWithin(new BigDecimal("-90"), new BigDecimal("-180"), new BigDecimal("90"), new BigDecimal("180"));

        verify(stationRepository, times(2)).findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // 네 칸마다 두 규칙(자릿수 · 범위)을 따로 찌른다 — 한 칸의 검사를 빼먹으면 그 줄이 통과한다.
    @Test
    @DisplayName("지수 표기 · 자릿수 16 · 범위 밖 영역은 400 INVALID_REQUEST — 조회하지 않는다")
    void rejects_insane_box() {
        BigDecimal[][] bad = {
                {new BigDecimal("1e-999999999"), SW_LNG, NE_LAT, NE_LNG},
                {SCALE_16, SW_LNG, NE_LAT, NE_LNG},
                {new BigDecimal("-90.5"), SW_LNG, NE_LAT, NE_LNG},
                {SW_LAT, SW_LNG, SCALE_16, NE_LNG},
                {SW_LAT, SW_LNG, new BigDecimal("-90.1"), NE_LNG},
                {SW_LAT, SW_LNG, new BigDecimal("90.0000001"), NE_LNG},
                {SW_LAT, new BigDecimal("126.9000000000000001"), NE_LAT, NE_LNG},
                {SW_LAT, new BigDecimal("180.5"), NE_LAT, NE_LNG},
                {SW_LAT, new BigDecimal("-180.0000001"), NE_LAT, NE_LNG},
                {SW_LAT, SW_LNG, NE_LAT, new BigDecimal("127.0000000000000001")},
                {SW_LAT, SW_LNG, NE_LAT, new BigDecimal("1E+3")}};
        for (BigDecimal[] box : bad) {
            assertThatThrownBy(() -> service.findWithin(box[0], box[1], box[2], box[3]))
                    .as("%s", (Object) box)
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
        }
        verifyNoInteractions(stationRepository, stationLineRepository);
    }
}
