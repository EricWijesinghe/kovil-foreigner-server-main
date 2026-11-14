package com.kovil.domain.auth.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.ClientIpResolver;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.auth.dto.request.LocalSignInRequest;
import com.kovil.domain.auth.dto.request.LocalSignUpRequest;
import com.kovil.domain.auth.dto.request.LogoutRequest;
import com.kovil.domain.auth.dto.request.PasswordResetRequest;
import com.kovil.domain.auth.dto.request.AccountLookupRequest;
import com.kovil.domain.auth.dto.response.AccountLookupResponse;
import com.kovil.domain.auth.service.AccountLookupService;
import com.kovil.domain.auth.service.PasswordResetService;
import com.kovil.domain.auth.dto.request.RefreshTokenRequest;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.auth.dto.response.TokenRefreshResponse;
import com.kovil.domain.auth.service.AuthService;
import com.kovil.domain.auth.service.LocalAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "이메일 가입·로그인, 토큰 회전, 로그아웃")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final AccountLookupService accountLookupService;
    private final LocalAuthService localAuthService;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "이메일 가입", description = """
            휴대폰 인증(`verificationToken`)이 선행되어야 한다. 성공 시 **201 + 토큰쌍**을 준다 —
            가입 직후 로그인 화면으로 되돌리지 않기 위해서다.

            **에러 분기**
            - `DUPLICATE_EMAIL`(409) — 이메일만 바꿔 재시도하면 된다. **인증은 소비되지 않았다.**
            - `DUPLICATE_PHONE`(409) — 이미 가입된 번호다. 로그인 화면으로 유도한다.
            - `VERIFICATION_TOKEN_INVALID`(400) / `VERIFICATION_ALREADY_USED`(409)
              — 휴대폰 인증부터 다시 시킨다.
            - `SUSPENDED_USER`(403) — 고객센터 안내.
            - `CONFLICT`(409) — 동시 요청 경합(닉네임 발급 등). **인증은 소비되지 않았다** —
              그대로 재시도하면 된다. `DUPLICATE_*` 와 달리 사용자 입력을 바꿀 필요가 없다.
            - `INVALID_REQUEST`(400) + `validationErrors[]` — 필드별 코드로 폼에 표시.
            """)
    @RateLimited(RateLimitPolicy.SIGNUP)
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignInResponse>> signUp(
            @Valid @RequestBody LocalSignUpRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(localAuthService.signUp(request)));
    }

    // 로그인은 어노테이션으로 막지 않는다 — 실패만 세야 하므로 서비스 레벨(LoginAttemptLimiter)이다.
    @Operation(summary = "이메일 로그인", description = """
            **유저 앱 · 파트너스 · 관리자 웹이 같은 엔드포인트를 쓴다.** 응답의 `role`(USER · PARTNER · ADMIN)과
            `permissions` 로 갈라진다 — 관리자 웹은 필요한 권한이 없으면 "권한 없음"을 그리고,
            파트너스는 `PARTNER` 가 아니면 파트너 가입으로 안내한다. 파트너의 심사 상태는 `GET /api/v1/partner/me`.
            서버는 어느 클라이언트가 부르는지 알 수 없으므로 **역할로 거절하지 않는다.**

            **에러 분기**
            - `INVALID_CREDENTIALS`(401) — 계정 없음과 비밀번호 불일치가 **같은 코드**다.
              "이메일 또는 비밀번호를 확인해 주세요" 하나로 응대한다.
              **남은 시도 횟수는 주지 않는다** — 계정 존재 여부를 추론할 재료가 된다.
            - `SUSPENDED_USER`(403) — **정지된 계정.** 폼 없는 막다른 화면 + 운영팀 연락처.
              정지 시각은 응답에 담기지 않는다(오류 응답에는 데이터 자리가 없다).
            - `USER_NOT_ACTIVE`(403) — 탈퇴 등 그 밖의 사용 불가 상태.
            - `RATE_LIMITED`(429) — 실패 누적 초과. `Retry-After` 참고.
              **성공 로그인은 한도를 소모하지 않는다.**
            """)
    @PostMapping("/login")
    public ApiResponse<SignInResponse> login(
            @Valid @RequestBody LocalSignInRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(
                localAuthService.signIn(request, clientIpResolver.resolve(servletRequest)));
    }

    @Operation(summary = "토큰 회전", description = """
            **리프레시 토큰도 함께 바뀐다** — 응답값으로 저장된 토큰을 반드시 교체해야 한다.

            **주의**: 이미 회전에 쓴(응답으로 새 토큰을 받은 뒤의 옛) 리프레시 토큰을 다시 보내면 탈취로 판정해
            **그 계정의 모든 세션이 끊긴다**. 동시에 여러 번 호출하지 않도록 요청을 직렬화할 것.
            단, 회전 직후(기본 60초 안)이고 그 회전으로 나간 새 토큰이 아직 한 번도 쓰이지 않았으면 응답 유실로 보고
            **그 새 토큰만 끊고 401** 이다 — 이 기기만 다시 로그인하고 다른 기기는 그대로다.
            로그아웃 · 세션 상한 · 비밀번호 재설정 등으로 끊긴 토큰은 다른 세션을 건드리지 않고 401 만 받는다.

            - `REFRESH_TOKEN_INVALID`(401) — 없음·폐기·만료가 전부 이 코드다. 로그인 화면으로.
            """)
    @RateLimited(RateLimitPolicy.REFRESH)
    @PostMapping("/token/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "비밀번호 재설정", description = """
            **휴대폰이 본인 확인 축이다** — 먼저 `/auth/phone/send`·`/verify`를
            `purpose=PASSWORD_RESET`으로 통과해 토큰을 받는다. 비로그인 상태에서 호출한다.

            성공하면 **살아 있는 세션이 전부 끊긴다**(리프레시 전량 회수) — 유출 의심
            상황이 전제라서다. 새 비밀번호로 다시 로그인해야 한다.

            - `VERIFICATION_TOKEN_INVALID`(400) — 토큰 무효·만료·용도 불일치.
            - `VERIFICATION_ALREADY_USED`(409) — 같은 토큰으로 이미 재설정함.
            - `RESET_ACCOUNT_NOT_FOUND`(404) — 이 번호로 가입된 계정 없음.
            - `RESET_NOT_LOCAL_ACCOUNT`(409) — 소셜 전용 계정(바꿀 비밀번호가 없음).
            - `SUSPENDED_USER`(403) — 정지 계정. 고객센터 안내 막다른 화면.
            """)
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.reset(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "아이디(이메일) 찾기", description = """
            휴대폰 인증(purpose=`PASSWORD_RESET`)을 통과하면 그 번호의 로그인 아이디를 돌려준다.
            **이메일은 가리지 않는다** — 같은 인증으로 비밀번호까지 바꿀 수 있으므로 가리는 것이
            보안이 되지 않고, 정작 본인이 아이디를 못 읽는다.
            소셜로만 가입했으면 `email` 이 null 이고 `socialProviders` 에 들어갈 길이 담긴다.
            """)
    @PostMapping("/email/find")
    public ApiResponse<AccountLookupResponse> findEmail(@Valid @RequestBody AccountLookupRequest request) {
        return ApiResponse.success(accountLookupService.lookup(request));
    }

    @Operation(summary = "로그아웃", description = """
            **이 기기의 세션만 끊는다** — 다른 기기는 유지된다.
            리프레시 토큰을 폐기하고, 이미 발급된 액세스 토큰도 남은 수명 동안 무효 처리한다.

            클라이언트는 저장한 토큰쌍을 지운다.
            """)
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CurrentUser AuthenticatedUser principal, @Valid @RequestBody LogoutRequest request) {
        authService.logout(principal, request.refreshToken());
        return ApiResponse.ok();
    }
}
