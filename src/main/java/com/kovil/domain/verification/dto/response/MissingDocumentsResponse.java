package com.kovil.domain.verification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 어떤 서류가 빠졌는지 알려주지 않으면 사용자가 화면을 처음부터 다시 훑어야 한다(FR-204).
@Schema(description = "필수 서류 누락 안내. 이 코드들에 해당하는 항목만 다시 채우면 된다.")
public record MissingDocumentsResponse(
        @Schema(description = "빠진 서류 코드 목록")
        List<String> missingDocumentCodes
) {
}
