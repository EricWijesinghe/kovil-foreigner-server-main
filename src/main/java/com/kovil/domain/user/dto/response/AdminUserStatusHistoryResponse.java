package com.kovil.domain.user.dto.response;

import com.kovil.domain.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = """
        회원 상태 전이 한 건. **관리자 전용이다** — reason 은 관리자가 쓴 내부 메모라
        사용자에게 그대로 보여주는 응답에 실으면 안 된다(PO 확정 2026-09-02).
        """)
public record AdminUserStatusHistoryResponse(

        @Schema(description = "이전 상태. null 이면 가입 시점이다.", example = "ACTIVE")
        UserStatus fromStatus,

        @Schema(description = "바뀐 상태", example = "SUSPENDED")
        UserStatus toStatus,

        @Schema(description = "행위자 구분. changed_by 만으로는 자발적 탈퇴와 관리자 조치가 갈리지 않는다.",
                example = "ADMIN")
        String actorNote,

        @Schema(description = "조치 사유(관리자 내부 메모)", example = "커뮤니티 광고 반복 게시")
        String reason,

        @Schema(description = "전이 시각", example = "2026-09-02T10:00:00")
        LocalDateTime changedAt
) {
}
