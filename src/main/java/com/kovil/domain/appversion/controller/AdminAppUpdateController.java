package com.kovil.domain.appversion.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.appversion.dto.request.AdminAppUpdatePolicyRequest;
import com.kovil.domain.appversion.dto.response.AdminAppUpdatePolicyResponse;
import com.kovil.domain.appversion.enums.AppKind;
import com.kovil.domain.appversion.service.AdminAppUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin · AppUpdate", description = """
        앱 버전 정책 (**ADMIN 전용**). 행은 IOS·ANDROID 둘로 고정 — 추가·삭제 없음, 수정만.
        저장 즉시 다음 앱 실행부터 적용된다(서버 재배포 없음). 강제 업데이트를 켜기 전에
        **그 버전이 스토어에서 실제로 받아지는지** 반드시 확인할 것.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/app-update-policies")
@PreAuthorize("hasAuthority('PERM_APP_POLICY_MANAGE')")
public class AdminAppUpdateController {

    private final AdminAppUpdateService adminAppUpdateService;

    @Operation(summary = "앱별 정책 (IOS · ANDROID)", description = "`app` 은 STUDENT(유학생 앱) | PARTNER(파트너스 앱). 모르는 값은 400.")
    @GetMapping("/{app}")
    public ApiResponse<List<AdminAppUpdatePolicyResponse>> findAll(
            @PathVariable AppKind app
    ) {
        return ApiResponse.success(adminAppUpdateService.findAll(app));
    }

    @Operation(summary = "정책 수정", description = """
            min ≤ latest, 버전은 1.2.3 형식, URL은 https:// 또는 빈 값.
            **앱을 경로에 반드시 싣는다** — 플랫폼만으로는 유학생 앱과 파트너스 앱의 정책을 가를 수 없다.
            """)
    @PutMapping("/{app}/{platform}")
    public ApiResponse<AdminAppUpdatePolicyResponse> update(
            @PathVariable AppKind app,
            @PathVariable String platform,
            @RequestBody AdminAppUpdatePolicyRequest request) {
        return ApiResponse.success(adminAppUpdateService.update(app, platform, request));
    }
}
