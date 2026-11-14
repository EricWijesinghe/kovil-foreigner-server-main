package com.kovil.domain.file.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        업로드 확정. 서버가 **스토리지에서 실측**해 검증하고 확정 경로로 옮긴 뒤 원장에 기록한다.
        클라이언트가 신고한 크기·타입은 신뢰하지 않는다.
        """)
public record ConfirmUploadRequest(

        @Schema(description = "presign이 준 `key`를 그대로", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_TEMP_KEY")
        String tempKey,

        @Schema(description = "presign 때와 같은 용도", example = "VERIFICATION_DOC",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("FILE_PURPOSE_NULL")
        String purpose,

        @Schema(description = "원본 파일명(선택)", example = "passport.png")
        String originalName
) {
}
