package com.kovil.domain.listing.service;

import com.kovil.domain.listing.dto.request.ListingPolygon;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.request.SavedAreaCreateRequest;
import com.kovil.domain.listing.dto.request.SavedAreaFilters;
import com.kovil.domain.listing.dto.response.SavedAreaResponse;
import com.kovil.domain.listing.entity.SavedArea;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.SavedAreaRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_POLYGON_INVALID;
import static com.kovil.domain.listing.exception.ListingErrorCode.SAVED_AREA_LIMIT_EXCEEDED;
import static com.kovil.domain.listing.exception.ListingErrorCode.SAVED_AREA_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavedAreaService {

    static final int LIMIT_PER_USER = 20;
    private static final int NAME_MAX = 20;
    private static final int FILTERS_MAX = 2_000;

    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final SavedAreaRepository savedAreaRepository;
    private final ListingRegionResolver regionResolver;
    private final JsonMapper jsonMapper;

    public List<SavedAreaResponse> findMine(String userUuid) {
        Long userId = userQueryService.getActiveUser(userUuid).getId();
        return savedAreaRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(area -> SavedAreaResponse.of(area, jsonMapper.readValue(area.getFilters(), SavedAreaFilters.class)))
                .toList();
    }

    @Transactional
    public SavedAreaResponse save(String userUuid, SavedAreaCreateRequest request) {
        userRepository.findIdByUuidForUpdate(userUuid);
        User user = userQueryService.getActiveUser(userUuid);

        String name = request.name().strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new ListingException(INVALID_REQUEST);
        }
        ListingPolygon polygon = ListingPolygon.parseOrNull(request.polygon());
        if (polygon == null) {
            throw new ListingException(LISTING_POLYGON_INVALID);
        }
        SavedAreaFilters filters = request.filters() == null ? SavedAreaFilters.NONE : request.filters();
        requireUsable(filters);

        if (savedAreaRepository.countByUserId(user.getId()) >= LIMIT_PER_USER) {
            throw new ListingException(SAVED_AREA_LIMIT_EXCEEDED);
        }
        String placeLabel = request.placeLabel() == null || request.placeLabel().isBlank()
                ? null : request.placeLabel().strip();
        // 칸(2,000자)을 넘기면 DB 가 500 으로 거절한다 — 그 전에 400 으로 막는다.
        String filtersJson = jsonMapper.writeValueAsString(filters);
        if (filtersJson.length() > FILTERS_MAX) {
            throw new ListingException(INVALID_REQUEST);
        }
        SavedArea saved = savedAreaRepository.save(SavedArea.builder()
                .userId(user.getId())
                .name(name)
                .placeLabel(placeLabel)
                .polygon(polygon.toParam())
                .filters(filtersJson)
                .build());
        return SavedAreaResponse.of(saved, filters);
    }

    @Transactional
    public void delete(String userUuid, String savedAreaUuid) {
        Long userId = userQueryService.getActiveUser(userUuid).getId();
        if (savedAreaRepository.deleteByUuidAndUserId(savedAreaUuid, userId) == 0) {
            throw new ListingException(SAVED_AREA_NOT_FOUND);
        }
    }

    @Transactional
    public void purgeOnWithdrawal(Long userId) {
        savedAreaRepository.deleteAllByUserId(userId);
    }

    private void requireUsable(SavedAreaFilters filters) {
        new ListingSearchCondition(filters.category(), filters.sellingTypes(), filters.roomTypes(), filters.floorTypes(),
                filters.depositMin(), filters.depositMax(), filters.rentMin(), filters.rentMax(), filters.areaMin(), filters.areaMax(),
                Boolean.TRUE.equals(filters.canParking()), Boolean.TRUE.equals(filters.shortLease()), filters.residentRegistration(),
                regionResolver.resolveIdOrNull(filters.regionCode()), null, null, null, null, null);
    }
}