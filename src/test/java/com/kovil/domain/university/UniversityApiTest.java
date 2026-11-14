package com.kovil.domain.university;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
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
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 대학교 레이어를 **진짜 보안 필터(비로그인) · 진짜 쿼리 · 진짜 직렬화**로 태운다(H2).
 * ★ 영역 경계 위의 학교는 들어온다(BETWEEN) — 경계 바로 밖은 빠진다.
 * ★ 숨긴 학교(is_active = 0 — 원천에서 사라짐)는 지도에 안 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UniversityApiTest {

    private static final String PATH = "/api/v1/universities";

    @Autowired MockMvc mockMvc;
    @Autowired UniversityRepository universityRepository;
    @Autowired RegionRepository regionRepository;

    private Long seodaemun;
    private Long closedGu;
    private Long guUnderClosedSido;

    @BeforeEach
    void setUp() {
        Long seoul = regionRepository.saveAndFlush(Region.builder().code("11").nameKo("서울특별시").nameEn("Seoul").build()).getId();
        seodaemun = regionRepository.saveAndFlush(Region.builder().parentId(seoul).code("11410")
                .nameKo("서대문구").nameEn("Seodaemun-gu").build()).getId();
        closedGu = regionRepository.saveAndFlush(Region.builder().parentId(seoul).code("11440")
                .nameKo("마포구").nameEn("Mapo-gu").isActive(false).build()).getId();
        Long busan = regionRepository.saveAndFlush(Region.builder().code("26").nameKo("부산광역시").nameEn("Busan")
                .isActive(false).build()).getId();
        guUnderClosedSido = regionRepository.saveAndFlush(Region.builder().parentId(busan).code("26110")
                .nameKo("중구").nameEn("Jung-gu").build()).getId();
    }

    private University save(String code, String lat, String lng, Long regionId, boolean active) {
        return universityRepository.saveAndFlush(University.builder()
                .code(code).nameKo("테스트대학교 " + code).nameEn("Test University " + code)
                .campusNameKo(code.endsWith("2") ? "제2캠퍼스" : null).campusNameEn(code.endsWith("2") ? "Campus 2" : null)
                .addressRoad("서울특별시 서대문구 테스트로 1")
                .lat(new BigDecimal(lat)).lng(new BigDecimal(lng)).regionId(regionId).isActive(active).build());
    }

    private static MockHttpServletRequestBuilder box(String swLat, String swLng, String neLat, String neLng) {
        return get(PATH).param("swLat", swLat).param("swLng", swLng).param("neLat", neLat).param("neLng", neLng);
    }

    @Test
    @DisplayName("비로그인도 본다 — 영역 안 · 경계 위는 들어오고, 밖 · 숨긴 학교는 빠진다, 가운데에서 가까운 순")
    void anonymous_sees_universities_in_box() throws Exception {
        save("9000003", "37.5600000", "126.9400000", seodaemun, true);
        save("9000001", "37.5000000", "126.9000000", null, true);
        save("9000004", "37.4999999", "126.9400000", null, true);
        save("9000005", "37.5600000", "126.9400000", null, false);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(contains("9000003", "9000001")));
    }

    @Test
    @DisplayName("응답 칸 — 코드 · 이름 · 짧은 이름 · 캠퍼스 · 좌표 · 열린 지역 코드(닫힌 구 · 닫힌 시도 아래 구는 null)")
    void response_fields() throws Exception {
        universityRepository.saveAndFlush(University.builder()
                .code("9000012").nameKo("테스트대학교 9000012").nameEn("Test University 9000012")
                .shortNameKo("테대").shortNameEn("TU")
                .campusNameKo("제2캠퍼스").campusNameEn("Campus 2")
                .addressRoad("서울특별시 서대문구 테스트로 1")
                .lat(new BigDecimal("37.5600000")).lng(new BigDecimal("126.9400000"))
                .regionId(seodaemun).isActive(true).build());
        save("9000013", "37.5700000", "126.9500000", closedGu, true);
        save("9000014", "37.5800000", "126.9600000", guUnderClosedSido, true);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00").header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].code").value("9000012"))
                .andExpect(jsonPath("$.data[0].name").value("테스트대학교 9000012"))
                .andExpect(jsonPath("$.data[0].shortName").value("테대"))
                .andExpect(jsonPath("$.data[0].campusName").value("제2캠퍼스"))
                .andExpect(jsonPath("$.data[0].lat").value(37.56))
                .andExpect(jsonPath("$.data[0].lng").value(126.94))
                .andExpect(jsonPath("$.data[0].regionCode").value("11410"))
                // 없으면 키는 있고 값이 null — 앱은 null 이면 name 으로 떨어진다(키가 사라지면 계약이 바뀐 것)
                .andExpect(jsonPath("$.data[1].shortName").value(nullValue()))
                .andExpect(jsonPath("$.data[1].campusName").value(nullValue()))
                .andExpect(jsonPath("$.data[1].regionCode").value(nullValue()))
                .andExpect(jsonPath("$.data[2].regionCode").value(nullValue()));

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data[0].name").value("Test University 9000012"))
                .andExpect(jsonPath("$.data[0].shortName").value("TU"))
                .andExpect(jsonPath("$.data[0].campusName").value("Campus 2"));

        // 브라우저 · OS 언어 그대로 보내는 클라이언트 — ko-KR 도 국문이다.
        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00").header("Accept-Language", "ko-KR,ko;q=0.9"))
                .andExpect(jsonPath("$.data[0].name").value("테스트대학교 9000012"))
                .andExpect(jsonPath("$.data[0].shortName").value("테대"))
                .andExpect(jsonPath("$.data[0].campusName").value("제2캠퍼스"));
    }

    // 네 변마다 경계 위 한 점(들어온다) · 바로 밖 한 점(빠진다) — 한 변의 비교가 엇나가면(< · 다른 칸) 그 변에서 걸린다.
    @Test
    @DisplayName("네 변 모두 — 경계 위는 들어오고, 경계 바로 밖(1e-7)은 빠진다")
    void every_edge_is_inclusive_and_tight() throws Exception {
        save("9000031", "37.5000000", "126.9400000", null, true);
        save("9000032", "37.6000000", "126.9400000", null, true);
        save("9000033", "37.5600000", "126.9000000", null, true);
        save("9000034", "37.5600000", "127.0000000", null, true);
        save("9000041", "37.4999999", "126.9400000", null, true);
        save("9000042", "37.6000001", "126.9400000", null, true);
        save("9000043", "37.5600000", "126.8999999", null, true);
        save("9000044", "37.5600000", "127.0000001", null, true);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(containsInAnyOrder("9000031", "9000032", "9000033", "9000034")));
    }

    // 200개 상한에서 어느 학교가 남는지를 이 순서가 정한다. 코드 순으로 자르면 화면 한쪽이 통째로 빈다.
    // 가운데(37.55, 126.95)를 사이에 둔 같은 거리 둘은 코드 순, 코드가 가장 작은 먼 학교는 맨 뒤.
    @Test
    @DisplayName("지도 영역 가운데에서 가까운 순이고, 같은 거리면 학교코드 순이다")
    void ordered_by_distance_then_code() throws Exception {
        save("9000060", "37.5900000", "126.9900000", null, true);
        save("9000062", "37.5520000", "126.9500000", null, true);
        save("9000061", "37.5480000", "126.9500000", null, true);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(contains("9000061", "9000062", "9000060")));
    }

    // 경도 1도는 위도 1도보다 짧다(cos 위도). 북쪽 0.010도(약 1.11km)보다 동쪽 0.012도(약 1.06km)가 가깝다.
    @Test
    @DisplayName("경도 차이는 위도에 맞춰 줄여 잰다 — 북쪽 0.010도보다 동쪽 0.012도가 가깝다")
    void longitude_is_scaled_by_latitude() throws Exception {
        save("9000071", "37.5600000", "126.9500000", null, true);
        save("9000072", "37.5500000", "126.9620000", null, true);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data[*].code").value(contains("9000072", "9000071")));
    }

    // 반대쪽 경계 — 동쪽 0.013도(약 1.15km)는 북쪽 0.010도(약 1.11km)보다 멀다. 가중치를 0 · 작게 잘못 주면 여기서 걸린다.
    @Test
    @DisplayName("경도 차이를 너무 줄이지도 않는다 — 동쪽 0.013도는 북쪽 0.010도보다 멀다")
    void longitude_is_not_over_scaled() throws Exception {
        save("9000081", "37.5600000", "126.9500000", null, true);
        save("9000082", "37.5500000", "126.9630000", null, true);

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data[*].code").value(contains("9000081", "9000082")));
    }

    @Test
    @DisplayName("상한을 넘으면 가운데에서 먼 학교부터 빠진다 — 코드가 가장 작아도")
    void cap_drops_the_farthest_first() throws Exception {
        save("8000000", "37.5000000", "126.9000000", null, true);
        IntStream.rangeClosed(1, 200).forEach(i -> save(String.format("8%06d", i), "37.5500000", "126.9500000", null, true));

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data", hasSize(200)))
                .andExpect(jsonPath("$.data[*].code").value(not(hasItem("8000000"))));
    }

    @Test
    @DisplayName("한 응답 200개까지 — 같은 거리면 학교코드가 작은 200개")
    void capped_at_200() throws Exception {
        IntStream.range(0, 201).forEach(i -> save(String.format("8%06d", 200 - i), "37.5600000", "126.9400000", null, true));

        mockMvc.perform(box("37.50", "126.90", "37.60", "127.00"))
                .andExpect(jsonPath("$.data", hasSize(200)))
                .andExpect(jsonPath("$.data[0].code").value("8000000"))
                .andExpect(jsonPath("$.data[199].code").value("8000199"));
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
        mockMvc.perform(box("37.50", "126.90", "91", "127.00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("남서가 북동보다 위면 빈 목록(200)")
    void inverted_box_is_empty() throws Exception {
        save("9000021", "37.5600000", "126.9400000", null, true);

        mockMvc.perform(box("37.60", "127.00", "37.50", "126.90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
