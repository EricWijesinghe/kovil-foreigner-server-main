package com.kovil.domain.region.dto;

import com.kovil.domain.region.entity.Region;

public record AdminRegionResponse(
        Long id, Long parentId, String code, String nameKo, String nameEn,
        Integer sortOrder, Boolean isActive
) {
    public static AdminRegionResponse from(Region region) {
        return new AdminRegionResponse(region.getId(), region.getParentId(), region.getCode(),
                region.getNameKo(), region.getNameEn(), region.getSortOrder(), region.getIsActive());
    }
}
