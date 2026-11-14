package com.kovil.domain.community.dto.response;

import com.kovil.domain.community.enums.ReportReasonCode;
import com.kovil.domain.community.enums.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 신고자는 관리자에게도 노출하지 않는다 — 판단 재료는 대상과 사유로 충분하다.
@Schema(description = "신고 대기열 행. `sameTargetPendingCount`가 2 이상이면 여러 사람이 지목한 대상이다.")
public record AdminReportResponse(
        String reportUuid,
        @Schema(description = "POST | COMMENT") String targetType,
        String targetUuid,
        @Schema(description = "글이면 제목, 댓글이면 본문 앞 80자") String targetPreview,
        @Schema(description = "이미 블라인드·삭제된 대상인가") boolean targetHidden,
        ReportReasonCode reasonCode,
        String detail,
        long sameTargetPendingCount,
        ReportStatus status,
        LocalDateTime createdAt,

        // 블라인드는 한 사람의 판단으로 남의 글을 가리는 일이다 — 누가 언제가 없으면
        // 사유만 떠 있다(확인서 inspectedByName·계약서 uploadedByName과 같은 수준).
        // 승인·기각 모두 찍힌다. PENDING이면 null이다.
        @Schema(description = "처리한 관리자 이름. 미처리·계정 삭제면 null.", example = "김도현")
        String handledByName,
        @Schema(description = "처리 시각. 미처리면 null.") LocalDateTime handledAt
) {
}
