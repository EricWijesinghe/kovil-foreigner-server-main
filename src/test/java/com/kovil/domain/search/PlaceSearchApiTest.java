package com.kovil.domain.search;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.transit.entity.SubwayLine;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.entity.SubwayStationLine;
import com.kovil.domain.transit.repository.SubwayStationLineRepository;
import com.kovil.domain.transit.repository.SubwayStationRepository;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
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
import java.time.LocalDateTime;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 장소 검색을 **진짜 보안 필터(비로그인) · 진짜 쿼리(LIKE ESCAPE · CASE 정렬 · 열린 지역 조인 · 공개 매물 평균) · 진짜 직렬화**로 태운다(H2).
 * ★ 유형을 넘는 순서는 서비스가, 유형 안의 순서는 저장소 쿼리가 정한다 — 둘 다 진짜로 돌아야 아래 순서가 나온다.
 * ★ 지역 좌표는 그 구 공개 매물의 흐린 좌표 평균이다 — 실좌표 · 거래 완료 · 승인 안 된 사무소 매물은 섞이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlaceSearchApiTest {

    private static final String PATH = "/api/v1/search/places";

    @Autowired MockMvc mockMvc;
    @Autowired RegionRepository regionRepository;
    @Autowired UniversityRepository universityRepository;
    @Autowired SubwayStationRepository stationRepository;
    @Autowired SubwayStationLineRepository stationLineRepository;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired EntityManager em;

    private Long seoul;
    private Long seodaemun;
    private Long closedGu;
    private Long guUnderClosedSido;

    @BeforeEach
    void setUp() {
        seoul = region(null, "11", "서울특별시", "Seoul", true);
        seodaemun = region(seoul, "11410", "서대문구", "Seodaemun-gu", true);
        closedGu = region(seoul, "11440", "마포구", "Mapo-gu", false);
        Long busan = region(null, "26", "부산광역시", "Busan", false);
        guUnderClosedSido = region(busan, "26110", "중구", "Jung-gu", true);
    }

    private Long region(Long parentId, String code, String ko, String en, boolean active) {
        return region(parentId, code, ko, en, active, 0);
    }

    private Long region(Long parentId, String code, String ko, String en, boolean active, int sortOrder) {
        return regionRepository.saveAndFlush(Region.builder().parentId(parentId).code(code)
                .nameKo(ko).nameEn(en).isActive(active).sortOrder(sortOrder).build()).getId();
    }

    private void university(String code, String ko, String en, Long regionId, boolean active) {
        universityRepository.saveAndFlush(University.builder().code(code).nameKo(ko).nameEn(en)
                .campusNameKo(code.endsWith("2") ? "제2캠퍼스" : null).campusNameEn(code.endsWith("2") ? "Campus 2" : null)
                .addressRoad("서울특별시 서대문구 테스트로 1")
                .lat(new BigDecimal("37.5600000")).lng(new BigDecimal("126.9400000"))
                .regionId(regionId).isActive(active).build());
    }

    private SubwayStation station(String code, String ko, String en, Long regionId, boolean active) {
        return stationRepository.saveAndFlush(SubwayStation.builder().code(code).nameKo(ko).nameEn(en)
                .lat(new BigDecimal("37.5550000")).lng(new BigDecimal("126.9370000"))
                .regionId(regionId).isActive(active).build());
    }

    private SubwayLine line(String code, String ko, String en, boolean active, int order) {
        SubwayLine line = SubwayLine.builder().code(code).nameKo(ko).nameEn(en).color("#00A84D")
                .isActive(active).sortOrder(order).build();
        em.persist(line);
        return line;
    }

    private void link(SubwayStation station, SubwayLine line, int order) {
        stationLineRepository.saveAndFlush(SubwayStationLine.builder()
                .stationId(station.getId()).lineId(line.getId()).sortOrder(order).build());
    }

    private static MockHttpServletRequestBuilder search(String query) {
        return get(PATH).param("query", query);
    }

    @Test
    @DisplayName("비로그인도 부른다 — query 가 없거나 공백뿐이면 빈 목록(200), 옛 이름 q 는 받지 않는다")
    void blank_query_is_empty() throws Exception {
        university("9000001", "서울테스트대학교", "Seoul Test University", seodaemun, true);

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(search("   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(get(PATH).param("q", "서울테스트"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(search("서울테스트"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000001")));
    }

    // 완전 일치(0) → 앞부분(1) → 포함(2), 같은 순위면 지역 → 대학교 → 역. 국문 · 영문 중 나은 쪽 순위를 쓴다.
    @Test
    @DisplayName("유형을 넘어 완전 일치 → 앞부분 → 포함, 같은 순위는 지역 → 대학교 → 역 — 대소문자 무시")
    void ranks_across_types() throws Exception {
        region(seoul, "11901", "동신촌구", "Dongsinchon-gu", true);
        region(seoul, "11902", "신촌구", "Sinchon-gu", true);
        university("9000011", "서울신촌대학교", "Seoul Sinchon University", seodaemun, true);
        university("9000012", "신촌대학교", "Sinchon University", seodaemun, true);
        station("ST9002", "신촌오거리", "Sinchon-ogeori", seodaemun, true);
        station("ST9001", "신촌", "Sinchon", seodaemun, true);

        for (String query : new String[]{"sinchon", "SINCHON", " Sinchon "}) {
            mockMvc.perform(search(query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].code")
                            .value(contains("ST9001", "11902", "9000012", "ST9002", "11901", "9000011")))
                    .andExpect(jsonPath("$.data.items[*].type")
                            .value(contains("STATION", "REGION", "UNIVERSITY", "STATION", "REGION", "UNIVERSITY")));
        }
        mockMvc.perform(search("신촌").header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.data.items[*].code")
                        .value(contains("ST9001", "11902", "9000012", "ST9002", "11901", "9000011")));
    }

    // ★ '!' 가 이스케이프 문자다 — '!' 를 두 번 쓰지 않으면 '!' 검색이 '%' 를 글자로 찾게 되어 100% 가 걸린다.
    @Test
    @DisplayName("% · _ · ! 는 글자 그대로 찾는다")
    void like_wildcards_are_literal() throws Exception {
        university("9000021", "100%대학교", "Hundred Percent University", seodaemun, true);
        university("9000031", "100X대학교", "Hundred X University", seodaemun, true);
        university("9000041", "놀람!대학교", "Wow University", seodaemun, true);

        mockMvc.perform(search("0%"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000021")));
        mockMvc.perform(search("0_"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(search("!"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000041")));
        mockMvc.perform(search("%"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000021")));
    }

    @Test
    @DisplayName("지역은 열린 시군구만 — 닫힌 구 · 닫힌 시도의 구 · 시도 자신은 안 나온다")
    void regions_are_open_districts_only() throws Exception {
        mockMvc.perform(search("구"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("11410")));
        mockMvc.perform(search("서울"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(search("busan"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(search("jung-gu"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    // ★ 지도 중심은 매물 필터 없이 그 구의 공개 매물 전부다 — 모든 축이 기본 매물과 다른 ps-villa 가 빠지면 평균이 달라진다.
    @Test
    @DisplayName("지역 항목 — 부제는 시도, 좌표는 매물 필터 없이 공개 매물 흐린 좌표 평균(거래 완료 · 승인 안 된 사무소 제외), 없으면 null")
    void region_item_and_center() throws Exception {
        Long other = region(seoul, "11470", "양천구", "Yangcheon-gu", true);
        Long approved = officeOf("ps-office", "99999-2026-00131", PartnerDivisionStatus.APPROVED);
        Long pending = officeOf("ps-pending", "99999-2026-00132", PartnerDivisionStatus.PENDING);
        listing("ps-a", approved, seodaemun, ListingStatus.ACTIVE, "37.5600000", "126.9400000");
        listing("ps-r", approved, seodaemun, ListingStatus.RESERVED, "37.5700000", "126.9600000");
        listing("ps-villa", approved, seodaemun, ListingStatus.ACTIVE, "37.5800000", "126.9800000",
                ListingCategory.HOUSE_VILLA, RoomType.TWO_ROOM, SellingType.LEASE, FloorType.ROOFTOP,
                AvailabilityAnswer.POSSIBLE);
        listing("ps-done", approved, seodaemun, ListingStatus.COMPLETED, "38.0000000", "127.5000000");
        listing("ps-pend", pending, seodaemun, ListingStatus.ACTIVE, "36.0000000", "125.0000000");
        listing("ps-other", pending, other, ListingStatus.ACTIVE, "37.5200000", "126.8600000");

        mockMvc.perform(search("서대문").header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0]", aMapWithSize(7)))
                .andExpect(jsonPath("$.data.items[0].type").value("REGION"))
                .andExpect(jsonPath("$.data.items[0].code").value("11410"))
                .andExpect(jsonPath("$.data.items[0].name").value("서대문구"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("서울특별시"))
                .andExpect(jsonPath("$.data.items[0].lat").value(37.57))
                .andExpect(jsonPath("$.data.items[0].lng").value(126.96))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("11410"));

        mockMvc.perform(search("yangcheon").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data.items[0].name").value("Yangcheon-gu"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("Seoul"))
                .andExpect(jsonPath("$.data.items[0].lat").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].lng").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("11470"));
    }

    // 부제의 구 이름은 열림과 관계없이 보이고, regionCode(목록 필터) 는 열린 구에만 붙는다.
    @Test
    @DisplayName("대학교 항목 — 부제는 캠퍼스 · 구, regionCode 는 열린 구만, 숨긴 학교는 빠진다")
    void university_items() throws Exception {
        university("9000052", "테스트대학교", "Test University", seodaemun, true);
        university("9000053", "테스트마포대학교", "Test Mapo University", closedGu, true);
        university("9000054", "테스트부산대학교", "Test Busan University", guUnderClosedSido, true);
        university("9000055", "테스트무소속대학교", "Test Nowhere University", null, true);
        university("9000056", "테스트폐교대학교", "Test Closed University", seodaemun, false);

        mockMvc.perform(search("테스트").header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000052", "9000053", "9000054", "9000055")))
                .andExpect(jsonPath("$.data.items[0].name").value("테스트대학교"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("제2캠퍼스 · 서대문구"))
                .andExpect(jsonPath("$.data.items[0].lat").value(37.56))
                .andExpect(jsonPath("$.data.items[0].lng").value(126.94))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("11410"))
                .andExpect(jsonPath("$.data.items[1].subtitle").value("마포구"))
                .andExpect(jsonPath("$.data.items[1].regionCode").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].subtitle").value("중구"))
                .andExpect(jsonPath("$.data.items[2].regionCode").value(nullValue()))
                .andExpect(jsonPath("$.data.items[3].subtitle").value(nullValue()))
                .andExpect(jsonPath("$.data.items[3].regionCode").value(nullValue()));

        mockMvc.perform(search("test").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data.items[0].name").value("Test University"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("Campus 2 · Seodaemun-gu"));
    }

    @Test
    @DisplayName("역 항목 — 부제는 노선(역-노선 순서, 숨긴 노선 제외), regionCode 는 열린 구만, 숨긴 역은 빠진다")
    void station_items() throws Exception {
        SubwayLine line2 = line("SEL-2", "2호선", "Line 2", true, 2);
        SubwayLine line5 = line("SEL-5", "5호선", "Line 5", true, 5);
        SubwayLine retired = line("OLD-1", "옛 노선", "Old Line", false, 1);
        SubwayStation transfer = station("ST9011", "테스트역", "Test Station", seodaemun, true);
        link(transfer, line5, 2);
        link(transfer, retired, 0);
        link(transfer, line2, 1);
        station("ST9012", "테스트마포역", "Test Mapo Station", closedGu, true);
        station("ST9013", "테스트무선역", "Test Lineless Station", null, true);
        station("ST9014", "테스트폐역", "Test Closed Station", seodaemun, false);

        mockMvc.perform(search("테스트").header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("ST9011", "ST9012", "ST9013")))
                .andExpect(jsonPath("$.data.items[*].type").value(contains("STATION", "STATION", "STATION")))
                .andExpect(jsonPath("$.data.items[0].name").value("테스트역"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("2호선 · 5호선"))
                .andExpect(jsonPath("$.data.items[0].lat").value(37.555))
                .andExpect(jsonPath("$.data.items[0].lng").value(126.937))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("11410"))
                .andExpect(jsonPath("$.data.items[1].regionCode").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].subtitle").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].regionCode").value(nullValue()));

        mockMvc.perform(search("test").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data.items[0].name").value("Test Station"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("Line 2 · Line 5"));
    }

    @Test
    @DisplayName("유형마다 10개까지, limit 은 1 ~ 50 으로 맞춘다 — 숫자가 아니면 400 INVALID_REQUEST")
    void per_type_cap_and_limit() throws Exception {
        for (int i = 10; i < 22; i++) {
            station("ST91" + i, "테스트" + i + "역", "Test Station " + i, null, true);
        }
        university("9000061", "테스트대학교", "Test University", null, true);

        mockMvc.perform(search("테스트"))
                .andExpect(jsonPath("$.data.items", hasSize(11)))
                .andExpect(jsonPath("$.data.items[0].code").value("9000061"))
                .andExpect(jsonPath("$.data.items[10].code").value("ST9119"));
        mockMvc.perform(search("테스트").param("limit", "3"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000061", "ST9110", "ST9111")));
        mockMvc.perform(search("테스트").param("limit", "0"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000061")));
        mockMvc.perform(search("테스트").param("limit", "-5"))
                .andExpect(jsonPath("$.data.items", hasSize(1)));
        mockMvc.perform(search("테스트").param("limit", "1000"))
                .andExpect(jsonPath("$.data.items", hasSize(11)));
        mockMvc.perform(search("테스트").param("limit", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    // ★ 유형마다 어떤 10개가 남는지는 저장소 ORDER BY CASE 만 정한다 — 서비스는 뽑힌 뒤에만 다시 정렬한다.
    //   앞부분 일치를 코드가 가장 큰 쪽에 두어, CASE 가 없으면(코드 순) 11번째로 잘리게 한다.
    @Test
    @DisplayName("유형마다 10개는 순위로 자른다 — 포함 일치 10개(작은 코드)보다 앞부분 일치 1개(큰 코드)가 남는다")
    void per_type_cap_keeps_prefix_over_contains() throws Exception {
        for (int i = 1; i <= 10; i++) {
            region(seoul, String.format("118%02d", i), "가테스트" + i + "구", "Ga-test " + i + "-gu", true);
            university(String.format("90010%02d", i), "가테스트" + i + "대학교", "Ga Test University " + i, null, true);
            station(String.format("ST93%02d", i), "가테스트" + i + "역", "Ga Test Station " + i, null, true);
        }
        region(seoul, "11898", "테스트가구", "Test-ga-gu", true);
        university("9001098", "테스트가대학교", "Test Ga University", null, true);
        station("ST9398", "테스트가역", "Test Ga Station", null, true);

        mockMvc.perform(search("테스트").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(30)))
                .andExpect(jsonPath("$.data.items[0].code").value("11898"))
                .andExpect(jsonPath("$.data.items[1].code").value("9001098"))
                .andExpect(jsonPath("$.data.items[2].code").value("ST9398"))
                .andExpect(jsonPath("$.data.items[?(@.type == 'REGION')].code")
                        .value(contains(bestThenNine("11898", "118%02d"))))
                .andExpect(jsonPath("$.data.items[?(@.type == 'UNIVERSITY')].code")
                        .value(contains(bestThenNine("9001098", "90010%02d"))))
                .andExpect(jsonPath("$.data.items[?(@.type == 'STATION')].code")
                        .value(contains(bestThenNine("ST9398", "ST93%02d"))));
    }

    // 완전 일치를 코드가 가장 큰 쪽에 둔다 — WHEN = :exact 가지가 없으면 앞부분 일치와 같은 순위가 되어 코드 순으로 잘린다.
    @Test
    @DisplayName("유형마다 10개는 순위로 자른다 — 앞부분 일치 10개(작은 코드)보다 완전 일치 1개(큰 코드)가 맨 앞에 남는다")
    void per_type_cap_puts_exact_first() throws Exception {
        for (int i = 1; i <= 10; i++) {
            region(seoul, String.format("118%02d", i), "테스트" + i + "구", "Test " + i + "-gu", true);
            university(String.format("90010%02d", i), "테스트" + i + "대학교", "Test University " + i, null, true);
            station(String.format("ST93%02d", i), "테스트" + i + "역", "Test Station " + i, null, true);
        }
        region(seoul, "11899", "테스트", "Test", true);
        university("9001099", "테스트", "Test", null, true);
        station("ST9399", "테스트", "Test", null, true);

        mockMvc.perform(search("테스트").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(30)))
                .andExpect(jsonPath("$.data.items[0].code").value("11899"))
                .andExpect(jsonPath("$.data.items[1].code").value("9001099"))
                .andExpect(jsonPath("$.data.items[2].code").value("ST9399"))
                .andExpect(jsonPath("$.data.items[?(@.type == 'REGION')].code")
                        .value(contains(bestThenNine("11899", "118%02d"))))
                .andExpect(jsonPath("$.data.items[?(@.type == 'UNIVERSITY')].code")
                        .value(contains(bestThenNine("9001099", "90010%02d"))))
                .andExpect(jsonPath("$.data.items[?(@.type == 'STATION')].code")
                        .value(contains(bestThenNine("ST9399", "ST93%02d"))));
    }

    // 유형 안 기대 순서 — 순위가 나은 하나가 맨 앞, 그 뒤로 코드 순 1 ~ 9번. 10번(코드가 가장 큰 쪽)은 잘린다.
    private static String[] bestThenNine(String best, String codeFormat) {
        return Stream.concat(Stream.of(best), IntStream.rangeClosed(1, 9).mapToObj(i -> String.format(codeFormat, i)))
                .toArray(String[]::new);
    }

    // ★ 구의 sortOrder 는 시도 안에서만 뜻이 있다 — 시도 순서(sortOrder → code)가 먼저, 그다음 구 순서.
    //   인천은 sortOrder 가 대구보다 앞이고 code 는 뒤라, 시도를 code 로만 줄 세우면 순서가 바뀐다.
    @Test
    @DisplayName("같은 순위의 같은 이름 구는 시도 순서 → 구 순서로 나온다")
    void same_rank_districts_follow_sido_then_district_order() throws Exception {
        Long daegu = region(null, "27", "대구광역시", "Daegu", true, 5);
        Long incheon = region(null, "28", "인천광역시", "Incheon", true, 3);
        region(daegu, "27110", "중구", "Jung-gu", true, 1);
        region(incheon, "28110", "중구", "Jung-gu", true, 1);
        region(seoul, "11140", "중구", "Jung-gu", true, 2);
        region(seoul, "11260", "중랑구", "Jungnang-gu", true, 1);

        mockMvc.perform(search("중구").header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].code").value(contains("11140", "28110", "27110")))
                .andExpect(jsonPath("$.data.items[*].subtitle").value(contains("서울특별시", "인천광역시", "대구광역시")));
        mockMvc.perform(search("중"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("11260", "11140", "28110", "27110")));
    }

    @Test
    @DisplayName("query 는 50자까지만 본다 — 뒤에 붙은 글자는 버린다")
    void query_is_cut_at_50() throws Exception {
        String fifty = "가".repeat(50);
        university("9000071", fifty + "대학교", "Long Name University", null, true);

        mockMvc.perform(search(fifty + "나"))
                .andExpect(jsonPath("$.data.items[*].code").value(contains("9000071")));
        mockMvc.perform(search("가".repeat(49) + "나"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    private Long officeOf(String uuid, String regNo, PartnerDivisionStatus divisionStatus) {
        Long representativeId = userRepository.save(User.builder()
                .uuid("u-" + uuid).name("홍길동")
                .nickname("u-" + uuid).nicknameNormalized("u-" + uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build()).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 서대문구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representativeId).userId(representativeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(divisionStatus)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    // 실좌표는 흐린 좌표와 멀리 둔다 — 평균이 실좌표로 계산되면 값이 달라진다.
    private void listing(String uuid, Long officeId, Long regionId, ListingStatus status, String displayLat, String displayLng) {
        listing(uuid, officeId, regionId, status, displayLat, displayLng, ListingCategory.ONE_TWO_ROOM, RoomType.ONE_ROOM,
                SellingType.MONTHLY_RENT, FloorType.GROUND, AvailabilityAnswer.ASK);
    }

    private void listing(String uuid, Long officeId, Long regionId, ListingStatus status, String displayLat, String displayLng,
                         ListingCategory category, RoomType roomType, SellingType sellingType, FloorType floorType,
                         AvailabilityAnswer residentRegistration) {
        listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261013-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L).regionId(regionId)
                .category(category).roomType(roomType)
                .title("테스트").sellingType(sellingType)
                .deposit(3_000_000L).monthlyRent(sellingType.hasMonthlyRent() ? 300_000L : 0L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(floorType)
                .residentRegistration(residentRegistration).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("33.0000000")).realLng(new BigDecimal("124.0000000"))
                .displayLat(new BigDecimal(displayLat)).displayLng(new BigDecimal(displayLng))
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status).build());
    }
}
