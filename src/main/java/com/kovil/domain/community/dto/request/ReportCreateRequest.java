package com.kovil.domain.community.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import com.kovil.domain.community.enums.ReportReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        신고. 같은 대상을 두 번 신고하면 `REPORT_DUPLICATE`(409)다.
        신고해도 상대는 알 수 없고, 노출 여부는 관리자 판단으로만 바뀐다.
        """)
public record ReportCreateRequest(

        @Schema(description = "사유 코드", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("REPORT_REASON_REQUIRED")
        ReportReasonCode reasonCode,

        @Schema(description = "상세 설명 (500자, 선택)")
        @SizeWithErrorCode(value = "REPORT_REASON_REQUIRED", min = 0, max = 500)
        String detail
) {
}
