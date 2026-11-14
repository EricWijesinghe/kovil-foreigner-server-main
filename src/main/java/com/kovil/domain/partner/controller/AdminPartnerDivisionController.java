package com.kovil.domain.partner.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.partner.dto.request.PartnerDivisionApproveRequest;
import com.kovil.domain.partner.dto.request.PartnerDivisionReasonRequest;
import com.kovil.domain.partner.dto.request.PartnerDivisionRejectRequest;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionDetailResponse;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionSummaryResponse;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.service.AdminPartnerDivisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin · Partner Division", description = """
        파트너 부문 심사 (`PARTNER_APPROVE`). 공인중개사 승인 대기열이 여기다.

        **승인 · 반려에는 화면에서 본 `version` 을 싣는다**(대기열 · 상세 응답의 값 그대로). 그사이 신청자가 다시 냈거나
        다른 관리자가 처리했으면 `PARTNER_DIVISION_CHANGED`(409) — 상세를 다시 열어 보고 판정한다.

        **세션은 정지에만 끊는다.** 승인 · 반려 · 복구는 파트너를 로그아웃시키지 않는다 — 파트너스 앱이
        리프레시(`POST /api/v1/auth/token/refresh`)로 새 토큰을 받으면 새 상태의 기능이 열린다.
        정지하면 그 파트너의 세션이 끊기고 그 사무소 공개 매물이 즉시 노출에서 빠진다. 복구하면 그대로 돌아온다
        (매물 상태는 건드리지 않는다).

        승인 · 반려 · 정지 · 복구는 **파트너에게 푸시 + 알림함으로 알린다**(`PARTNER_DIVISION_*`). 반려 사유는 알림 본문에 실리고,
        정지 사유는 싣지 않는다(내부 기록).

        같은 중개등록번호로 여러 명이 신청할 수 있고 **한 명만 승인**한다 — 상세의 `sameRegNoApplications` 를 본다.
        승인 · 정지된 사무소가 그 번호를 쓰는 동안 다른 신청의 승인은 `OFFICE_REG_NO_DUPLICATED`(409)다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/partner-divisions")
@PreAuthorize("hasAuthority('PERM_PARTNER_APPROVE')")
public class AdminPartnerDivisionController {

    private final AdminPartnerDivisionService service;

    @Operation(summary = "심사 대기열", description = """
            **제출된 것만** 나온다(가입만 하고 서류를 내지 않은 건은 빠진다). 제출이 오래된 순.
            `status=PENDING` 이 심사 대기열이다. 비우면 제출된 전체(승인 · 반려 · 정지 포함).
            `regNoSuspendedBefore=true` 면 그 중개등록번호 또는 그 사무소 자신이 정지된 적이 있다 — "정지 이력이 있는 중개등록번호입니다" 경고(막지 않는다).
            승인된 사무소의 정보 변경은 여기가 아니라 `/api/v1/admin/office-change-requests` 에 온다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<AdminPartnerDivisionSummaryResponse>> list(
            @Parameter(description = "PENDING · APPROVED · REJECTED · SUSPENDED. 비우면 전체")
            @RequestParam(required = false) PartnerDivisionStatus status,
            @Parameter(description = "REAL_ESTATE_BROKER. 비우면 전체")
            @RequestParam(required = false) PartnerDivisionType division,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.findSubmitted(status, division, Paging.of(page, size)));
    }

    @Operation(summary = "심사 상세", description = """
            신청자 · 사무소 정보 · **등록증 단기 열람 URL** · 공개 중인 매물 수(정지의 영향 범위) ·
            **같은 등록번호의 다른 신청**(`sameRegNoApplications` — 번호를 잡고 있는 줄이 먼저, 그 번호로 옮기려는 다른 사무소의
            대기 중 변경 신청도 `kind=OFFICE_CHANGE_REQUEST` 로 실린다) ·
            **그 번호 · 그 사무소의 정지 이력**(`regNoSuspensionHistory` — 비어 있지 않으면 경고, 막지 않는다) · 승인 · 반려에 실을 `version`.
            등록증 URL 은 만료된다 — 캐시하거나 공유하지 않는다.
            """)
    @GetMapping("/{divisionUuid}")
    public ApiResponse<AdminPartnerDivisionDetailResponse> get(
            @CurrentUser AuthenticatedUser principal, @PathVariable String divisionUuid) {
        return ApiResponse.success(service.getDetail(divisionUuid, principal));
    }

    @Operation(summary = "승인", description = """
            본문에 화면에서 본 `version` 을 싣는다. 승인하면 그 사무소가 중개등록번호를 잡는다(다른 신청은 승인할 수 없게 된다).
            파트너의 세션은 끊지 않는다 — 앱이 리프레시로 새 토큰을 받으면 기능이 열린다.

            **에러 분기**
            - `PARTNER_DIVISION_CHANGED`(409) — 화면을 연 뒤 신청자가 다시 냈거나 다른 관리자가 처리했다. 상세를 다시 연다.
            - `PARTNER_DIVISION_NOT_REVIEWABLE`(409) — 제출된 대기 건이 아니다.
            - `PARTNER_NOT_ACTIVE`(409) — 정지 · 탈퇴 계정은 승인할 수 없다.
            - `OFFICE_REG_NO_DUPLICATED`(409) — 다른 사무소가 이 번호를 승인받아 쓰고 있다(`sameRegNoApplications` 의 `claimed`).
            - `CONFLICT`(409) — 같은 순간의 다른 처리와 겹쳤다. 상세를 다시 연다.
            - `INVALID_REQUEST`(400) — `version` 이 없다.
            """)
    @PostMapping("/{divisionUuid}/approve")
    public ApiResponse<AdminPartnerDivisionSummaryResponse> approve(
            @CurrentUser AuthenticatedUser principal, @PathVariable String divisionUuid,
            @Valid @RequestBody PartnerDivisionApproveRequest request) {
        return ApiResponse.success(service.approve(principal.userUuid(), divisionUuid, request.version()));
    }

    @Operation(summary = "반려", description = """
            사유는 **신청자에게 그대로 보인다.** 본문에 화면에서 본 `version` 을 싣는다.
            계정은 그대로 남고, 신청자가 고쳐 다시 내면 대기열로 돌아온다. 세션은 끊지 않는다.

            - `PARTNER_DIVISION_CHANGED`(409) — 화면을 연 뒤 바뀌었다. 상세를 다시 연다.
            - `PARTNER_DIVISION_NOT_REVIEWABLE`(409) — 제출된 대기 건이 아니다.
            - `INVALID_REQUEST`(400) — 사유 · `version` 이 없다.
            """)
    @PostMapping("/{divisionUuid}/reject")
    public ApiResponse<AdminPartnerDivisionSummaryResponse> reject(
            @CurrentUser AuthenticatedUser principal, @PathVariable String divisionUuid,
            @Valid @RequestBody PartnerDivisionRejectRequest request) {
        return ApiResponse.success(service.reject(principal.userUuid(), divisionUuid, request.reason(),
                request.version()));
    }

    @Operation(summary = "정지", description = """
            승인된 부문만 정지한다(`PARTNER_DIVISION_NOT_APPROVED` 409). 그 부문의 기능이 닫히고
            **그 사무소 공개 매물이 즉시 노출에서 빠진다.** 사유는 내부 기록이다(파트너에게 보이지 않는다).
            **그 파트너의 세션을 끊는다** — 낡은 토큰이 수명만큼 기능을 열어 두지 않게. `version` 은 받지 않는다.
            중개등록번호는 놓지 않는다 — 정지가 이어지는 동안 같은 번호의 다른 신청은 승인할 수 없다.
            그 번호를 **정지 이력**에 남긴다 — 나중에 같은 번호로 신청 · 변경 신청이 오면 심사 화면이 경고한다(복구해도 남는다).
            정지된 동안 그 파트너는 탈퇴할 수 없다(`PARTNER_SUSPENDED_CANNOT_WITHDRAW`) — 탈퇴로 번호를 풀어 정지를 피하지 못한다.
            **그 사무소가 맡은 진행 중 임대 요청은 전부 대기로 돌아가고**(다른 사무소가 잡을 수 있다) 유학생마다 "다시 대기" 알림이 간다.
            """)
    @PostMapping("/{divisionUuid}/suspend")
    public ApiResponse<AdminPartnerDivisionSummaryResponse> suspend(
            @CurrentUser AuthenticatedUser principal, @PathVariable String divisionUuid,
            @Valid @RequestBody PartnerDivisionReasonRequest request) {
        return ApiResponse.success(service.suspend(principal.userUuid(), divisionUuid, request.reason()));
    }

    @Operation(summary = "복구", description = """
            정지된 부문만 복구한다(`PARTNER_DIVISION_NOT_SUSPENDED` 409). 매물은 정지 전 상태 그대로 돌아온다.
            **정지 때 대기로 돌아간 임대 요청은 되찾지 않는다** — 그사이 다른 사무소가 잡았을 수 있다.
            정지 · 탈퇴 계정은 복구할 수 없다(`PARTNER_NOT_ACTIVE` 409). `version` 은 받지 않는다.
            세션은 끊지 않는다 — 앱이 리프레시로 새 토큰을 받으면 기능이 다시 열린다.
            """)
    @PostMapping("/{divisionUuid}/reinstate")
    public ApiResponse<AdminPartnerDivisionSummaryResponse> reinstate(
            @CurrentUser AuthenticatedUser principal, @PathVariable String divisionUuid) {
        return ApiResponse.success(service.reinstate(principal.userUuid(), divisionUuid));
    }
}
