package com.kovil.domain.verification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        심사자용 신청 상세. 사용자용 응답과 달리 **신청자 식별자**가 들어 있다.

        서류 이미지는 여기 없다 — `/files/{fileUuid}/url`로 **매번 단기 URL을 받아** 연다(INV-08).
        문서 개수는 `documents`의 길이다(별도 필드를 두지 않는다 — 두 값이 어긋날 자리를 만들지 않기 위해).
        """)
public record AdminVerificationResponse(

        @Schema(description = "승인·반려에 쓸 식별자")
        String verificationUuid,

        @Schema(description = "PENDING_REVIEW · APPROVED · REJECTED · REVOKED(승인 후 무효화)",
                example = "PENDING_REVIEW")
        String status,

        @Schema(description = "신청자 식별자")
        String userUuid,

        @Schema(description = "여권 영문 성명 — 계약 명의의 정본", example = "NGUYEN VAN A")
        String fullName,

        @Schema(description = "생년월일")
        LocalDate birthDate,

        @Schema(description = "신청자가 고른 국적", example = "VN")
        String nationalityCountryCode,

        @Schema(description = "제출 시점의 신분 코드(스냅샷)", example = "STUDENT")
        String residentStatusCode,

        @Schema(description = "제출 시점에 요구했던 서류 코드(스냅샷). **지금 요건과 다를 수 있다** — "
                + "그때 무엇을 요구했는지가 심사 근거다.")
        List<String> requiredDocumentCodes,

        @Schema(description = "실제 제출된 서류")
        List<VerificationResponse.VerificationDocumentResponse> documents,

        @Schema(description = "반려 사유(반려 건만)")
        String rejectReason,

        @Schema(description = "인증 만료일(승인 건만). null이면 무기한")
        LocalDate expiresAt,

        @Schema(description = "신청 시각. 대기열은 이 순서대로 처리한다.")
        LocalDateTime submittedAt,

        @Schema(description = "심사 시각. 미심사면 null")
        LocalDateTime reviewedAt,

        @Schema(description = "심사자 이름. 미심사면 null", example = "박서진")
        String reviewedByName,

        @Schema(description = """
                이 신청자의 **이전 신청 건수.** 0이면 첫 신청, 1 이상이면 재신청 배지를 붙인다.
                이전 반려 사유는 `GET /admin/users/{userUuid}/verifications`로 받는다 —
                같은 사람이 같은 이유로 세 번째 반려되는 상황을 심사자가 알아야 한다.
                """, example = "2")
        int previousAttemptCount,

        @Schema(description = "무효화 시각(REVOKED만)")
        LocalDateTime revokedAt,

        @Schema(description = "무효화 사유(REVOKED만). **내부 기록이며 유저에게 노출되지 않는다.**")
        String revokeReason
) {
}
