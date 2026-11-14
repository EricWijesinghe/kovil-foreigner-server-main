package com.kovil.domain.listing.service;

import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.region.service.AddressRegionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_REGION_INVALID;


@Component
@RequiredArgsConstructor
public class ListingRegionResolver {

    private final RegionRepository regionRepository;
    private final AddressRegionResolver addressRegionResolver;

    // 매물의 지역 = 주소의 시군구. 주소 글자로 정해지면 앱이 보낸 코드는 보지 않음 — 닫힌 시군구여도 그 지역으로 저장하고
    // 게시는 관문이 막음(지역이 다시 열리면 다시 저장하지 않아도 게시됨). 주소로 못 가를 때만 앱이 고른 코드(열린 지역만).
    public Long resolveIdForSave(String regionCode, String dongName, String addressJibun, String addressRoad,
                                 Long currentRegionId) {
        Region fromAddress = addressRegionResolver.regionOfAddress(dongName, addressJibun, addressRoad);
        if (fromAddress != null) {
            return fromAddress.getId();
        }
        return resolveIdForUpdate(regionCode, currentRegionId);
    }

    // 앱이 지역 칸을 잠글지 — 저장할 때 주소로 정해지는 매물이면 바꿀 수 없음.
    public boolean decidedByAddress(String dongName, String addressJibun, String addressRoad) {
        return addressRegionResolver.regionOfAddress(dongName, addressJibun, addressRoad) != null;
    }

    public Long resolveIdOrNull(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        return resolve(regionCode.trim()).getId();
    }

    public Long resolveIdForUpdate(String regionCode, Long currentRegionId) {
        if (regionCode != null && currentRegionId != null && regionCode.trim().equals(codeOf(currentRegionId))) {
            return currentRegionId;
        }
        return resolveIdOrNull(regionCode);
    }

    public boolean isPublishable(Long regionId) {
        if (regionId == null) {
            return false;
        }
        Optional<Region> region = regionRepository.findByIdInAndIsActiveTrue(List.of(regionId))
                .stream().findFirst();
        return region.isPresent() && !region.get().isRoot() && parentActive(region.get());
    }

    public String codeOf(Long regionId) {
        if (regionId == null) {
            return null;
        }
        return regionRepository.findByIdIn(List.of(regionId)).stream()
                .findFirst().map(Region::getCode).orElse(null);
    }

    public Map<Long, String> codesOf(Collection<Long> regionIds) {
        List<Long> ids = regionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return regionRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Region::getId, Region::getCode));
    }

    private Region resolve(String code) {
        Region region = regionRepository.findByCodeInAndIsActiveTrue(List.of(code)).stream()
                .findFirst()
                .orElseThrow(() -> new ListingException(LISTING_REGION_INVALID));
        if (region.isRoot() || !parentActive(region)) {
            throw new ListingException(LISTING_REGION_INVALID);
        }
        return region;
    }

    private boolean parentActive(Region region) {
        return region.getParentId() != null
                && !regionRepository.findByIdInAndIsActiveTrue(List.of(region.getParentId())).isEmpty();
    }
}