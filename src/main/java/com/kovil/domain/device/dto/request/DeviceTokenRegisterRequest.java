package com.kovil.domain.device.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        FCM 디바이스 토큰 등록. **멱등이다** — 같은 토큰을 다시 보내도 중복 등록되지 않는다.
        
        토큰이 다른 계정에 등록돼 있었다면 **소유자가 이 계정으로 이전된다**(기기를 넘겨받은 경우).
        앱 시작 시마다 호출해도 안전하다.
        """)
public record DeviceTokenRegisterRequest(

        @Schema(description = "FCM 등록 토큰 — iOS 도 FCM 등록 토큰이다(APNs 기기 토큰을 올리면 발송 때 거절되고 지워진다)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("DEVICE_TOKEN_BLANK")
        @SizeWithErrorCode(value = "DEVICE_TOKEN_BLANK", max = 512)
        String token,

        @Schema(description = "IOS · ANDROID · WEB (대소문자 무관)",
                example = "IOS", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("DEVICE_PLATFORM_NULL")
        String platform
) {
}
