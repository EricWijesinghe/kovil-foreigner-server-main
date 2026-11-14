package com.kovil.domain.listing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ListingSummaryBatchResponse", description = "매물 요약 조회 배치 응답")
public record ListingSummaryBatchResponse(
        @Schema(description = "요청 순서 및 지금 공개된 매물만 · 목록 카드와 같은 형태")
        List<ListingSummaryResponse> items,

        @Schema(description = "공개가 아니거나 없는 uuid")
        List<String> missing
) {
}
