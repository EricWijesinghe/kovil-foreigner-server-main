package com.kovil.domain.device.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.device.dto.request.DeviceTokenRegisterRequest;
import com.kovil.domain.device.dto.response.DeviceTokenResponse;
import com.kovil.domain.device.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Device", description = "푸시 알림용 디바이스 토큰 (인증 필요)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/devices")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @Operation(summary = "디바이스 토큰 등록", description = """
            **멱등이다 — 앱 시작 시마다 호출해도 안전하다.** 같은 토큰을 다시 보내도 중복 등록되지 않는다.
            
            **화면 설계**
            - 로그인 직후와 FCM 토큰 갱신 콜백에서 부른다.
            - 사용자당 상한(기본 10)을 넘으면 서버가 오래된 것부터 정리하므로 FE가 관리할 필요가 없다.
            - 기기를 넘겨받아 다른 계정으로 로그인하면 **소유자가 자동으로 이전된다**.
            
            반환된 `deviceUuid`는 해제에 쓴다.
            같은 토큰이 **동시에** 두 번 오면 늦은 쪽은 `CONFLICT`(409) — 한 번 다시 부르면 이어받는다.
            """)
    @PostMapping
    public ApiResponse<DeviceTokenResponse> register(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody DeviceTokenRegisterRequest request) {
        return ApiResponse.success(deviceTokenService.register(
                principal.userUuid(), request.token(), request.platform()));
    }

    @Operation(summary = "디바이스 토큰 해제", description = """
            이 기기로 오는 푸시를 끊는다. **로그아웃 전에 부른다** — 로그아웃이 그 액세스 토큰을 취소하므로 뒤에 부르면 401 이다.
            
            **남의 기기는 해제되지 않는다** — 없거나 내 것이 아니면 `DEVICE_TOKEN_NOT_FOUND`(404)이며
            둘을 구분하지 않는다.
            
            (탈퇴 시에는 서버가 알아서 전부 지우므로 따로 부를 필요가 없다.)
            """)
    @DeleteMapping("/{deviceUuid}")
    public ApiResponse<Void> unregister(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "등록 시 받은 deviceUuid") @PathVariable String deviceUuid) {
        deviceTokenService.unregister(principal.userUuid(), deviceUuid);
        return ApiResponse.ok();
    }
}
