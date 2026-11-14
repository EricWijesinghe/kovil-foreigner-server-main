package com.kovil.domain.region.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/*
 * 주소 → 서비스 지역(시군구). 규칙 · 표는 DML 생성기 dml/tools/region_lookup.py 와 같은 값 — 어긋나면 매물과 대학교 · 역이 다른 지역에 앉음.
 *   0. 카카오 법정동 코드 앞 5자리와 같은 시군구 행. 개편 전 옛 코드면 아래로.
 *   1. 시도 = 별칭표(정식 · 옛 · 줄인 이름 — 카카오는 "전남"처럼 줄여 줌), 안 되면 지번 · 도로명 주소 첫 낱말.
 *   2. 그 시도의 시군구 중 이름이 같은 것: 일반구("성남시 분당구") → 그대로 → +시 · +군 → 옛 이름(인천 남구 → 미추홀구). 세종은 하나뿐.
 *   3. 그래도 없으면 읍면동 색인 — 그 시도(시를 알면 그 시의 일반구들) 안에서 한 시군구에만 있을 때만.
 *   4. 못 찾으면 null — 지어내지 않음(쪼개진 인천 옛 중구 · 서구인데 읍면동이 없는 경우 포함).
 * 대조 대상은 regions 전부(닫힌 곳 포함) — 닫힌 시군구도 코드를 주고 regionActive=false.
 */
@Component
public class AddressRegionResolver {

    static final String DONG_INDEX = "region/region_dongs.tsv";

    // '광주시' 는 경기도 광주시와 겹쳐 받지 않음.
    static final Map<String, String> SIDO = Map.ofEntries(
            Map.entry("서울특별시", "11"), Map.entry("부산광역시", "26"), Map.entry("대구광역시", "27"),
            Map.entry("인천광역시", "28"), Map.entry("대전광역시", "30"), Map.entry("울산광역시", "31"),
            Map.entry("세종특별자치시", "36"), Map.entry("경기도", "41"), Map.entry("충청북도", "43"),
            Map.entry("충청남도", "44"), Map.entry("경상북도", "47"), Map.entry("경상남도", "48"),
            Map.entry("제주특별자치도", "50"), Map.entry("강원특별자치도", "51"), Map.entry("전북특별자치도", "52"),
            Map.entry("전남광주통합특별시", "12"),
            Map.entry("광주광역시", "12"), Map.entry("전라남도", "12"), Map.entry("강원도", "51"),
            Map.entry("전라북도", "52"), Map.entry("제주도", "50"),
            Map.entry("서울", "11"), Map.entry("부산", "26"), Map.entry("대구", "27"), Map.entry("인천", "28"),
            Map.entry("대전", "30"), Map.entry("울산", "31"), Map.entry("세종", "36"), Map.entry("경기", "41"),
            Map.entry("충북", "43"), Map.entry("충남", "44"), Map.entry("경북", "47"), Map.entry("경남", "48"),
            Map.entry("제주", "50"), Map.entry("강원", "51"), Map.entry("전북", "52"), Map.entry("광주", "12"),
            Map.entry("전남", "12"),
            Map.entry("서울시", "11"), Map.entry("부산시", "26"), Map.entry("대구시", "27"), Map.entry("인천시", "28"),
            Map.entry("대전시", "30"), Map.entry("울산시", "31"));

    // (시도 코드, 옛 이름) → 지금 이름.
    static final Map<String, String> RENAMED = Map.of("28 남구", "미추홀구", "28 동구", "제물포구");

    private static final String SEJONG = "36";
    private static final Pattern SPLITTER = Pattern.compile("[\\s(),]+");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\d[\\d-]*$");
    private static final Pattern DONG = Pattern.compile("[가-힣0-9]+(동|읍|면|가)");

    private final RegionRepository regionRepository;
    // 시도 코드 → 읍면동 → 시군구 코드들. 같은 이름 읍면동이 여러 시군구에 있다.
    private final Map<String, Map<String, Set<String>>> dongIndex;

    public AddressRegionResolver(RegionRepository regionRepository) {
        this.regionRepository = regionRepository;
        this.dongIndex = loadDongIndex();
    }

    public record Resolved(String regionCode, Boolean regionActive) {
        static final Resolved NONE = new Resolved(null, null);
    }

    // 검색 한 번에 regions 를 한 번 읽고, 결과 행마다 그 안에서 판정.
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        return new Snapshot(regionRepository.findAll());
    }

    // 매물 저장 — 벤더 법정동 코드 없이 저장되는 주소 글자(동 · 지번 · 도로명)만으로 판정. 닫힌 시군구여도 그 행, 못 가르면 null.
    @Transactional(readOnly = true)
    public Region regionOfAddress(String dongName, String jibunAddress, String roadAddress) {
        Snapshot snapshot = snapshot();
        String code = snapshot.codeOf(null, null, null, dongName, jibunAddress, roadAddress);
        return code == null ? null : snapshot.byCode.get(code);
    }

    public final class Snapshot {

        private final Map<String, Region> byCode;
        private final Map<Long, Region> byId;
        private final Map<String, Map<String, String>> districtsBySido = new HashMap<>();

        Snapshot(List<Region> regions) {
            this.byCode = regions.stream().collect(Collectors.toMap(Region::getCode, Function.identity()));
            this.byId = regions.stream().collect(Collectors.toMap(Region::getId, Function.identity()));
            for (Region region : regions) {
                Region parent = region.isRoot() ? null : byId.get(region.getParentId());
                if (parent != null) {
                    districtsBySido.computeIfAbsent(parent.getCode(), key -> new HashMap<>())
                            .put(region.getNameKo(), region.getCode());
                }
            }
        }

        public Resolved resolve(String legalDongCode, String sido, String sigugun, String dongName,
                                String jibunAddress, String roadAddress) {
            String code = codeOf(legalDongCode, sido, sigugun, dongName, jibunAddress, roadAddress);
            return code == null ? Resolved.NONE : new Resolved(code, isPublishable(byCode.get(code)));
        }

        private String codeOf(String legalDongCode, String sidoName, String sigugun, String dongName,
                              String jibunAddress, String roadAddress) {
            if (legalDongCode != null && legalDongCode.strip().length() >= 5) {
                Region hit = byCode.get(legalDongCode.strip().substring(0, 5));
                if (hit != null && !hit.isRoot()) {
                    return hit.getCode();
                }
            }

            List<String> jibun = words(jibunAddress);
            List<String> road = words(roadAddress);
            String sido = firstNonNull(sidoName == null ? null : SIDO.get(sidoName.strip()),
                    jibun.isEmpty() ? null : SIDO.get(jibun.get(0)),
                    road.isEmpty() ? null : SIDO.get(road.get(0)));
            if (sido == null) {
                return null;
            }
            Map<String, String> kids = districtsBySido.getOrDefault(sido, Map.of());
            if (SEJONG.equals(sido) && kids.size() == 1) {
                return kids.values().iterator().next();
            }

            List<String> names = sigugun != null && !sigugun.isBlank() ? words(sigugun)
                    : !jibun.isEmpty() ? afterSido(jibun) : afterSido(road);
            String first = names.isEmpty() ? "" : names.get(0);
            if (names.size() >= 2 && kids.containsKey(first + " " + names.get(1))) {
                return kids.get(first + " " + names.get(1));
            }
            if (!first.isEmpty()) {
                for (String name : Arrays.asList(first, first + "시", first + "군", RENAMED.get(sido + " " + first))) {
                    if (name != null && kids.containsKey(name)) {
                        return kids.get(name);
                    }
                }
            }

            // 일반구가 있는 시인데 구가 없는 주소 — 그 시의 일반구 안에서만 읍면동으로 가른다.
            Collection<String> allowed = kids.values();
            if (first.endsWith("시")) {
                List<String> cityDistricts = kids.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(first + " ")).map(Map.Entry::getValue).toList();
                if (!cityDistricts.isEmpty()) {
                    allowed = cityDistricts;
                }
            }
            List<String> dongs = new ArrayList<>(dongWords(dongName));
            dongs.addAll(dongWords(jibunAddress));
            dongs.addAll(dongWords(roadAddress));
            return byDong(sido, dongs, new HashSet<>(allowed));
        }

        private String byDong(String sido, List<String> dongs, Set<String> allowed) {
            Map<String, Set<String>> index = dongIndex.getOrDefault(sido, Map.of());
            Set<String> found = new HashSet<>();
            for (String dong : dongs) {
                Set<String> hits = new HashSet<>(index.getOrDefault(dong, Set.of()));
                hits.retainAll(allowed);
                if (hits.size() == 1) {
                    found.addAll(hits);
                }
            }
            return found.size() == 1 ? found.iterator().next() : null;
        }

        // 매물 게시 관문과 같은 판정(ListingRegionResolver.isPublishable) — 활성 · 시도 아님 · 부모 시도 활성.
        private boolean isPublishable(Region region) {
            if (region == null || region.isRoot() || !Boolean.TRUE.equals(region.getIsActive())) {
                return false;
            }
            Region parent = byId.get(region.getParentId());
            return parent != null && Boolean.TRUE.equals(parent.getIsActive());
        }
    }

    private static List<String> words(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.strip().split("\\s+"));
    }

    private static List<String> afterSido(List<String> jibun) {
        return !jibun.isEmpty() && SIDO.containsKey(jibun.get(0)) ? jibun.subList(1, jibun.size()) : jibun;
    }

    // 낱말과 괄호 속 낱말 가운데 …동 · …읍 · …면 · …가 로 끝나는 것(번지는 뗌).
    private static List<String> dongWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : SPLITTER.split(text)) {
            String word = TRAILING_NUMBER.matcher(token).replaceAll("");
            if (DONG.matcher(word).matches()) {
                out.add(word);
            }
        }
        return out;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    // 시작 때 한 번. 자원이 없으면 기동을 막는다 — 조용히 비면 읍면동 판정이 늘 실패해 이름이 같은 구를 못 가른다.
    private static Map<String, Map<String, Set<String>>> loadDongIndex() {
        Map<String, Map<String, Set<String>>> index = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DONG_INDEX).getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split("\t");
                if (cells.length == 3) {
                    index.computeIfAbsent(cells[0], key -> new HashMap<>())
                            .computeIfAbsent(cells[1], key -> new HashSet<>()).add(cells[2]);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("읍면동 색인을 읽지 못함: " + DONG_INDEX, e);
        }
        return index;
    }
}
