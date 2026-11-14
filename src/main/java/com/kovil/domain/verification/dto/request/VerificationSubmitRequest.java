package com.kovil.domain.verification.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = """
        외국인 인증 신청.

        **먼저 `/verifications/requirements`로 요건을 받아 파일을 올린 뒤** 여기에 제출한다.
        파일은 `/files/presign` → `/files/confirm`(purpose=`VERIFICATION_DOC`)으로 올리고
        받은 `fileUuid`를 넘긴다.

        심사가 진행 중이면 `VERIFICATION_PENDING`(409)이다. 반려된 뒤에는 다시 낼 수 있다.
        """)
public record VerificationSubmitRequest(

        @Schema(description = "신분 목록에서 고른 code", example = "STUDENT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("RESIDENT_STATUS_NOT_FOUND")
        String statusCode,

        @Schema(description = "**여권 영문 성명 그대로.** 계약 명의의 정본이 된다.",
                example = "NGUYEN VAN A", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 1, max = 100)
        String fullName,

        @Schema(description = "생년월일 (yyyy-MM-dd)", example = "1998-03-15",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        LocalDate birthDate,

        @Schema(description = "국적 ISO 3166-1 alpha-2", example = "VN",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 2, max = 2)
        String nationalityCountryCode,

        @Schema(description = "제출 서류. 요건에 없는 코드를 보내면 거절된다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("VERIFICATION_DOCS_MISSING")
        List<SubmittedDocument> documents
) {

    @Schema(description = "제출 서류 한 건. 복수장 문서는 같은 documentCode로 여러 건 보낸다.")
    public record SubmittedDocument(

            @Schema(description = "요건 응답의 documentCode", example = "PASSPORT",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlankWithErrorCode("VERIFICATION_DOCS_MISSING")
            String documentCode,

            @Schema(description = "`/files/confirm`이 준 fileUuid",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlankWithErrorCode("VERIFICATION_FILE_INVALID")
            String fileUuid
    ) {
    }
}
