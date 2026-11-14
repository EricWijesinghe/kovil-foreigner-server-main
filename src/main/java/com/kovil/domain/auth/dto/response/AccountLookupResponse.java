package com.kovil.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "이 번호로 찾은 로그인 수단.")
public record AccountLookupResponse(
        /*
         * ★ 마스킹하지 않는다.
         *
         * 호출자는 이미 **이 계정을 지탱하는 번호의 소유**를 증명했다 — 같은 증명으로
         * 비밀번호를 바꿔 계정을 통째로 가져갈 수 있다. 그 상황에서 이메일만 가리는 것은
         * 보안이 아니라 연출이고, 정작 로그인해야 하는 본인이 아이디를 못 읽어 기능이 무의미해진다.
         */
        @Schema(description = "이메일 로그인 아이디. 소셜로만 가입했으면 null.",
                example = "someone@example.com")
        String email,
        @Schema(description = "이 계정에 연결된 소셜 로그인. 이메일이 없으면 이쪽으로 들어가야 한다.",
                example = "[\"GOOGLE\"]")
        List<String> socialProviders
) {
}
