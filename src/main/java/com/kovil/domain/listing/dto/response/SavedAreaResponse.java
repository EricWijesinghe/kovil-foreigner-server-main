package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.dto.request.SavedAreaFilters;
import com.kovil.domain.listing.entity.SavedArea;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "저장한 구역")
public record SavedAreaResponse(
        String savedAreaUuid,
        String name,
        String placeLabel,
        @Schema(description = "정규화한 구역 — 같은 점 연속 · 닫는 점을 합친 꼭짓점. 그대로 목록 API 의 polygon 에 싣는다")
        String polygon,
        SavedAreaFilters filters,
        LocalDateTime createdAt
) {

    public static SavedAreaResponse of(SavedArea area, SavedAreaFilters filters) {
        return new SavedAreaResponse(area.getUuid(), area.getName(), area.getPlaceLabel(),
                area.getPolygon(), filters, area.getCreatedAt());
    }
}