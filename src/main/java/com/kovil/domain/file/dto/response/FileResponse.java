package com.kovil.domain.file.dto.response;

import com.kovil.domain.file.entity.StoredFile;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        확정된 파일.

        **`fileUrl`은 비민감 파일에만 있다.** 인증 문서는 공개 URL을
        만들지 않으므로 null이며, 열람은 `GET /files/{uuid}/url`로 **매번 짧은 URL을 새로 받는다.**
        """)
public record FileResponse(

        @Schema(description = "이후 이 파일을 지목할 때 쓰는 식별자")
        String fileUuid,

        @Schema(description = "용도", example = "VERIFICATION_DOC")
        String purpose,

        @Schema(description = "스토리지 실측 MIME 타입", example = "image/png")
        String contentType,

        @Schema(description = "스토리지 실측 바이트 크기", example = "1024")
        long byteSize,

        @Schema(description = "민감 파일이면 true. true면 fileUrl은 항상 null이다.", example = "true")
        boolean sensitive,

        @Schema(description = "비민감 파일의 공개 URL. 민감 파일은 null.")
        String fileUrl
) {
    public static FileResponse from(StoredFile file) {
        return new FileResponse(
                file.getUuid(),
                file.getPurpose().name(),
                file.getContentType(),
                file.getByteSize(),
                file.isSensitive(),
                file.getFileUrl());
    }
}
