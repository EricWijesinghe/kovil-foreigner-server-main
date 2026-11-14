package com.kovil.domain.verification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        인증 신청 상태. **판정은 언제나 최신 1건**이며 과거 신청은 이 응답에 나오지 않는다.

        - `PENDING_REVIEW` — 심사 중. 재제출 불가.
        - `APPROVED` — 인증 완료. `expiresAt`이 있으면 그날까지 유효하다.
        - `REJECTED` — `rejectReason`을 **그대로 사용자에게 보여준다**. 다시 제출할 수 있다.
        """)
public record VerificationResponse(

        @Schema(description = "신청 식별자")
        String verificationUuid,

        @Schema(description = "PENDING_REVIEW · APPROVED · REJECTED", example = "PENDING_REVIEW")
        String status,

        @Schema(description = "제출 시점의 신분 코드(스냅샷)", example = "STUDENT")
        String residentStatusCode,

        @Schema(description = "여권 영문 성명", example = "NGUYEN VAN A")
        String fullName,

        @Schema(description = "생년월일")
        LocalDate birthDate,

        @Schema(description = "국적 ISO 코드", example = "VN")
        String nationalityCountryCode,

        @Schema(description = "반려 사유. **그대로 노출한다.** 반려가 아니면 null.",
                example = "여권 사진면이 잘려 있습니다.")
        String rejectReason,

        @Schema(description = "인증 만료일. null이면 무기한.")
        LocalDate expiresAt,

        @Schema(description = "지금 이 인증으로 임대 요청을 낼 수 있는지. **화면 게이트는 이 값으로 판단한다.**",
                example = "false")
        boolean effective,

        @Schema(description = "제출 시각")
        LocalDateTime submittedAt,

        @Schema(description = "심사 시각. 미심사면 null.")
        LocalDateTime reviewedAt,

        @Schema(description = "제출한 서류")
        List<VerificationDocumentResponse> documents
) {

    @Schema(description = "제출 서류 한 건. 파일은 `/files/{fileUuid}/url`로만 열람한다.")
    public record VerificationDocumentResponse(

            @Schema(description = "제출 시점의 문서 코드(스냅샷)", example = "PASSPORT")
            String documentCode,

            @Schema(description = "열람 URL을 받을 때 쓰는 식별자")
            String fileUuid
    ) {
    }
}
