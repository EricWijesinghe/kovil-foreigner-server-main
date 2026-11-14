package com.kovil.domain.user.dto.response;

import com.kovil.domain.user.entity.UserConsent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = """
        약관 동의 상태. 조회는 **타입별 최신 1건**만 준다.

        이력은 append-only로 쌓이지만(철회도 새 행), 화면이 알아야 하는 것은 현재 상태뿐이다.
        """)
public record ConsentResponse(

        @Schema(description = "TERMS_OF_SERVICE · PRIVACY_POLICY · PRIVACY_THIRD_PARTY · MARKETING",
                example = "MARKETING")
        String consentType,

        @Schema(description = "현재 동의 여부", example = "true")
        boolean agreed,

        @Schema(description = "동의한 약관 버전. 개정 시 재동의 판정 축.", example = "v1")
        String version,

        @Schema(description = "이 상태가 된 시각")
        LocalDateTime agreedAt
) {
    public static ConsentResponse from(UserConsent consent) {
        return new ConsentResponse(
                consent.getConsentType().name(),
                Boolean.TRUE.equals(consent.getAgreed()),
                consent.getVersion(),
                consent.getCreatedAt());
    }
}
