package com.kovil.domain.user.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.domain.user.enums.ConsentType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        약관 동의·철회 기록. **append-only다** — 철회도 `agreed=false`인 새 기록으로 남는다.
        기존 기록은 수정되지 않는다(개인정보보호법 보존 의무).
        """)
public record ConsentRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "MARKETING")
        @NotNullWithErrorCode("INVALID_CONSENT_TYPE")
        ConsentType consentType,

        @Schema(description = "true=동의, false=철회", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "true")
        @NotNullWithErrorCode("INVALID_REQUEST")
        Boolean agreed,

        @Schema(description = "동의한 약관 버전", example = "v1")
        String version
) {
}
