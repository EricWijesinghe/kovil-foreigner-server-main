package com.kovil.domain.user.dto.response;

import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        회원 상세 (ADMIN 전용).

        ★ **전화번호는 여기 없다.** 평문 열람은 `POST /admin/users/{userUuid}/phone-reveal`로만 가능하고
        그 호출이 기록으로 남는다 — 마스킹을 UI 장식이 아니라 실제 경계로 두기 위해서다.

        인증 이력은 `GET /admin/users/{userUuid}/verifications`로 따로 받는다 —
        같은 데이터를 두 곳에서 만들면 언젠가 어긋난다.
        """)
public record AdminUserDetailResponse(
        String userUuid,
        String email,
        String name,
        String nickname,
        UserRole role,
        UserStatus status,
        String preferredLanguage,

        @Schema(description = "유효 인증의 상태. 없으면 null") String verificationStatus,

        @Schema(description = """
                가입 경로. 한 계정에 여러 수단이 붙을 수 있다(이메일 + 소셜 연동).
                """, example = "[\"LOCAL\"]") List<String> signupSources,

        @Schema(description = "정지 시각. 정지 상태가 아니면 null") LocalDateTime suspendedAt,

        @Schema(description = "정지 사유. **내부 기록이며 유저에게 노출되지 않는다.**")
        String suspendReason,

        @Schema(description = """
                이 사람이 무엇을 하고 있었는가. **정지를 판단하려면 한 화면에서 알아야 한다** —
                이 블록이 없으면 관리자가 화면 네 곳을 따로 열어야 한다.
                """) Activity activity,

        LocalDateTime createdAt
) {

    @Schema(description = "활동 집계")
    public record Activity(
            @Schema(description = "진행 중 임대 요청 수(SUBMITTED · IN_PROGRESS)", example = "1")
            long openRentalRequestCount,
            @Schema(description = "작성한 커뮤니티 글 수(삭제 제외)", example = "3")
            long communityPostCount,
            @Schema(description = "이 사람의 글·댓글이 받은 신고 수", example = "0")
            long receivedReportCount) {
    }
}
