package com.kovil.domain.user.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.user.dto.request.RoleAssignRequest;
import com.kovil.domain.user.dto.request.UserSuspendRequest;
import com.kovil.domain.user.dto.response.AdminPhoneRevealResponse;
import com.kovil.domain.user.dto.response.AdminUserDetailResponse;
import com.kovil.domain.user.dto.response.AdminUserStatusHistoryResponse;
import com.kovil.domain.user.dto.response.AdminUserSummaryResponse;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.AdminUserService;
import com.kovil.domain.user.service.RoleAssignmentService;
import com.kovil.domain.verification.dto.response.AdminVerificationResponse;
import com.kovil.domain.verification.service.VerificationReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin · User", description = """
        회원 관리 (**ADMIN 전용**).

        정지는 로그인과 모든 API를 막는다. 해제하면 그대로 복귀한다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final VerificationReviewService verificationReviewService;
    private final RoleAssignmentService roleAssignmentService;

    /*
     * ★ 이 하나만 클래스 게이트(PERM_USER_MANAGE)보다 좁다 — 메서드 애너테이션이 이긴다.
     *
     * 회원 조회·정지와 **역할 승급은 위험도가 다르다.** 여기가 ADMIN 을 만들 수 있는 유일한
     * 문이라, 회원 관리를 맡기는 것과 관리자를 찍어낼 권한을 주는 것이 같아지면 안 된다.
     */
    @Operation(summary = "역할 변경(승급·강등)", description = """
            **ADMIN 은 이 경로로만 부여된다** — 가입 화면에서는 신청할 수 없다(PO 확정 2026-09-17).
            자기 자신은 바꿀 수 없고(`ROLE_ASSIGN_SELF_FORBIDDEN` 403), 마지막 관리자는 강등되지 않는다
            (`LAST_ADMIN_CANNOT_BE_DEMOTED` 409). 바뀐 사람의 세션은 전부 끊긴다.

            **USER 와 ADMIN 사이만 오간다.** 파트너(`PARTNER`)는 이 경로로 주지도 빼지도 못한다
            (`PARTNER_ROLE_NOT_ASSIGNABLE` 400) — 파트너는 파트너 가입으로 생기고, 할 수 있는 일은
            부문 심사(`/api/v1/admin/partner-divisions`)가, 떠나는 것은 탈퇴가 정한다.
            """)
    @PreAuthorize("hasAuthority('PERM_ROLE_ASSIGN')")
    @PatchMapping("/{userUuid}/role")
    public ApiResponse<Void> assignRole(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String userUuid,
            @Valid @RequestBody RoleAssignRequest request) {
        roleAssignmentService.assignRole(principal.userUuid(), userUuid, UserRole.from(request.role()));
        return ApiResponse.ok();
    }

    @Operation(summary = "회원 목록", description = """
            `query`는 **이메일·이름·닉네임 부분 일치**다. 최신 가입순.
            전체 전화번호(`01012341234`, 하이픈·+82 허용)를 넣으면 **해시 완전일치**로 찾는다 —
            번호는 암호화 저장이라 부분 번호로는 못 찾는다.
            `status`·`role`은 각각 회원 상태·권한 필터이며 비우면 전체다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<AdminUserSummaryResponse>> search(
            @Parameter(description = "ACTIVE · SUSPENDED · WITHDRAWN")
            @RequestParam(required = false) UserStatus status,
            @Parameter(description = "USER · PARTNER · ADMIN")
            @RequestParam(required = false) UserRole role,
            @Parameter(description = "검색어 — 이메일·이름·닉네임 부분 일치. 전체 전화번호는 완전일치")
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminUserService.search(status, role, query, Paging.of(page, size)));
    }

    @Operation(summary = "회원 상세", description = """
            ★ **전화번호는 이 응답에 없다.** 평문은 `POST /{userUuid}/phone-reveal`로만 받는다 —
            상세에 실어 두면 화면을 열기만 해도 평문이 흘러 열람 기록이 무의미해진다.

            `activity`로 **이 사람이 무엇을 하고 있었는지**를 한 화면에서 본다 — 정지 판단의 재료다.
            인증 이력은 `GET /{userUuid}/verifications`로 따로 받는다.
            """)
    @GetMapping("/{userUuid}")
    public ApiResponse<AdminUserDetailResponse> getDetail(@PathVariable String userUuid) {
        return ApiResponse.success(adminUserService.getDetail(userUuid));
    }

    @Operation(summary = "인증 이력", description = """
            신청 전부를 **최신순**으로 준다(반려·무효화 포함). 재신청 건을 심사할 때
            **이전 반려 사유를 함께 읽기 위한 조회다** —
            같은 사람이 같은 이유로 세 번째 반려되는 상황을 심사자가 알아야 한다.
            """)
    @GetMapping("/{userUuid}/verifications")
    public ApiResponse<List<AdminVerificationResponse>> verifications(@PathVariable String userUuid) {
        return ApiResponse.success(verificationReviewService.findByUser(userUuid));
    }

    @Operation(summary = "상태 이력", description = """
            가입·정지·해제·탈퇴 전이를 **최신순**으로 준다.

            정지 해제는 users.suspend_reason 을 지우므로, 해제된 뒤 "왜 정지했었나"를 답할 수
            있는 곳은 이 원장뿐이다. 재정지 판단과 이의 제기 대응이 이 조회에 걸려 있다.

            **reason 은 관리자 내부 메모다** — 사용자에게 보여주는 화면으로 그대로 넘기지 않는다.
            """)
    @GetMapping("/{userUuid}/status-histories")
    public ApiResponse<List<AdminUserStatusHistoryResponse>> statusHistories(
            @PathVariable String userUuid) {
        return ApiResponse.success(adminUserService.getStatusHistories(userUuid));
    }

    @Operation(summary = "전화번호 열람", description = """
            복호화된 번호를 돌려주고 **그 사실을 기록으로 남긴다**(누가·누구를·언제).

            조회가 아니라 POST인 이유가 이것이다 — 이 호출은 부작용이 있는 행위다.
            화면의 "열람 기록이 남습니다"가 참이 되는 지점이며,
            마스킹 토글을 열 때 한 번만 부른다.
            """)
    @PostMapping("/{userUuid}/phone-reveal")
    public ApiResponse<AdminPhoneRevealResponse> revealPhone(
            @CurrentUser AuthenticatedUser principal, @PathVariable String userUuid) {
        return ApiResponse.success(adminUserService.revealPhone(principal.userUuid(), userUuid));
    }

    @Operation(summary = "정지", description = """
            로그인·API 전면 차단 + 리프레시 토큰 전량 회수. 멱등이다.
            자기 자신은 정지할 수 없다(`SELF_SUSPEND` 400).

            `reason`은 선택이며 **내부 기록**이다 — 정지된 사용자가 보는 것은
            "고객센터로 문의해 주세요" 하나다.

            공인중개사 사무소 대표자(파트너)면 **그 사무소가 맡은 진행 중 임대 요청이 전부 대기로 돌아가고**
            유학생마다 "다시 대기" 알림이 간다.
            """)
    @PostMapping("/{userUuid}/suspend")
    public ApiResponse<Void> suspend(
            @CurrentUser AuthenticatedUser principal, @PathVariable String userUuid,
            @Valid @RequestBody(required = false) UserSuspendRequest request) {
        adminUserService.suspend(principal.userUuid(), userUuid,
                request == null ? null : request.reason());
        return ApiResponse.ok();
    }

    @Operation(summary = "정지 해제", description = """
            멱등 — 정지 상태가 아니면 조용히 지나간다. 정지 시각·사유도 함께 지워진다.
            정지 때 대기로 돌아간 임대 요청은 되찾지 않는다 — 그사이 다른 사무소가 잡았을 수 있다.
            """)
    @PostMapping("/{userUuid}/unsuspend")
    public ApiResponse<Void> unsuspend(
            @CurrentUser AuthenticatedUser principal, @PathVariable String userUuid) {
        adminUserService.unsuspend(principal.userUuid(), userUuid);
        return ApiResponse.ok();
    }
}
