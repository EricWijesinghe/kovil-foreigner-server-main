package com.kovil.domain.auth.dto.response;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "로그인·가입 성공 결과. 토큰쌍과 화면을 그리는 데 필요한 최소 정보.")
public record SignInResponse(

        @Schema(description = "Authorization 헤더에 실을 액세스 토큰")
        String accessToken,

        @Schema(description = "회전용 리프레시 토큰. **1회용이며 회전하면 즉시 폐기된다.**")
        String refreshToken,

        // JWT를 디코딩하지 않고도 갱신 시점을 잡을 수 있게 준다.
        @Schema(description = "액세스 토큰 만료까지 남은 초. 이 값을 보고 미리 갱신하면 된다.",
                example = "1800")
        long accessTokenExpiresIn,

        @Schema(description = "외부 노출 사용자 식별자", example = "3f2b...")
        String userUuid,

        @Schema(description = "역할 이름. **화면 분기에 쓰지 말 것** — 역할은 늘어난다.", example = "USER")
        String role,

        @Schema(description = """
                이 사용자가 할 수 있는 일. 관리자 웹 · 파트너스의 메뉴 · 화면 분기는 role 이 아니라 이 목록으로 한다.
                유학생은 빈 배열이다. 파트너는 **승인된 부문의 권한**만 담긴다 — 심사 중이면 빈 배열이고,
                부문 상태는 `GET /api/v1/partner/me` 로 따로 받는다.
                """, example = "[\"LISTING_MANAGE\", \"RENTAL_REQUEST_HANDLE\"]")
        List<String> permissions,

        // FE는 이 값으로 이후 요청의 Accept-Language를 채운다 — 서버는 매 요청 DB를 보지 않는다.
        @Schema(description = "저장된 UI 언어. **이후 요청의 Accept-Language를 이 값으로 채운다.**",
                example = "ko")
        String preferredLanguage
) {

    // 권한은 **발급한 액세스 토큰과 같은 출처**(TokenPair 의 승인 부문)로 계산한다 —
    // 응답과 토큰이 서로 다른 권한을 말하면 화면은 열리는데 API 가 403 을 낸다.
    public static SignInResponse of(TokenPair tokens, User user) {
        return new SignInResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresIn(),
                user.getUuid(),
                user.getRole().name(),
                user.getRole().permissionsWith(tokens.approvedDivisions()).stream()
                        .map(Enum::name).sorted().toList(),
                user.getPreferredLanguage());
    }
}
