package com.kovil.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// M1 대기열의 실제 작업 단위는 신고가 아니라 **대상**이다 — 같은 글의 신고 4건이 4행을
// 차지하면, 하나를 승인할 때 4행이 한꺼번에 사라지는 이유를 화면이 설명할 수 없다.
// 개별 신고(사유·설명)는 기존 목록을 targetUuid로 좁혀 읽는다.
@Schema(description = "신고 대상 1행 — 같은 대상의 대기 신고를 묶은 것. 많이 신고된 대상이 먼저다.")
public record AdminReportTargetResponse(
        @Schema(description = "POST | COMMENT") String targetType,
        String targetUuid,
        @Schema(description = "글이면 제목, 댓글이면 본문 앞 80자") String targetPreview,
        @Schema(description = "이미 블라인드·삭제된 대상인가") boolean targetHidden,
        @Schema(description = "이 대상에 붙은 대기 신고 수", example = "4") long reportCount,
        @Schema(description = "가장 오래 기다린 신고의 접수 시각") LocalDateTime oldestReportAt,
        @Schema(description = "가장 최근 신고의 접수 시각") LocalDateTime latestReportAt
) {
}
