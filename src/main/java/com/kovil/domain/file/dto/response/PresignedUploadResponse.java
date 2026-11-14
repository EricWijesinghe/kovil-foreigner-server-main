package com.kovil.domain.file.dto.response;

import com.kovil.infra.storage.PresignedUpload;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "업로드 URL. 만료 전에 PUT하고 confirm까지 마쳐야 한다.")
public record PresignedUploadResponse(

        @Schema(description = "confirm에 그대로 돌려줄 임시 키")
        String key,

        @Schema(description = "여기에 파일을 PUT한다. **Content-Type을 요청 때와 똑같이 보낼 것.**")
        String uploadUrl,

        @Schema(description = "이 시각이 지나면 다시 발급받아야 한다")
        LocalDateTime expiresAt
) {
    public static PresignedUploadResponse from(PresignedUpload presigned) {
        return new PresignedUploadResponse(
                presigned.key(), presigned.uploadUrl(), presigned.expiresAt());
    }
}
