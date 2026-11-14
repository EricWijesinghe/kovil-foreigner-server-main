package com.kovil.domain.appversion.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.appversion.dto.response.AppVersionCheckResponse;
import com.kovil.domain.appversion.service.AppVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AppVersion", description = "앱 버전 게이트 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/app-version")
public class AppVersionController {

    private final AppVersionService appVersionService;

    @Operation(summary = "앱 콜드 스타트 1회 호출. **비로그인 공개** — 로그인 화면보다 먼저 판정한다.",
            description = """
                    현재 바이너리 버전을 보내면 FORCE/RECOMMEND/OK를 돌려받는다.
                    비교는 서버만 한다 — 앱에 비교 로직을 넣으면 구버전 바이너리에 박제된다.

                    이상 입력(모르는 플랫폼·못 읽는 버전)은 400이 아니라 **OK**다.
                    이미 나간 바이너리가 무엇을 보내든 쓸 수 있는 답을 받아야 한다(fail-open).
                    """)
    @GetMapping("/check")
    public ApiResponse<AppVersionCheckResponse> check(
            @Parameter(description = "STUDENT | PARTNER. 없으면 STUDENT(이미 나간 유학생 바이너리는 보내지 않는다) · 모르는 값은 OK", example = "PARTNER")
            @RequestParam(required = false) String app,
            @Parameter(description = "ios | android (그 외·누락은 OK)", example = "ios")
            @RequestParam(required = false) String platform,
            @Parameter(description = "바이너리 버전 (Info.plist/versionName)", example = "1.0.0")
            @RequestParam(required = false) String version
    ) {
        return ApiResponse.success(appVersionService.check(app, platform, version));
    }
}
