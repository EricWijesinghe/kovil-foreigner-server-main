package com.kovil.domain.file.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        업로드 URL 발급. **이 단계는 DB에 아무것도 남기지 않는다** —
        발급만 받고 올리지 않은 건은 스토리지 수명 정책이 알아서 지운다.

        받은 `uploadUrl`에 **같은 Content-Type으로** PUT한 뒤 `/files/confirm`을 부른다.
        타입이 다르면 스토리지가 거부한다(서명에 포함되어 있다).
        """)
public record PresignUploadRequest(

        @Schema(description = """
                VERIFICATION_DOC · COMMUNITY_IMAGE · PROFILE_IMAGE · LISTING_IMAGE.
                CONTRACT_DOC · MOVE_IN_PHOTO · BANK_ICON 은 은퇴한 용도라 `INVALID_FILE_PURPOSE`(400)다.
                """,
                example = "VERIFICATION_DOC", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("FILE_PURPOSE_NULL")
        String purpose,

        @Schema(description = "올릴 파일의 MIME 타입. 용도별 허용 목록이 다르다.",
                example = "image/png", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("UNSUPPORTED_CONTENT_TYPE")
        String contentType,

        @Schema(description = "원본 파일명(선택). 표시용으로만 쓰고 확장자는 여기서 뽑지 않는다.",
                example = "passport.png")
        String originalName
) {
}
