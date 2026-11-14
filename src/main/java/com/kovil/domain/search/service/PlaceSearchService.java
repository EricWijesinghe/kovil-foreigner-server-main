package com.kovil.domain.search.service;

import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceSearchService {

    static final int MAX_LIMIT = 50;
    static final int PER_TYPE = 10;
    static final int MAX_QUERY_LENGTH = 50;

    private static final List<ListingStatus> VISIBLE = List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final RegionRepository regionRepository;
    private final UniversityRepository universityRepository;
    private final SubwayStationRepository stationRepository;
    private final SubwayStationLineRepository stationLineRepository;
    private final ListingRepository listingRepository;

    public PlaceSearchResponse search(String rawQuery, int rawLimit) {
        String query = normalize(rawQuery);
        if (query.isEmpty()) {
            return new PlaceSearchResponse(List.of());
        }
        int limit = Math.min(Math.max(rawLimit, 1), MAX_LIMIT);
        boolean korean = Locale.KOREAN.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage());
        String exact = query.toLowerCase(Locale.ROOT);
        String escaped = escapeLike(exact);
        String prefix = escaped + "%";
        String contains = "%" + escaped + "%";
        Pageable perType = PageRequest.of(0, PER_TYPE);

        List<Region> districts = regionRepository.searchOpenDistricts(exact, prefix, contains, perType);
        List<University> universities = universityRepository.searchActive(exact, prefix, contains, perType);
        List<SubwayStation> stations = stationRepository.searchActive(exact, prefix, contains, perType);

        Set<Long> placeRegionIds = Stream.concat(
                        universities.stream().map(University::getRegionId), stations.stream().map(SubwayStation::getRegionId))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Region> placeDistricts = placeRegionIds.isEmpty() ? Map.of() : byId(regionRepository.findByIdIn(placeRegionIds));
        Map<Long, Region> openDistricts = openDistrictsOf(placeRegionIds);
        Map<Long, Region> parents = districts.isEmpty() ? Map.of()
                : byId(regionRepository.findByIdIn(districts.stream().map(Region::getParentId).distinct().toList()));
        Map<Long, RegionPriceStat> centers = centersOf(districts);
        Map<Long, List<String>> lineNames = lineNamesOf(stations, korean);

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < districts.size(); i++) {
            Region region = districts.get(i);
            RegionPriceStat center = centers.get(region.getId());
            candidates.add(new Candidate(rankOf(exact, region.getNameKo(), region.getNameEn()), i, new PlaceSearchResponse.Item(
                    PlaceType.REGION, region.getCode(), nameOf(region, korean), nameOf(parents.get(region.getParentId()), korean),
                    center == null ? null : decimal(center.avgLat()), center == null ? null : decimal(center.avgLng()),
                    region.getCode())));
        }
        for (int i = 0; i < universities.size(); i++) {
            University university = universities.get(i);
            Region district = university.getRegionId() == null ? null : placeDistricts.get(university.getRegionId());
            Region open = university.getRegionId() == null ? null : openDistricts.get(university.getRegionId());
            String campus = korean ? university.getCampusNameKo() : university.getCampusNameEn();
            candidates.add(new Candidate(rankOf(exact, university.getNameKo(), university.getNameEn()), i, new PlaceSearchResponse.Item(
                    PlaceType.UNIVERSITY, university.getCode(), korean ? university.getNameKo() : university.getNameEn(),
                    join(Stream.of(campus, district == null || district.isRoot() ? null : nameOf(district, korean))),
                    university.getLat(), university.getLng(), open == null ? null : open.getCode())));
        }
        for (int i = 0; i < stations.size(); i++) {
            SubwayStation station = stations.get(i);
            Region open = station.getRegionId() == null ? null : openDistricts.get(station.getRegionId());
            candidates.add(new Candidate(rankOf(exact, station.getNameKo(), station.getNameEn()), i, new PlaceSearchResponse.Item(
                    PlaceType.STATION, station.getCode(), korean ? station.getNameKo() : station.getNameEn(),
                    join(lineNames.getOrDefault(station.getId(), List.of()).stream()), station.getLat(), station.getLng(),
                    open == null ? null : open.getCode())));
        }

        return new PlaceSearchResponse(candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::rank)
                        .thenComparing(candidate -> candidate.item().type())
                        .thenComparingInt(Candidate::index))
                .limit(limit)
                .map(Candidate::item)
                .toList());
    }

    private record Candidate(int rank, int index, PlaceSearchResponse.Item item) {
    }

    private static int rankOf(String exact, String nameKo, String nameEn) {
        return Math.min(rankOne(exact, nameKo), rankOne(exact, nameEn));
    }

    private static int rankOne(String exact, String name) {
        if (name == null) {
            return 2;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(exact) ? 0 : lower.startsWith(exact) ? 1 : 2;
    }

    private static String normalize(String raw) {
        String query = raw == null ? "" : raw.strip();
        if (query.codePointCount(0, query.length()) <= MAX_QUERY_LENGTH) {
            return query;
        }
        return query.substring(0, query.offsetByCodePoints(0, MAX_QUERY_LENGTH));
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private Map<Long, RegionPriceStat> centersOf(List<Region> districts) {
        if (districts.isEmpty()) {
            return Map.of();
        }
        return listingRepository.aggregatePublicPricesByRegion(VISIBLE, null,
                        List.of(SellingType.values()), List.of(RoomType.values()), List.of(FloorType.values()),
                        null, null, null, null, null, null, false, false, null, null,
                        null, null, null, null, districts.stream().map(Region::getId).toList())
                .stream()
                .collect(Collectors.toMap(RegionPriceStat::regionId, Function.identity()));
    }

    private Map<Long, Region> openDistrictsOf(Set<Long> regionIds) {
        if (regionIds.isEmpty()) {
            return Map.of();
        }
        List<Region> districts = regionRepository.findByIdInAndIsActiveTrue(regionIds).stream()
                .filter(region -> !region.isRoot())
                .toList();
        if (districts.isEmpty()) {
            return Map.of();
        }
        Set<Long> openParents = regionRepository.findByIdInAndIsActiveTrue(
                        districts.stream().map(Region::getParentId).distinct().toList())
                .stream().map(Region::getId).collect(Collectors.toSet());
        return districts.stream()
                .filter(region -> openParents.contains(region.getParentId()))
                .collect(Collectors.toMap(Region::getId, Function.identity()));
    }

    private Map<Long, List<String>> lineNamesOf(List<SubwayStation> stations, boolean korean) {
        if (stations.isEmpty()) {
            return Map.of();
        }
        return stationLineRepository.findLinesOf(stations.stream().map(SubwayStation::getId).toList()).stream()
                .collect(Collectors.groupingBy(SubwayStationLineRow::stationId,
                        Collectors.mapping(row -> korean ? row.nameKo() : row.nameEn(), Collectors.toList())));
    }

    private static Map<Long, Region> byId(List<Region> regions) {
        return regions.stream().collect(Collectors.toMap(Region::getId, Function.identity()));
    }

    private static String nameOf(Region region, boolean korean) {
        if (region == null) {
            return null;
        }
        return korean ? region.getNameKo() : region.getNameEn();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String join(Stream<String> parts) {
        String joined = parts.filter(Objects::nonNull).collect(Collectors.joining(" · "));
        return joined.isEmpty() ? null : joined;
    }
}