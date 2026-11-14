package com.kovil.domain.auth.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.auth.dto.request.AppleNotificationRequest;
import com.kovil.domain.auth.service.AppleAccountEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth · Apple 알림", description = """
        Apple 이 보내는 서버 간 알림. 사람이 부르는 API 가 아니다. Services ID 의 알림 주소가 이 경로다.
        """)
@RestController
@RequiredArgsConstructor
public class AppleNotificationController {

    private final AppleAccountEventService appleAccountEventService;

    @Operation(summary = "Apple 계정 변경 알림", description = """
            비로그인 경로다. 본문의 `payload` 를 Apple 공개키로 검증한다(발급처 · 대상 앱 · 서명).

            - `consent-revoked` · `account-delete` → 그 Apple 계정에 붙은 회원의 **로그인 세션을 끊고**
              저장해 둔 Apple 토큰을 지운다. Apple 연결은 남아 다시 Apple 로 로그인하면 원래 계정으로 들어온다.
            - `email-disabled` · `email-enabled` → 기록만 한다.
            - 같은 알림이 다시 와도 결과가 같다.

            **응답**
            - 200 — 처리했다(연결된 회원이 없어도 200).
            - `OAUTH_TOKEN_INVALID`(400) — 서명 · 발급처 · 대상 앱이 맞지 않거나 events 가 깨졌다.
            - `INVALID_REQUEST`(400) — 본문에 `payload` 가 없다(`validationErrors[0].errorCode` 는 `OAUTH_TOKEN_INVALID`).
            - `OAUTH_PROVIDER_UNAVAILABLE`(503) — Apple 공개키를 받지 못했다. Apple 이 다시 보낸다.
            """)
    @PostMapping("/oauth/apple/notifications")
    public ApiResponse<Void> receive(@Valid @RequestBody AppleNotificationRequest request) {
        appleAccountEventService.handle(request.payload());
        return ApiResponse.ok();
    }
}
