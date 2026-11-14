package com.kovil.domain.user.dto.request;

import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        정지 사유(선택). **내부 기록이며 유저에게 노출되지 않는다** —
        정지된 사용자가 보는 것은 "고객센터로 문의해 주세요" 하나다.
        """)
public record UserSuspendRequest(

        @Schema(description = "정지 사유", example = "커뮤니티 광고 반복 게시, 3회 경고 후 정지")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 255)
        String reason
) {
}
