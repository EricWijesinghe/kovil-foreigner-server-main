package com.kovil.domain.auth.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.domain.auth.dto.request.OAuthLinkRequest;
import com.kovil.domain.auth.dto.request.OAuthLoginRequest;
import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.auth.service.OAuthAuthService;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.OAuthReturnTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "OAuth", description = """
        소셜 로그인 (Apple · Google · Facebook). iOS 앱의 Apple 은 1 · 2단계 대신 `OAuth Apple` 의 네이티브 흐름을 쓰고,
        3단계 link 만 함께 쓴다. 웹(kovil.co.kr)의 Apple 은 이 흐름 그대로다.

        **3단계 흐름이다.**
        1. `GET /authorize` → provider 로그인 URL을 받아 웹뷰로 연다.
        2. `POST /{provider}` → 돌아온 code·state를 보낸다.
           **처음 보는 계정이면 여기서 로그인되지 않는다** — 휴대폰 인증을 요구한다.
        3. 휴대폰 인증 후 `POST /{provider}/link` → 로그인 완료.

        인증된 번호의 계정이 이미 있으면 **거절한다**(`DUPLICATE_PHONE`). 번호만으로는 같은 사람인지 알 수 없어 기존 계정에 붙이지 않는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {

    private final OAuthAuthService oAuthAuthService;

    @Operation(summary = "1) 소셜 로그인 URL 발급", description = """
            provider의 로그인 화면 URL을 만들어 준다. `state`가 URL에 포함되어 있고
            **CSRF 방어 축이므로 클라이언트가 임의로 바꾸면 안 된다.**

            받은 URL을 웹뷰/브라우저로 열고, redirect_uri로 돌아온 `code`·`state`를 2단계에 그대로 넘긴다.

            **돌아올 곳(`returnTo`).** `APP`(기본)은 `kovil://oauth/{provider}`, `WEB` 은 웹의 `/oauth/{provider}` 로
            `code`·`state`(거부면 `error`)를 싣고 돌아온다. 웹은 창 전체를 이 URL 로 보낸다(팝업 아님).
            verifier 는 돌아온 뒤에도 읽어야 하므로 같은 탭의 sessionStorage 에 두고, 서버로는 2단계에서만 보낸다.
            주소는 서버 설정이 정하고 요청으로 바꿀 수 없다. 다른 값이면 `INVALID_REQUEST`(400).

            **PKCE 필수.** 클라이언트는 `code_verifier`(43~128자 unreserved)를 만들어 **앱 안에만 두고**,
            그것의 SHA-256을 패딩 없는 base64url로 적은 `codeChallenge`만 여기에 보낸다.
            2단계에서 verifier 원문을 대조하므로, 커스텀 스킴으로 돌아오는 code가 가로채여도 쓸 수 없다.
            형식이 어긋나면 `OAUTH_PKCE_INVALID`(400).
            """)
    @GetMapping("/{provider}/authorize")
    public ApiResponse<String> authorize(
            @Parameter(description = "APPLE · GOOGLE · FACEBOOK (대소문자 무관)", example = "google")
            @PathVariable String provider,
            @Parameter(description = "code_verifier의 SHA-256을 패딩 없는 base64url로 적은 43자 (S256)",
                    required = true)
            // required=false 로 받아 **없을 때도 OAUTH_PKCE_INVALID** 가 나가게 한다.
            // required=true 면 스프링이 먼저 INVALID_REQUEST 를 내보내 위 문서와 어긋난다.
            @RequestParam(required = false) String codeChallenge,
            @Parameter(description = "돌아올 곳. APP(기본) · WEB", example = "WEB")
            @RequestParam(required = false) String returnTo) {
        return ApiResponse.success(oAuthAuthService.buildAuthorizationUri(
                AuthType.from(provider), codeChallenge, OAuthReturnTarget.from(returnTo)));
    }

    @Operation(summary = "2) 소셜 로그인 (code 교환)", description = """
            **응답이 두 갈래다.**
            - `phoneVerificationRequired=false` → `signIn`의 토큰쌍으로 로그인 완료.
            - `phoneVerificationRequired=true` → **아직 로그인이 아니다.** `oauthToken`을 보관하고
              휴대폰 인증 화면으로 보낸 뒤 3단계를 부른다.

            **에러 분기**
            - `OAUTH_STATE_INVALID`(400) — state가 만료·재사용됐다. 1단계부터 다시.
            - `OAUTH_PKCE_INVALID`(400) — `codeVerifier`가 1단계의 challenge와 맞지 않는다.
              **state는 이미 소모됐으므로 1단계부터 다시** 해야 한다.
            - `OAUTH_TOKEN_INVALID`(400) — provider 검증 실패. 재시도 안내.
            - `OAUTH_PROVIDER_UNAVAILABLE`(503) — Apple 장애 · 시한 초과(Apple 만). 잠시 뒤 1단계부터 다시.
            - `SUSPENDED_USER`(403) — 정지 계정. 고객센터 안내.
              (탈퇴 계정은 여기 오지 않는다 — 수단이 해제돼 신규 신원으로 취급되고 재가입 경로를 탄다)
            """)
    @RateLimited(RateLimitPolicy.OAUTH_LOGIN)
    @PostMapping("/{provider}")
    public ApiResponse<OAuthLoginResponse> login(
            @Parameter(description = "APPLE · GOOGLE · FACEBOOK", example = "google")
            @PathVariable String provider,
            @Valid @RequestBody OAuthLoginRequest request) {
        return ApiResponse.success(
                oAuthAuthService.login(AuthType.from(provider), request.code(), request.state(),
                        request.user(), request.codeVerifier()));
    }

    @Operation(summary = "3) 휴대폰 인증 후 가입 확정", description = """
            인증된 번호로 **새 계정을 만들고** 이 소셜 수단을 붙인 뒤 로그인시킨다.
            그 번호의 계정이 이미 있으면 붙이지 않고 `DUPLICATE_PHONE` 으로 거절한다.
            휴대폰 인증은 번호의 주인이 같은 사람인지 알려 주지 않기 때문이다(번호는 넘겨받을 수 있다).

            **에러 분기**
            - `OAUTH_TEMP_TOKEN_EXPIRED`(400) — 2단계 증표가 만료(10분). 1단계부터 다시.
            - `VERIFICATION_TOKEN_INVALID`(400) / `VERIFICATION_ALREADY_USED`(409) — 휴대폰 인증부터 다시.
            - `SUSPENDED_USER`(403) — 고객센터 안내.
            - `DUPLICATE_PHONE`(409) — 이미 가입된 번호다. 본인 계정이면 그 계정으로 로그인하도록,
              아니면 고객센터로 안내한다. **휴대폰 인증은 소비되지 않았다.**
            - `OAUTH_EMAIL_REQUIRED`(400) — 소셜 계정이 이메일을 주지 않았다.
              이메일 제공에 동의해 1단계부터 다시 하거나 다른 방법으로 가입하도록 안내한다.
            - `OAUTH_ALREADY_LINKED`(409) — 이 소셜 계정이 **이미 다른 계정에 붙어 있다.**
              그 계정으로 로그인하거나 고객센터로 안내한다.
            - `CONFLICT`(409) — 동시 요청 경합. 휴대폰 인증은 살아 있으니 그대로 재시도한다.
              닉네임이 겹친 경우면 재시도가 성공하고, 같은 소셜 계정을 동시에 연결한 경우면 재시도 답은 보통 `OAUTH_ALREADY_LINKED` 다.
            """)
    @RateLimited(RateLimitPolicy.SIGNUP)
    @PostMapping("/{provider}/link")
    public ApiResponse<SignInResponse> link(
            @Parameter(description = "APPLE · GOOGLE · FACEBOOK", example = "google")
            @PathVariable String provider,
            @Valid @RequestBody OAuthLinkRequest request) {
        return ApiResponse.success(oAuthAuthService.link(
                request.oauthToken(), request.verificationToken(), request.preferredLanguage()));
    }
}
