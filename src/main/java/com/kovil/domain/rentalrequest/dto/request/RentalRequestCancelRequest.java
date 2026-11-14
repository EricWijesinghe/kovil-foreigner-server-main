package com.kovil.domain.rentalrequest.dto.request;

import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        임대 요청 취소. **되돌릴 수 없다** — 다시 찾으려면 새 요청을 낸다.

        사유는 선택이다. 받아 두면 어떤 요청이 왜 접히는지 알 수 있다. 맡은 사무소에는 알림이 가지 않는다 —
        사무소는 맡은 요청 목록에서 "취소됨"으로 본다.
        """)
public record RentalRequestCancelRequest(

        @Schema(description = "취소 사유(선택)", example = "Found a place on my own")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 255)
        String reason
) {
}
