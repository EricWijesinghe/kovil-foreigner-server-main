package com.kovil.domain.auth.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.domain.auth.dto.request.PhoneSendRequest;
import com.kovil.domain.auth.dto.request.PhoneVerifyRequest;
import com.kovil.domain.auth.dto.response.PhoneSendResponse;
import com.kovil.domain.auth.dto.response.PhoneVerifyResponse;
import com.kovil.domain.auth.service.PhoneVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Phone Verification",
        description = "휴대폰 인증 (공개). 가입·소셜 연결의 관문이며 한국 번호만 받는다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/phone")
public class PhoneVerificationController {

    private final PhoneVerificationService phoneVerificationService;

    @Operation(summary = "인증번호 발송", description = """
            SMS로 6자리 인증번호를 보낸다. **인증번호는 어떤 경우에도 응답에 담기지 않는다.**

            **화면 설계**
            - `expiresInSeconds`로 입력 만료 카운트다운을 그린다.
            - `resendAvailableInSeconds` 동안 **재발송 버튼을 잠근다** — 안 잠그면 사용자가 눌렀다가 429를 본다.

            **에러 분기**
            - `PHONE_NOT_KR`(400) — 한국 휴대폰이 아니다. 번호 입력 필드에 표시.
            - `VERIFICATION_RESEND_TOO_SOON`(429) — 쿨다운 중. 남은 시간 안내.
            - `RATE_LIMITED`(429) — 발송 한도 초과. `Retry-After` 헤더(초)로 안내.
            - `SMS_SEND_FAILED`(503) — 발송 실패. 재시도 안내.
            """)
    @RateLimited({RateLimitPolicy.PHONE_VERIFICATION_SEND_IP, RateLimitPolicy.SMS_GLOBAL})
    @PostMapping("/send")
    public ApiResponse<PhoneSendResponse> send(@Valid @RequestBody PhoneSendRequest request) {
        return ApiResponse.success(
                phoneVerificationService.send(request.phone(), request.purposeOrDefault()));
    }

    @Operation(summary = "인증번호 확인", description = """
            성공하면 가입·소셜 연결에 쓸 `verificationToken`을 준다(10분 유효, **1회용**).

            **화면 설계**
            - 성공 즉시 다음 단계(가입 폼 / 소셜 연결)로 넘긴다. 토큰을 보관했다가 그 단계에서 함께 보낸다.
            - 실패 사유를 세분해 보여주지 말 것 — 서버가 일부러 하나로 뭉쳐서 준다.

            **에러 분기**
            - `VERIFICATION_CODE_INVALID`(400) — 틀림·만료·미발급·시도초과가 **전부 이 코드다**.
              "인증번호를 다시 확인하거나 재발송해 주세요"로 응대한다.
            - `RATE_LIMITED`(429) — 시도 한도 초과. `Retry-After` 참고.
            """)
    @PostMapping("/verify")
    public ApiResponse<PhoneVerifyResponse> verify(@Valid @RequestBody PhoneVerifyRequest request) {
        return ApiResponse.success(
                phoneVerificationService.verify(
                        request.phone(), request.code(), request.purposeOrDefault()));
    }
}
