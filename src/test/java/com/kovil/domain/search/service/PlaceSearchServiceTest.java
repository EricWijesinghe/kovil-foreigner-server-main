package com.kovil.domain.search.service;

import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.listing.repository.RegionPriceStat;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.search.dto.response.PlaceSearchResponse;
import com.kovil.domain.search.enums.PlaceType;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.repository.SubwayStationLineRepository;
import com.kovil.domain.transit.repository.SubwayStationLineRow;
import com.kovil.domain.transit.repository.SubwayStationRepository;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Collection;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 장소 검색의 조립부 — 세 유형을 한 목록으로 합친다.
 * ★ 순서는 순위(완전 일치 → 앞부분 일치 → 포함)가 먼저고, 같은 순위 안에서 유형(지역 → 대학교 → 역), 같은 유형 안에서 저장소 순서다.
 * ★ 부제의 구 이름은 열림과 관계없이 붙지만, regionCode(목록 필터로 쓴다)는 열린 구만 — 닫힌 구 코드는 목록에서 400 을 낸다.
 */
class PlaceSearchServiceTest {

    private RegionRepository regionRepository;
    private UniversityRepository universityRepository;
    private SubwayStationRepository stationRepository;
    private SubwayStationLineRepository stationLineRepository;
    private ListingRepository listingRepository;
    private PlaceSearchService service;

    @BeforeEach
    void setUp() {
        regionRepository = mock(RegionRepository.class);
        universityRepository = mock(UniversityRepository.class);
        stationRepository = mock(SubwayStationRepository.class);
        stationLineRepository = mock(SubwayStationLineRepository.class);
        listingRepository = mock(ListingRepository.class);
        service = new PlaceSearchService(regionRepository, universityRepository, stationRepository, stationLineRepository,
                listingRepository);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static Region region(Long id, Long parentId, String code, String ko, String en) {
        return Region.builder().id(id).parentId(parentId).code(code).nameKo(ko).nameEn(en).build();
    }

    private static University university(Long id, String code, String ko, String en, Long regionId) {
        return University.builder().id(id).code(code).nameKo(ko).nameEn(en).addressRoad("주소")
                .lat(new BigDecimal("37.5600000")).lng(new BigDecimal("126.9400000")).regionId(regionId).build();
    }

    private static SubwayStation station(Long id, String code, String ko, String en, Long regionId) {
        return SubwayStation.builder().id(id).code(code).nameKo(ko).nameEn(en)
                .lat(new BigDecimal("37.5550000")).lng(new BigDecimal("126.9360000")).regionId(regionId).build();
    }

    private void found(List<Region> districts, List<University> universities, List<SubwayStation> stations) {
        given(regionRepository.searchOpenDistricts(anyString(), anyString(), anyString(), any())).willReturn(districts);
        given(universityRepository.searchActive(anyString(), anyString(), anyString(), any())).willReturn(universities);
        given(stationRepository.searchActive(anyString(), anyString(), anyString(), any())).willReturn(stations);
    }

    private List<PlaceSearchResponse.Item> search(String q, int limit) {
        return service.search(q, limit).items();
    }

    @Test
    @DisplayName("빈 검색어(공백만 포함 · 전각 공백 포함)는 빈 목록이고 아무것도 조회하지 않는다")
    void blank_query_is_empty() {
        assertThat(search("   ", 20)).isEmpty();
        assertThat(search(null, 20)).isEmpty();
        assertThat(search("\u3000\u3000", 20)).isEmpty();
        verifyNoInteractions(regionRepository, universityRepository, stationRepository, stationLineRepository, listingRepository);
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 떼고 소문자로 — 50자까지만, LIKE 의 % · _ · ! 는 ! 로 이스케이프, 유형마다 10개")
    void query_is_normalised_escaped_and_capped() {
        found(List.of(), List.of(), List.of());

        search("  Sin%_!chon  ", 20);
        search("가".repeat(60), 20);

        ArgumentCaptor<String> exact = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contains = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(universityRepository, times(2)).searchActive(exact.capture(), prefix.capture(), contains.capture(), page.capture());
        assertThat(exact.getAllValues().get(0)).isEqualTo("sin%_!chon");
        assertThat(prefix.getAllValues().get(0)).isEqualTo("sin!%!_!!chon%");
        assertThat(contains.getAllValues().get(0)).isEqualTo("%sin!%!_!!chon%");
        assertThat(exact.getAllValues().get(1)).hasSize(50);
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        verify(regionRepository).searchOpenDistricts(eq("sin%_!chon"), eq("sin!%!_!!chon%"), eq("%sin!%!_!!chon%"), any());
        verify(stationRepository).searchActive(eq("sin%_!chon"), eq("sin!%!_!!chon%"), eq("%sin!%!_!!chon%"), any());

        ArgumentCaptor<Pageable> regionPage = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> stationPage = ArgumentCaptor.forClass(Pageable.class);
        verify(regionRepository, times(2)).searchOpenDistricts(anyString(), anyString(), anyString(), regionPage.capture());
        verify(stationRepository, times(2)).searchActive(anyString(), anyString(), anyString(), stationPage.capture());
        assertThat(regionPage.getAllValues()).extracting(Pageable::getPageNumber, Pageable::getPageSize)
                .containsOnly(tuple(0, 10));
        assertThat(stationPage.getAllValues()).extracting(Pageable::getPageNumber, Pageable::getPageSize)
                .containsOnly(tuple(0, 10));
    }

    // strip() 은 전각 공백(U+3000)도 떼지만 trim() 은 못 뗀다 — 남으면 앞뒤에 공백이 붙은 검색어로 찾아 0건이 된다.
    @Test
    @DisplayName("전각 공백(U+3000)도 앞뒤에서 뗀다")
    void full_width_spaces_are_stripped() {
        found(List.of(), List.of(), List.of());

        search("\u3000Sin\u3000", 20);

        verify(regionRepository).searchOpenDistricts(eq("sin"), eq("sin%"), eq("%sin%"), any());
        verify(universityRepository).searchActive(eq("sin"), eq("sin%"), eq("%sin%"), any());
        verify(stationRepository).searchActive(eq("sin"), eq("sin%"), eq("%sin%"), any());
    }

    // 포함(지역) < 앞부분(대학) < 완전(역) — 순위가 유형 순서보다 먼저다. 같은 순위면 지역 → 대학교 → 역.
    @Test
    @DisplayName("순위가 먼저, 같은 순위면 지역 → 대학교 → 역, 같은 유형이면 저장소 순서")
    void rank_then_type_then_repository_order() {
        found(List.of(region(20L, 1L, "11440", "신촌마을구", "New Sinchon-gu"), region(21L, 1L, "11410", "서대문구", "Sinchon-gu")),
                List.of(university(30L, "0000149", "신촌대학교", "Sinchon University", null),
                        university(31L, "0000150", "새신촌대학교", "New Sinchon University", null)),
                List.of(station(40L, "ST0065", "신촌역", "Sinchon", null)));
        given(regionRepository.findByIdIn(any())).willReturn(List.of(region(1L, null, "11", "서울특별시", "Seoul")));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<PlaceSearchResponse.Item> items = search("sinchon", 20);

        assertThat(items).extracting(PlaceSearchResponse.Item::type, PlaceSearchResponse.Item::code).containsExactly(
                tuple(PlaceType.STATION, "ST0065"),
                tuple(PlaceType.REGION, "11410"),
                tuple(PlaceType.UNIVERSITY, "0000149"),
                tuple(PlaceType.REGION, "11440"),
                tuple(PlaceType.UNIVERSITY, "0000150"));
    }

    @Test
    @DisplayName("limit 만큼 자르고, 1 ~ 50 밖이면 끝값으로 맞춘다")
    void limit_is_clamped() {
        List<University> many = IntStream.range(0, 10)
                .mapToObj(i -> university((long) i, String.format("%07d", i), "대학교" + i, "University " + i, null)).toList();
        found(List.of(), many, List.of());

        assertThat(search("대학", 3)).hasSize(3);
        assertThat(search("대학", 0)).hasSize(1);
        assertThat(search("대학", 1_000)).hasSize(10);
        assertThat(search("대학", 3)).extracting(PlaceSearchResponse.Item::code).containsExactly("0000000", "0000001", "0000002");
    }

    @Test
    @DisplayName("지역 — 부제는 시도 이름, 좌표는 그 구 공개 매물 평균(없으면 null), regionCode 는 자기 코드 · 언어 규칙")
    void region_items() {
        found(List.of(region(21L, 1L, "11410", "서대문구", "Seodaemun-gu"), region(22L, 1L, "11440", "마포구", "Mapo-gu")),
                List.of(), List.of());
        given(regionRepository.findByIdIn(List.of(1L))).willReturn(List.of(region(1L, null, "11", "서울특별시", "Seoul")));
        given(listingRepository.aggregatePublicPricesByRegion(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(), any(), eq(List.of(21L, 22L))))
                .willReturn(List.of(new RegionPriceStat(21L, 3, 1.0, 2.0, 37.5612, 126.9367)));

        LocaleContextHolder.setLocale(Locale.KOREA);
        List<PlaceSearchResponse.Item> ko = search("구", 20);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<PlaceSearchResponse.Item> en = search("구", 20);

        assertThat(ko.get(0).name()).isEqualTo("서대문구");
        assertThat(ko.get(0).subtitle()).isEqualTo("서울특별시");
        assertThat(ko.get(0).lat()).isEqualByComparingTo("37.5612");
        assertThat(ko.get(0).lng()).isEqualByComparingTo("126.9367");
        assertThat(ko.get(0).regionCode()).isEqualTo("11410");
        assertThat(ko.get(1).lat()).isNull();
        assertThat(ko.get(1).lng()).isNull();
        assertThat(en.get(0).name()).isEqualTo("Seodaemun-gu");
        assertThat(en.get(0).subtitle()).isEqualTo("Seoul");
    }

    // 지역 좌표는 찾은 구만 묶는다 — 전국 공개 매물을 다 묶지 않는다. 구가 없으면 묻지도 않는다.
    @Test
    @DisplayName("지역이 없으면 매물 평균을 조회하지 않는다")
    void no_centre_query_without_regions() {
        found(List.of(), List.of(university(30L, "0000149", "연세대학교", "Yonsei University", null)), List.of());

        search("연세", 20);

        verifyNoInteractions(listingRepository);
    }

    @Test
    @DisplayName("대학교 — 부제는 캠퍼스 · 구(구는 닫혀 있어도), regionCode 는 열린 구만")
    void university_items() {
        found(List.of(), List.of(university(30L, "0000048", "가톨릭대학교", "The Catholic University of Korea", 65L),
                university(31L, "0000149", "연세대학교", "Yonsei University", 41L)), List.of());
        given(universityRepository.searchActive(anyString(), anyString(), anyString(), any())).willReturn(List.of(
                University.builder().id(30L).code("0000048").nameKo("가톨릭대학교").nameEn("The Catholic University of Korea")
                        .campusNameKo("제2캠퍼스").campusNameEn("Campus 2").addressRoad("주소")
                        .lat(new BigDecimal("37.5")).lng(new BigDecimal("127.0")).regionId(65L).build(),
                university(31L, "0000149", "연세대학교", "Yonsei University", 41L)));
        // 65 = 닫힌 구(서초구) · 41 = 열린 구(서대문구)
        given(regionRepository.findByIdIn(any())).willReturn(List.of(region(65L, 1L, "11650", "서초구", "Seocho-gu"),
                region(41L, 1L, "11410", "서대문구", "Seodaemun-gu")));
        given(regionRepository.findByIdInAndIsActiveTrue(any())).willAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.contains(1L) ? List.of(region(1L, null, "11", "서울특별시", "Seoul"))
                    : List.of(region(41L, 1L, "11410", "서대문구", "Seodaemun-gu"));
        });

        LocaleContextHolder.setLocale(Locale.KOREAN);
        List<PlaceSearchResponse.Item> ko = search("대학교", 20);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<PlaceSearchResponse.Item> en = search("대학교", 20);

        assertThat(ko).extracting(PlaceSearchResponse.Item::subtitle).containsExactly("제2캠퍼스 · 서초구", "서대문구");
        assertThat(ko).extracting(PlaceSearchResponse.Item::regionCode).containsExactly(null, "11410");
        assertThat(en).extracting(PlaceSearchResponse.Item::subtitle).containsExactly("Campus 2 · Seocho-gu", "Seodaemun-gu");
        assertThat(en.get(1).name()).isEqualTo("Yonsei University");
        assertThat(ko.get(1).lat()).isEqualByComparingTo("37.56");
    }

    @Test
    @DisplayName("역 — 부제는 노선 이름들(저장소 순서), 노선이 없으면 null, regionCode 는 열린 구만")
    void station_items() {
        found(List.of(), List.of(), List.of(station(70L, "ST0070", "왕십리역", "Wangsimni", 41L),
                station(71L, "ST0071", "새역", "New", null)));
        given(stationLineRepository.findLinesOf(List.of(70L, 71L))).willReturn(List.of(
                new SubwayStationLineRow(70L, "SEL-2", "2호선", "Line 2", "#00A84D"),
                new SubwayStationLineRow(70L, "SEL-5", "5호선", "Line 5", "#996CAC")));
        given(regionRepository.findByIdIn(any())).willReturn(List.of(region(41L, 1L, "11200", "성동구", "Seongdong-gu")));
        given(regionRepository.findByIdInAndIsActiveTrue(any())).willAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.contains(1L) ? List.of(region(1L, null, "11", "서울특별시", "Seoul"))
                    : List.of(region(41L, 1L, "11200", "성동구", "Seongdong-gu"));
        });

        LocaleContextHolder.setLocale(Locale.KOREAN);
        List<PlaceSearchResponse.Item> ko = search("역", 20);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<PlaceSearchResponse.Item> en = search("역", 20);

        assertThat(ko).extracting(PlaceSearchResponse.Item::subtitle).containsExactly("2호선 · 5호선", null);
        assertThat(ko).extracting(PlaceSearchResponse.Item::regionCode).containsExactly("11200", null);
        assertThat(en).extracting(PlaceSearchResponse.Item::subtitle).containsExactly("Line 2 · Line 5", null);
        assertThat(en.get(0).name()).isEqualTo("Wangsimni");
    }
}
