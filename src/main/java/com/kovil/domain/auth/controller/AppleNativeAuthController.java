package com.kovil.domain.auth.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.domain.auth.dto.request.AppleNativeLoginRequest;
import com.kovil.domain.auth.dto.response.AppleNonceResponse;
import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.service.AppleNativeAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "OAuth Apple", description = """
        iOS 앱의 Apple 로그인(네이티브 시트). 웹(kovil.co.kr)의 Apple 은 `OAuth` 의 `GET /apple/authorize` 흐름을 쓴다.

        **3단계 흐름이다.**
        1. `GET /apple/nonce` → 받은 값을 `signInAsync({ nonce })` 에 넘긴다.
        2. `POST /apple/native` → Apple 이 준 `identityToken` · `authorizationCode` 와 같은 nonce 를 보낸다.
           응답은 `POST /{provider}` 와 같은 두 갈래다. **처음 보는 계정이면 여기서 로그인되지 않는다.** 휴대폰 인증을 요구한다.
        3. 휴대폰 인증 후 `POST /apple/link` → 로그인 완료(다른 소셜 수단과 같은 엔드포인트).

        인증된 번호의 계정이 이미 있으면 **거절한다**(`DUPLICATE_PHONE`). 같은 이메일의 계정이 있어도 붙이지 않는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/oauth/apple")
public class AppleNativeAuthController {

    private final AppleNativeAuthService appleNativeAuthService;

    @Operation(summary = "1) Apple 로그인 nonce 발급", description = """
            재전송 방어용 1회용 값(10분)을 준다. Apple 시트를 열 때마다 새로 받는다.
            받은 값을 **그대로**(해시하지 않고) `signInAsync` 에 넘기고, 2단계에도 같은 값을 싣는다.

            **에러 분기**
            - `OAUTH_PROVIDER_DISABLED`(400) — Apple 로그인이 꺼져 있다. 버튼을 숨긴다.
            """)
    @RateLimited(RateLimitPolicy.OAUTH_LOGIN)
    @GetMapping("/nonce")
    public ApiResponse<AppleNonceResponse> nonce() {
        return ApiResponse.success(new AppleNonceResponse(appleNativeAuthService.issueNonce()));
    }

    @Operation(summary = "2) Apple 로그인 (identityToken 검증 · code 교환)", description = """
            **응답이 두 갈래다.**
            - `phoneVerificationRequired=false` → `signIn`의 토큰쌍으로 로그인 완료.
            - `phoneVerificationRequired=true` → **아직 로그인이 아니다.** `oauthToken`을 보관하고
              휴대폰 인증 화면으로 보낸 뒤 `POST /apple/link` 를 부른다.

            사용자가 시트에서 취소했으면(`ERR_REQUEST_CANCELED`) 이 API 를 부르지 않는다.

            **에러 분기**
            - `OAUTH_PROVIDER_DISABLED`(400) — Apple 로그인이 꺼져 있다.
            - `OAUTH_STATE_INVALID`(400) — nonce 가 만료 · 재사용됐다. **nonce 는 이미 소모됐으므로 1단계부터 다시** 한다.
            - `OAUTH_TOKEN_INVALID`(400) — Apple 신원 검증 실패(서명 · 발급처 · 대상 앱 · 만료 · nonce · code). 재시도 안내.
            - `OAUTH_PROVIDER_UNAVAILABLE`(503) — Apple 장애 · 시한 초과. 잠시 뒤 1단계부터 다시.
            - `SUSPENDED_USER`(403) — 정지 계정. 고객센터 안내.
            """)
    @RateLimited(RateLimitPolicy.OAUTH_LOGIN)
    @PostMapping("/native")
    public ApiResponse<OAuthLoginResponse> login(@Valid @RequestBody AppleNativeLoginRequest request) {
        return ApiResponse.success(appleNativeAuthService.login(
                request.identityToken(), request.authorizationCode(), request.nonce(),
                request.givenNameOrNull(), request.familyNameOrNull()));
    }
}
