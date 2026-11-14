package com.kovil.domain.university.service;

import com.kovil.common.exception.MasterException;
import com.kovil.common.util.MapCenter;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.university.dto.response.UniversityResponse;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UniversityQueryService {

    static final int MAX_RESULTS = 200;
    private static final int MAX_SCALE = 15;
    private static final BigDecimal LAT_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LNG_LIMIT = BigDecimal.valueOf(180);

    private final UniversityRepository universityRepository;
    private final RegionRepository regionRepository;

    public List<UniversityResponse> findWithin(BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng) {
        requireSane(swLat, LAT_LIMIT);
        requireSane(neLat, LAT_LIMIT);
        requireSane(swLng, LNG_LIMIT);
        requireSane(neLng, LNG_LIMIT);

        // 상한에 닿으면 가운데에서 먼 학교부터 빠짐. 코드 순으로 자르면 화면 한쪽이 통째로 빔.
        MapCenter center = MapCenter.of(swLat, swLng, neLat, neLng);
        List<University> found = universityRepository.findActiveWithin(swLat, swLng, neLat, neLng,
                center.lat(), center.lng(), center.lngWeight(), PageRequest.of(0, MAX_RESULTS));
        Map<Long, String> regionCodes = openRegionCodesOf(found);
        boolean korean = Locale.KOREAN.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage());

        return found.stream()
                .map(u -> new UniversityResponse(
                        u.getCode(),
                        korean ? u.getNameKo() : u.getNameEn(),
                        korean ? u.getShortNameKo() : u.getShortNameEn(),
                        korean ? u.getCampusNameKo() : u.getCampusNameEn(),
                        u.getLat(), u.getLng(),
                        u.getRegionId() == null ? null : regionCodes.get(u.getRegionId())))
                .toList();
    }

    private Map<Long, String> openRegionCodesOf(List<University> universities) {
        List<Long> ids = universities.stream().map(University::getRegionId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Region> districts = regionRepository.findByIdInAndIsActiveTrue(ids).stream()
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
                .collect(Collectors.toMap(Region::getId, Region::getCode));
    }

    private static void requireSane(BigDecimal value, BigDecimal limit) {
        if (value.scale() > MAX_SCALE || value.abs().compareTo(limit) > 0) {
            throw new MasterException(INVALID_REQUEST);
        }
    }
}