package com.kovil.domain.device.dto.response;

import com.kovil.domain.device.entity.DeviceToken;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "등록된 디바이스. **토큰 원문은 돌려주지 않는다** — 해제는 deviceUuid로 한다.")
public record DeviceTokenResponse(

        @Schema(description = "해제(DELETE)에 쓸 식별자")
        String deviceUuid,

        @Schema(description = "IOS · ANDROID · WEB", example = "IOS")
        String platform
) {
    public static DeviceTokenResponse from(DeviceToken deviceToken) {
        return new DeviceTokenResponse(deviceToken.getUuid(), deviceToken.getPlatform().name());
    }
}
