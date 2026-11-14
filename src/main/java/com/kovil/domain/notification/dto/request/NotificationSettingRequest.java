package com.kovil.domain.notification.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 수신 설정 변경. **CONTRACT는 지정할 수 없다.**")
public record NotificationSettingRequest(

        @Schema(description = "COMMUNITY · MARKETING", example = "MARKETING",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("NOTIFICATION_CATEGORY_NOT_TOGGLEABLE")
        String category,

        @Schema(description = "수신 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        Boolean enabled
) {
}
