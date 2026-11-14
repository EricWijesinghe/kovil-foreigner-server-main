package com.kovil.domain.listing.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SavedAreaCreateRequest(
        @Schema(description = "이름 — 앞뒤 공백을 빼고 1~20자. 같은 이름 허용")
        @NotBlank String name,
        @Schema(description = "앱이 지도 SDK 로 얻은 동 이름(예 창천동). 최대 40자. 서버는 저장만 한다")
        @Size(max = 40) String placeLabel,
        @Schema(description = "그린 구역 — 목록 API 의 polygon 과 같은 형식 · 같은 검증(틀리면 400 LISTING_POLYGON_INVALID)")
        String polygon,
        @Schema(description = "저장할 때의 목록 필터. 비우면 필터 없음")
        @Valid SavedAreaFilters filters
) {
}