package com.kovil.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        전화번호 평문. **이 호출은 열람 기록으로 남는다.**

        회원 상세 응답에는 번호가 없다 — 마스킹을 UI 장식이 아니라 실제 경계로 두기 위해서다.
        화면의 "열람 기록이 남습니다"가 참이 되는 지점이 여기다.
        """)
public record AdminPhoneRevealResponse(

        @Schema(description = "복호화된 휴대폰 번호. 탈퇴·미등록이면 null", example = "01012345678")
        String phone,

        @Schema(description = "이 회원의 번호가 지금까지 열람된 횟수(이번 호출 포함)", example = "3")
        long revealCount
) {
}
