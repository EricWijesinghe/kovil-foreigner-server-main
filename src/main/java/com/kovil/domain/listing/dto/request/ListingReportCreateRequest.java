package com.kovil.domain.listing.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import com.kovil.domain.listing.enums.ListingReportReason;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ListingReportCreateRequest", description = """
        매물 신고. 같은 매물을 두 번 신고하면 `LISTING_REPORT_DUPLICATE`(409)다.
        신고해도 신고자 · 중개사 누구에게도 알리지 않고, 매물을 내릴지는 관리자가 판단한다.
        """)
public record ListingReportCreateRequest(

        @Schema(description = "사유 코드", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("LISTING_REPORT_REASON_REQUIRED")
        ListingReportReason reasonCode,

        @Schema(description = "상세 설명 (500자, 선택)")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 500)
        String detail
) {
}