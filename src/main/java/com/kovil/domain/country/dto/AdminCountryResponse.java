package com.kovil.domain.country.dto;

import com.kovil.domain.country.entity.Country;

public record AdminCountryResponse(
        Long id, String code, String nameEn, String flagEmoji, Integer sortOrder, Boolean isActive
) {
    public static AdminCountryResponse from(Country country) {
        return new AdminCountryResponse(country.getId(), country.getCode(), country.getNameEn(),
                country.getFlagEmoji(), country.getSortOrder(), country.getIsActive());
    }
}
