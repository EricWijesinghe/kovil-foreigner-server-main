package com.kovil.domain.region.service;

import com.kovil.domain.region.dto.response.RegionResponse;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionQueryService {

    private final RegionRepository regionRepository;

    public List<RegionResponse> findActiveRegionTree() {
        return findActiveRegionTree(LocaleContextHolder.getLocale().getLanguage());
    }

    // 트리를 한 번의 조회로 만든다. 시도별로 자식을 다시 읽으면 17번을 더 읽게 된다.
    // 부모가 비활성이면 그 아래 시군구도 통째로 빠진다 — 서비스 미개시 시도를 여는 셈이 되면 안 된다.
    public List<RegionResponse> findActiveRegionTree(String languageCode) {
        List<Region> active = regionRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc();

        Map<Long, List<Region>> childrenByParentId = new LinkedHashMap<>();
        List<Region> roots = new ArrayList<>();
        for (Region region : active) {
            if (region.isRoot()) {
                roots.add(region);
            } else {
                childrenByParentId.computeIfAbsent(region.getParentId(), key -> new ArrayList<>()).add(region);
            }
        }

        return roots.stream()
                .map(root -> new RegionResponse(
                        root.getCode(),
                        nameOf(root, languageCode),
                        childrenByParentId.getOrDefault(root.getId(), List.of()).stream()
                                .map(child -> new RegionResponse(
                                        child.getCode(), nameOf(child, languageCode), List.of()))
                                .toList()))
                .toList();
    }

    // 지역명은 로마자 표기가 국제 통용이라 번역 테이블 없이 ko/en 인라인이다(V1 주석).
    // 한국어 요청만 국문, 그 외 모든 언어는 영문으로 간다.
    private String nameOf(Region region, String languageCode) {
        return Locale.KOREAN.getLanguage().equals(languageCode) ? region.getNameKo() : region.getNameEn();
    }
}
