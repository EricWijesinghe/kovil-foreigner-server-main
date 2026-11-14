package com.kovil.domain.region.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/*
 * 주소 → 시군구 판정. 지역 트리는 진짜 장부(dml/tools/regions.tsv — DML 생성기 출력)로 세운다. 읍면동 색인은 클래스패스 자원.
 * 경우는 BE_REQUEST 가 짚은 깨지는 자리: 카카오 줄인 시도 · 전남광주 통합 · 인천 개편(이름 바뀜 · 쪼개짐) · 일반구 · 같은 이름 구.
 */
class AddressRegionResolverTest {

    private RegionRepository regionRepository;
    private final List<Region> regions = new ArrayList<>();
    private final Map<String, Region> byCode = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("dml/tools/regions.tsv"), StandardCharsets.UTF_8);
        long id = 1;
        Map<String, Long> ids = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            ids.put(cells[0], id++);
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            Region region = Region.builder().parentId(cells[1].isEmpty() ? null : ids.get(cells[1]))
                    .code(cells[0]).nameKo(cells[2]).nameEn(cells[3]).build();
            ReflectionTestUtils.setField(region, "id", ids.get(cells[0]));
            regions.add(region);
            byCode.put(cells[0], region);
        }
        regionRepository = mock(RegionRepository.class);
        given(regionRepository.findAll()).willReturn(regions);
    }

    private AddressRegionResolver.Resolved resolve(String legalDongCode, String sido, String sigugun, String dongName,
                                                   String jibun, String road) {
        return new AddressRegionResolver(regionRepository).snapshot()
                .resolve(legalDongCode, sido, sigugun, dongName, jibun, road);
    }

    private void close(String code) {
        ReflectionTestUtils.setField(byCode.get(code), "isActive", false);
    }

    @Test
    @DisplayName("가짜 지오코더 3건 — 전라남도 나주시 · 서울특별시 광진구 · 경기도 성남시 분당구")
    void fake_samples() {
        assertThat(resolve(null, "전라남도", "나주시", "빛가람동", "전라남도 나주시 빛가람동 235-1", null).regionCode())
                .isEqualTo("12170");
        assertThat(resolve(null, "서울특별시", "광진구", "자양동", "서울특별시 광진구 자양동 227-7", null).regionCode())
                .isEqualTo("11215");
        assertThat(resolve(null, "경기도", "성남시 분당구", "정자동", "경기도 성남시 분당구 정자동 178-1", null).regionCode())
                .isEqualTo("41135");
    }

    @Test
    @DisplayName("카카오 줄인 시도 \"전남\" · \"광주\"(옛 광역시)는 전남광주 12 아래로 간다")
    void kakao_short_sido_names_go_under_the_merged_province() {
        assertThat(resolve(null, "전남", "나주시", null, "전남 나주시 빛가람동 235-1", null).regionCode()).isEqualTo("12170");
        assertThat(resolve(null, "광주", "동구", null, "광주 동구 충장로1가 1", null).regionCode()).isEqualTo("12210");
    }

    @Test
    @DisplayName("인천 옛 남구는 미추홀구다")
    void incheon_renamed_district() {
        assertThat(resolve(null, "인천", "남구", null, "인천 남구 주안동 1", null).regionCode()).isEqualTo("28177");
    }

    @Test
    @DisplayName("쪼개진 인천 옛 중구는 읍면동으로 새 구를 가르고, 읍면동이 없으면 null 이다")
    void incheon_split_district_needs_a_dong() {
        assertThat(resolve(null, "인천", "중구", "운서동", "인천 중구 운서동 2850", null).regionCode()).isEqualTo("28155");
        assertThat(resolve(null, "인천", "중구", "신흥동1가", "인천 중구 신흥동1가 1", null).regionCode()).isEqualTo("28125");
        AddressRegionResolver.Resolved unknown = resolve(null, "인천", "중구", null, null, "인천 중구 공항로 272");
        assertThat(unknown.regionCode()).isNull();
        assertThat(unknown.regionActive()).isNull();
    }

    @Test
    @DisplayName("같은 이름 구는 시도로 가른다 — 서울 중구 · 부산 중구")
    void same_district_name_in_different_provinces() {
        assertThat(resolve(null, "서울", "중구", null, null, null).regionCode()).isEqualTo("11140");
        assertThat(resolve(null, "부산광역시", "중구", null, null, null).regionCode()).isEqualTo("26110");
    }

    // 정자동은 수원 장안구에도 성남 분당구에도 있다 — 시를 알면 그 시의 일반구 안에서만 찾는다.
    @Test
    @DisplayName("일반구가 있는 시인데 구가 없으면 그 시 안에서 읍면동으로 가른다")
    void city_without_district_uses_the_dong_within_that_city() {
        assertThat(resolve(null, "경기", "성남시", "정자동", "경기 성남시 정자동 178-1", null).regionCode()).isEqualTo("41135");
        assertThat(resolve(null, "경기", null, "정자동", null, null).regionCode())
                .as("시를 모르면 두 시군구에 있어 가를 수 없다").isNull();
    }

    /*
     * ★ 매물 저장은 벤더 법정동 코드 없이 저장되는 주소 글자만으로 다시 판정한다(PO 확정 2026-10-23) — 앱이 보낸 지역 코드를 믿지 않으려고.
     *   닫힌 시군구도 그 행을 준다(저장은 하고 게시만 막는다). 못 가르면 null 이라 그때만 앱이 고른 값을 쓴다.
     */
    @Test
    @DisplayName("매물 주소 글자로 판정 — 지번 · 도로명 · 동, 닫혀도 그 행, 못 가르면 null")
    void region_of_a_stored_listing_address() {
        AddressRegionResolver resolver = new AddressRegionResolver(regionRepository);

        assertThat(resolver.regionOfAddress(null, "전남광주통합특별시 북구 임동 887 S-클래스 더 제니스 오피스텔",
                "전남광주통합특별시 북구 유동길 48 S-클래스 더 제니스 오피스텔").getCode()).isEqualTo("12300");
        assertThat(resolver.regionOfAddress("정자동", "경기 성남시 정자동 178-1", null).getCode()).isEqualTo("41135");
        assertThat(resolver.regionOfAddress(null, null, "서울특별시 관악구 관악로 1").getCode()).isEqualTo("11620");

        close("11620");
        assertThat(resolver.regionOfAddress(null, "서울특별시 관악구 봉천동 1", null).getCode())
                .as("닫힌 시군구도 그 행").isEqualTo("11620");

        assertThat(resolver.regionOfAddress(null, "인천 중구 공항로 272", null)).as("쪼개진 옛 구 · 동 없음").isNull();
        assertThat(resolver.regionOfAddress(null, "어딘가 1", null)).isNull();
    }

    @Test
    @DisplayName("카카오 법정동 코드 앞 5자리가 시군구 행이면 이름보다 먼저 그것이다")
    void legal_dong_code_goes_first() {
        assertThat(resolve("4113510900", "경기", "엉뚱한구", null, null, null).regionCode()).isEqualTo("41135");
    }

    @Test
    @DisplayName("법정동 코드가 개편 전 옛 코드면 이름 규칙으로 떨어진다")
    void stale_legal_dong_code_falls_back_to_names() {
        assertThat(resolve("4617010300", "전남", "나주시", "빛가람동", null, null).regionCode()).isEqualTo("12170");
    }

    @Test
    @DisplayName("세종은 시군구가 하나뿐이라 바로 그것이다")
    void sejong_has_one_district() {
        assertThat(resolve(null, "세종특별자치시", null, null, "세종특별자치시 한누리대로 2130", null).regionCode())
                .isEqualTo("36110");
    }

    @Test
    @DisplayName("시도 칸이 없으면 지번 · 도로명 주소의 첫 낱말로 시도를 찾는다")
    void sido_from_the_address_text() {
        assertThat(resolve(null, null, null, null, null, "서울특별시 광진구 아차산로 200").regionCode()).isEqualTo("11215");
        assertThat(resolve(null, null, null, null, "서울특별시 광진구 자양동 227-7", null).regionCode()).isEqualTo("11215");
    }

    @Test
    @DisplayName("열린 시군구는 regionActive=true, 닫힌 시군구 · 닫힌 시도 아래는 코드는 주되 false")
    void active_flag_follows_the_publish_gate() {
        assertThat(resolve(null, "서울특별시", "광진구", null, null, null).regionActive()).isTrue();

        close("11215");
        AddressRegionResolver.Resolved closed = resolve(null, "서울특별시", "광진구", null, null, null);
        assertThat(closed.regionCode()).isEqualTo("11215");
        assertThat(closed.regionActive()).isFalse();

        close("26");
        AddressRegionResolver.Resolved underClosedSido = resolve(null, "부산", "중구", null, null, null);
        assertThat(underClosedSido.regionCode()).isEqualTo("26110");
        assertThat(underClosedSido.regionActive()).isFalse();
    }

    @Test
    @DisplayName("모르는 시도면 null — 지어내지 않는다")
    void unknown_sido_is_null() {
        assertThat(resolve(null, "어딘가도", "어딘가시", null, "어딘가도 어딘가시 1", null).regionCode()).isNull();
    }

    @Test
    @DisplayName("읍면동 색인 자원이 실려 있다 — 비면 같은 이름 구를 못 가른다")
    void dong_index_is_loaded() {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Set<String>>> index = (Map<String, Map<String, Set<String>>>)
                ReflectionTestUtils.getField(new AddressRegionResolver(regionRepository), "dongIndex");
        assertThat(index).isNotNull();
        assertThat(index.values().stream().mapToInt(Map::size).sum()).isGreaterThan(4_000);
    }
}
