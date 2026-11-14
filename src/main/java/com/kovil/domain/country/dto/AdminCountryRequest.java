package com.kovil.domain.country.dto;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "국가 저장. 코드는 ISO 3166-1 alpha-2가 정본이며 만들 때만 정한다.")
public record AdminCountryRequest(
        @Schema(example = "VN")
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND")
        @SizeWithErrorCode(value = "MASTER_REFERENCE_NOT_FOUND", min = 2, max = 2)
        String code,
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String nameEn,
        String flagEmoji,
        Integer sortOrder,
        Boolean isActive
) {
}
