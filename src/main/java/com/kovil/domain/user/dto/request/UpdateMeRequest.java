package com.kovil.domain.user.dto.request;

import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        내 정보 수정. **PATCH다 — 보내지 않은 필드는 건드리지 않는다.**
        비우고 싶으면 빈 문자열을 보낸다(`name`만 해당).

        `profileImageUrl`은 받지 않는다: 파일은 presign→confirm을 거쳐야 하므로
        임의 URL을 받으면 그 규약이 무너진다.
        """)
public record UpdateMeRequest(

        @Schema(description = "표시용 이름. 빈 문자열이면 비운다.", example = "Nguyen Van A")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 100)
        String name,

        @Schema(description = """
                커뮤니티 표시명. 2~20자의 문자·숫자(한글·라틴·키릴 모두 가능),
                공백·특수문자 불가. 중복이면 `NICKNAME_TAKEN`(409), 금칙어면 `NICKNAME_BANNED`.
                """, example = "SunnyDay")
        String nickname,

        @Schema(description = "UI 언어. `/languages`의 code만 받는다.", example = "ko")
        String preferredLanguage,

        @Schema(description = "국적 ISO 3166-1 alpha-2. `/countries`의 code만 받는다.", example = "VN")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 2, max = 2)
        String nationalityCountryCode
) {
}
