package com.kovil.domain.auth.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Apple 서버 간 알림 본문. Apple 이 보내는 그대로다.")
public record AppleNotificationRequest(

        @Schema(description = "Apple 키로 서명한 JWS. events 에 종류(type)와 사용자(sub)가 있다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("OAUTH_TOKEN_INVALID")
        @SizeWithErrorCode(value = "OAUTH_TOKEN_INVALID", max = 8192)
        String payload
) {
}
