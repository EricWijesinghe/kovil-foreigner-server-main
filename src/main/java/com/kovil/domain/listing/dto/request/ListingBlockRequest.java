package com.kovil.domain.listing.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ListingBlockRequest", description = "매물 차단 사유")
public record ListingBlockRequest(
        @Schema(description = "차단 사유(500자). 관리자 내부 기록이다 — 중개사 응답 · 알림에 실리지 않는다.",
                example = "사진과 실제 방이 다르다는 신고 3건을 확인했습니다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 500)
        String reason
) {
}