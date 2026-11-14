package com.kovil.domain.appversion.dto.response;

import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record AdminAppUpdatePolicyResponse(
        @Schema(description = "STUDENT(유학생 앱) | PARTNER(파트너스 앱)", example = "STUDENT")
        AppKind app,
        @Schema(description = "IOS | ANDROID", example = "IOS")
        String platform,
        String minSupportedVersion,
        String latestVersion,
        @Schema(description = "비어 있으면 어떤 판정도 발동하지 않는다(화면이 이 상태를 안내할 것)")
        String storeUrl,
        LocalDateTime updatedAt
) {
    public static AdminAppUpdatePolicyResponse from(AppUpdatePolicy policy) {
        return new AdminAppUpdatePolicyResponse(
                policy.getApp(),
                policy.getPlatform(),
                policy.getMinSupportedVersion(),
                policy.getLatestVersion(),
                policy.getStoreUrl(),
                policy.getUpdatedAt()
        );
    }
}
