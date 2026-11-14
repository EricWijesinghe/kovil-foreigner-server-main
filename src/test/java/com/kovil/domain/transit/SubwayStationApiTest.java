package com.kovil.domain.transit;

import com.kovil.domain.transit.entity.SubwayLine;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.entity.SubwayStationLine;
import com.kovil.domain.transit.repository.SubwayStationLineRepository;
import com.kovil.domain.transit.repository.SubwayStationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 지하철역 레이어를 **진짜 보안 필터(비로그인) · 진짜 쿼리(엔티티 조인 + 생성자 표현식) · 진짜 직렬화**로 태운다(H2).
 * ★ 환승역의 lines[] 순서는 역-노선의 sort_order, 같으면 노선의 sort_order 다 — 넣은 순서(id)와 엇갈리게 넣어 갈리게 한다.
 * ★ 숨긴 노선 · 숨긴 역(is_active = 0 — 원천에서 사라짐)은 지도에 안 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubwayStationApiTest {

    private static final String PATH = "/api/v1/transit/subway-stations";

    @Autowired MockMvc mockMvc;
    @Autowired SubwayStationRepository stationRepository;
    @Autowired SubwayStationLineRepository stationLineRepository;
    @Autowired EntityManager em;

    private SubwayLine line2;
    private SubwayLine line5;
    private SubwayLine retired;

    @BeforeEach
    void setUp() {
        line2 = line("SEL-2", "2호선", "Line 2", "#00A84D", true, 2);
        line5 = line("SEL-5", "5호선", "Line 5", "#996CAC", true, 5);
        retired = line("OLD-1", "옛 노선", "Old Line", "#123456", false, 9);
    }

    private SubwayLine line(String code, String ko, String en, String color, boolean active, int order) {
        SubwayLine line = SubwayLine.builder().code(code).nameKo(ko).nameEn(en).color(color)
                .isActive(active).sortOrder(order).build();
        em.persist(line);
        return line;
    }

    private SubwayStation station(String code, String lat, String lng, boolean active) {
        return stationRepository.saveAndFlush(SubwayStation.builder().code(code).nameKo(code + "역").nameEn("Station " + code)
                .lat(new BigDecimal(lat)).lng(new BigDecimal(lng)).isActive(active).build());
    }

    private void link(SubwayStation station, SubwayLine line, int order) {
        stationLineRepository.saveAndFlush(SubwayStationLine.builder()
                .stationId(station.getId()).lineId(line.getId()).sortOrder(order).build());
    }

    private static MockHttpServletRequestBuilder box(String swLat, String swLng, String neLat, String neLng) {
        return get(PATH).param("swLat", swLat).param("swLng", swLng).param("neLat", neLat).param("neLng", neLng);
    }

    @Test
    @DisplayName("비로그인도 본다 — 환승역은 역 하나에 노선 여럿(sort_order 순), 숨긴 노선은 빠진다")
    void anonymous_sees_transfer_station_with_ordered_lines() throws Exception {
        SubwayStation wangsimni = station("ST0070", "37.5612000", "127.0380000", true);
        link(wangsimni, line5, 2);
        link(wangsimni, retired, 3);
        link(wangsimni, line2, 1);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.10").header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.data[0].code").value("ST0070"))
                .andExpect(jsonPath("$.data[0].name").value("ST0070역"))
                .andExpect(jsonPath("$.data[0].lat").value(37.5612))
                .andExpect(jsonPath("$.data[0].lng").value(127.038))
                .andExpect(jsonPath("$.data[0].lines[0]", aMapWithSize(3)))
                .andExpect(jsonPath("$.data[0].lines[*].code").value(contains("SEL-2", "SEL-5")))
                .andExpect(jsonPath("$.data[0].lines[*].name").value(contains("2호선", "5호선")))
                .andExpect(jsonPath("$.data[0].lines[*].color").value(contains("#00A84D", "#996CAC")));

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.10").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data[0].name").value("Station ST0070"))
                .andExpect(jsonPath("$.data[0].lines[*].name").value(contains("Line 2", "Line 5")));

        // 앱은 기기 언어를 그대로 보낸다 — ko-KR 도 국문이다.
        mockMvc.perform(box("37.50", "126.90", "37.60", "127.10").header("Accept-Language", "ko-KR"))
                .andExpect(jsonPath("$.data[0].name").value("ST0070역"))
                .andExpect(jsonPath("$.data[0].lines[*].name").value(contains("2호선", "5호선")));
    }

    // 역-노선 순서와 노선 순서를 거꾸로 둔다 — 생성 데이터(dml/05)는 둘이 늘 같아서 MySQL 테스트로는 갈리지 않는다.
    @Test
    @DisplayName("역-노선 순서가 노선 순서보다 먼저다 — 넣은 순서도 아니다")
    void station_line_order_beats_line_order() throws Exception {
        SubwayStation station = station("ST0090", "37.5612000", "127.0380000", true);
        link(station, line2, 2);
        link(station, line5, 1);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lines[*].code").value(contains("SEL-5", "SEL-2")));
    }

    // 역-노선의 순서가 같으면(원천에서 같은 자리) 노선 자체의 순서로 가른다 — 노선 순서는 가장 앞이지만 id 는 가장 크고,
    // 코드도 가장 뒤이고, 링크도 나중에 넣는다(넣은 순서 · 노선 id · 노선 코드로 가르면 여기서 걸린다).
    @Test
    @DisplayName("역-노선 순서가 같으면 노선 순서로 — 넣은 순서 · 노선 id · 노선 코드가 아니다")
    void ties_fall_back_to_line_order() throws Exception {
        SubwayLine first = line("ZZ-1", "1호선", "Line 1", "#0052A4", true, 1);
        SubwayStation station = station("ST0080", "37.5612000", "127.0380000", true);
        link(station, line5, 1);
        link(station, first, 1);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.10"))
                .andExpect(jsonPath("$.data[0].lines[*].code").value(contains("ZZ-1", "SEL-5")));
    }

    // 코드 순서와 이름 순서(국문 · 영문), 넣은 순서를 전부 거꾸로 둔다.
    @Test
    @DisplayName("같은 거리면 역 코드 순이다 — 이름 순 · 넣은 순서가 아니다")
    void ordered_by_code_not_name() throws Exception {
        for (String[] s : new String[][]{{"ST0002", "가산역", "Gasan"}, {"ST0001", "하계역", "Hagye"}}) {
            stationRepository.saveAndFlush(SubwayStation.builder().code(s[0]).nameKo(s[1]).nameEn(s[2])
                    .lat(new BigDecimal("37.5600000")).lng(new BigDecimal("126.9400000")).isActive(true).build());
        }

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data[*].code").value(contains("ST0001", "ST0002")));
    }

    @Test
    @DisplayName("영역 안 · 경계 위는 들어오고, 밖 · 숨긴 역은 빠진다, 가운데에서 가까운 순 — 노선 없는 역은 빈 lines")
    void box_and_hidden_stations() throws Exception {
        station("ST0003", "37.5600000", "126.9400000", true);
        link(station("ST0001", "37.5000000", "126.9000000", true), line2, 1);
        station("ST0004", "37.4999999", "126.9400000", true);
        station("ST0005", "37.5600000", "126.9400000", false);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data[*].code").value(contains("ST0003", "ST0001")))
                .andExpect(jsonPath("$.data[0].lines", hasSize(0)))
                .andExpect(jsonPath("$.data[1].lines[*].code").value(contains("SEL-2")));
    }

    // 네 변마다 경계 위 한 점(들어온다) · 바로 밖 한 점(빠진다) — 한 변의 비교가 엇나가면(< · 다른 칸) 그 변에서 걸린다.
    @Test
    @DisplayName("네 변 모두 경계 위는 들어오고 1e-7 밖은 빠진다")
    void every_edge_is_inclusive_and_tight() throws Exception {
        List.of(new String[]{"SE01", "37.5000000", "126.9500000"}, new String[]{"SE02", "37.6000000", "126.9500000"},
                new String[]{"SE03", "37.5500000", "126.9000000"}, new String[]{"SE04", "37.5500000", "127.0000000"},
                new String[]{"SO01", "37.4999999", "126.9500000"}, new String[]{"SO02", "37.6000001", "126.9500000"},
                new String[]{"SO03", "37.5500000", "126.8999999"}, new String[]{"SO04", "37.5500000", "127.0000001"})
                .forEach(p -> station(p[0], p[1], p[2], true));

        mockMvc.perform(box("37.5", "126.9", "37.6", "127.0"))
                .andExpect(jsonPath("$.data[*].code").value(containsInAnyOrder("SE01", "SE02", "SE03", "SE04")));
    }

    // 위도 37.55 에서 경도 1도는 위도 1도의 cos 배 — 동쪽 0.012도는 북쪽 0.010도보다 가깝고, 0.013도는 멀다.
    @Test
    @DisplayName("경도 차이는 위도에 맞춰 잰다 — 동쪽 0.012도는 북쪽 0.010도보다 가깝고 0.013도는 멀다")
    void longitude_is_scaled_by_latitude() throws Exception {
        station("SL01", "37.5600000", "126.9500000", true);
        station("SL02", "37.5500000", "126.9620000", true);
        station("SL03", "37.5500000", "126.9370000", true);
        station("SL04", "37.5500000", "126.9630000", true);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data[*].code").value(contains("SL02", "SL01", "SL03", "SL04")));
    }

    @Test
    @DisplayName("상한을 넘으면 가운데에서 먼 역부터 빠진다 — 코드가 가장 작아도")
    void cap_drops_the_farthest_first() throws Exception {
        station("SX0000", "37.5000000", "126.9000000", true);
        IntStream.rangeClosed(1, 300).forEach(i -> station(String.format("SX%04d", i), "37.5500000", "126.9500000", true));

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data", hasSize(300)))
                .andExpect(jsonPath("$.data[*].code").value(not(hasItem("SX0000"))));
    }

    @Test
    @DisplayName("한 응답 300개까지 — 같은 거리면 역 코드가 작은 300개")
    void capped_at_300() throws Exception {
        IntStream.range(0, 301).forEach(i -> station(String.format("SX%04d", 300 - i), "37.5600000", "126.9400000", true));

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data", hasSize(300)))
                .andExpect(jsonPath("$.data[0].code").value("SX0000"))
                .andExpect(jsonPath("$.data[299].code").value("SX0299"));
    }

    @Test
    @DisplayName("영역 네 값이 하나라도 빠지면 · 지수 표기 · 범위 밖은 400 INVALID_REQUEST")
    void box_is_required_and_sane() throws Exception {
        String[] names = {"swLat", "swLng", "neLat", "neLng"};
        String[] values = {"37.50", "126.90", "37.60", "127.00"};
        for (int missing = 0; missing < names.length; missing++) {
            MockHttpServletRequestBuilder request = get(PATH);
            for (int i = 0; i < names.length; i++) {
                if (i != missing) {
                    request = request.param(names[i], values[i]);
                }
            }
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
        mockMvc.perform(box("1e-999999999", "126.90", "37.60", "127.00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        mockMvc.perform(box("37.50", "181", "37.60", "127.00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("남서가 북동보다 위면 빈 목록(200)")
    void inverted_box_is_empty() throws Exception {
        station("ST0021", "37.5600000", "126.9400000", true);

        mockMvc.perform(box("37.60", "127.00", "37.50", "126.90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
