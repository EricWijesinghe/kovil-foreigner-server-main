package com.kovil.domain.region.dto;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        지역 저장. 코드 정본은 행정안전부 법정동코드다.
        **코드·부모는 만들 때만 정한다** — 트리를 옮기면 기존 요청의 지역 축이 흔들린다.
        """)
public record AdminRegionRequest(
        @Schema(description = "상위 지역 id (시·도 아래 구·군). 최상위면 비운다")
        Long parentId,
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND")
        @SizeWithErrorCode(value = "MASTER_REFERENCE_NOT_FOUND", min = 2, max = 10)
        String code,
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String nameKo,
        @NotBlankWithErrorCode("MASTER_REFERENCE_NOT_FOUND") String nameEn,
        Integer sortOrder,
        Boolean isActive
) {
}
