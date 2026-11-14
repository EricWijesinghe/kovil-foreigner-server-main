package com.kovil.domain.verification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = """
        인증 승인. 승인하면 **국적이 심사값으로 확정**되어 사용자 정보에 반영된다(FR-210).

        되돌릴 수 없다 — 잘못 승인하면 사용자가 반려 후 재제출하는 경로가 없다.
        """)
public record VerificationApproveRequest(

        // 비자 만료가 다가오는 사람을 무기한 인증으로 두면 재확인 시점이 사라진다.
        @Schema(description = """
                재인증을 요구할 날짜(선택). **비우면 무기한**이다.
                체류 자격 만료일이 보이면 그 날짜를 넣어 두는 편이 안전하다.
                """, example = "2027-08-31")
        LocalDate expiresAt
) {
}
