package com.kovil.domain.transit.service;

import com.kovil.common.exception.MasterException;
import com.kovil.common.util.MapCenter;
import com.kovil.domain.transit.dto.response.SubwayStationResponse;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.repository.SubwayStationLineRepository;
import com.kovil.domain.transit.repository.SubwayStationLineRow;
import com.kovil.domain.transit.repository.SubwayStationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubwayStationQueryService {

    static final int MAX_RESULTS = 300;
    private static final int MAX_SCALE = 15;
    private static final BigDecimal LAT_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LNG_LIMIT = BigDecimal.valueOf(180);

    private final SubwayStationRepository stationRepository;
    private final SubwayStationLineRepository stationLineRepository;

    public List<SubwayStationResponse> findWithin(BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng) {
        requireSane(swLat, LAT_LIMIT);
        requireSane(neLat, LAT_LIMIT);
        requireSane(swLng, LNG_LIMIT);
        requireSane(neLng, LNG_LIMIT);

        // 상한에 닿으면 가운데에서 먼 역부터 빠짐. 코드 순으로 자르면 화면 한쪽이 통째로 빔.
        MapCenter center = MapCenter.of(swLat, swLng, neLat, neLng);
        List<SubwayStation> stations = stationRepository.findActiveWithin(swLat, swLng, neLat, neLng,
                center.lat(), center.lng(), center.lngWeight(), PageRequest.of(0, MAX_RESULTS));
        if (stations.isEmpty()) {
            return List.of();
        }
        boolean korean = Locale.KOREAN.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage());
        Map<Long, List<SubwayStationResponse.Line>> linesByStation = stationLineRepository
                .findLinesOf(stations.stream().map(SubwayStation::getId).toList()).stream()
                .collect(Collectors.groupingBy(SubwayStationLineRow::stationId, Collectors.mapping(
                        row -> new SubwayStationResponse.Line(row.code(), korean ? row.nameKo() : row.nameEn(), row.color()),
                        Collectors.toList())));

        return stations.stream()
                .map(s -> new SubwayStationResponse(s.getCode(), korean ? s.getNameKo() : s.getNameEn(),
                        s.getLat(), s.getLng(), linesByStation.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    private static void requireSane(BigDecimal value, BigDecimal limit) {
        if (value.scale() > MAX_SCALE || value.abs().compareTo(limit) > 0) {
            throw new MasterException(INVALID_REQUEST);
        }
    }
}