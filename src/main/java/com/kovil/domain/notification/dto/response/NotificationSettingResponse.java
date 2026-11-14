package com.kovil.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        알림 수신 설정.

        ★ **`CONTRACT`는 목록에 없다**(FR-608). 월세·계약·인증은 서비스 핵심이라
        끌 수 없다 — 꺼 두면 "몰라서 못 냈다"가 성립해 버린다.
        """)
public record NotificationSettingResponse(

        @Schema(description = "COMMUNITY · MARKETING", example = "COMMUNITY") String category,

        @Schema(description = "수신 여부. **설정한 적이 없으면 true**다.", example = "true") boolean enabled
) {
}
