package com.kovil.domain.partner.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.partner.dto.request.OfficeChangeApproveRequest;
import com.kovil.domain.partner.dto.request.OfficeChangeRejectRequest;
import com.kovil.domain.partner.dto.response.AdminOfficeChangeRequestDetailResponse;
import com.kovil.domain.partner.dto.response.AdminOfficeChangeRequestSummaryResponse;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.service.AdminOfficeChangeRequestService;
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

@Tag(name = "Admin · Office Change Request", description = """
        승인된 중개사무소의 정보 변경 심사 (`PARTNER_APPROVE`).

        승인된 공인중개사가 심사 대상(등록번호 · 상호 · 대표자 · 소재지 · 사업자번호 · 등록증)을 바꾸면 **변경 신청**이 생긴다.
        심사 동안 그 사무소의 부문 · 매물 · 기능은 **그대로**다(사무소 값도 지금 값 그대로). 승인하면 바꿀 값이 사무소에 적용되고,
        반려하면 사무소는 그대로다(옛 번호도 그대로 쓴다). 대표번호는 변경 신청 대상이 아니다(파트너가 바로 바꾼다).

        **승인 · 반려에는 화면에서 본 `version` 을 싣는다**(대기열 · 상세 응답의 값 그대로). 그사이 신청자가 다시 냈거나
        다른 관리자가 처리했으면 `OFFICE_CHANGE_REQUEST_CHANGED`(409) — 상세를 다시 열어 보고 판정한다.

        승인 · 반려는 **파트너에게 푸시 + 알림함으로 알린다**(`OFFICE_CHANGE_APPROVED` · `OFFICE_CHANGE_REJECTED`, 딥링크 `/partner/me`).
        반려 사유는 알림 본문에 실린다. 세션은 끊지 않는다.

        상세의 `regNoSuspensionHistory` · 목록의 `regNoSuspendedBefore` 는 **바꿀 번호 · 사무소의 지금 번호 · 이 사무소 자신**의
        정지 이력이다(정지됐던 사무소가 번호를 바꿔도 자기 이력이 따라온다) — "정지 이력이 있는 중개등록번호입니다" 경고만 하고 막지 않는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/office-change-requests")
@PreAuthorize("hasAuthority('PERM_PARTNER_APPROVE')")
public class AdminOfficeChangeRequestController {

    private final AdminOfficeChangeRequestService service;

    @Operation(summary = "변경 심사 대기열", description = """
            `status` 를 비우면 `PENDING`(심사 대기)이다. 제출이 오래된 순 — 대기 중에 다시 낸 건은 뒤로 간다.
            `APPROVED` · `REJECTED` 로 지난 판정을, `CANCELED` 로 신청자가 거둬들인 건을 본다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<AdminOfficeChangeRequestSummaryResponse>> list(
            @Parameter(description = "PENDING(기본) · APPROVED · REJECTED · CANCELED(신청자가 거둬들임)")
            @RequestParam(defaultValue = "PENDING") OfficeChangeRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.findQueue(status, Paging.of(page, size)));
    }

    @Operation(summary = "변경 심사 상세", description = """
            사무소의 **지금 값**(`current`)과 **바꿀 값**(`requested`)을 나란히 준다. 새 등록증 단기 열람 URL(`license`) ·
            신청자 부문 상태(`divisionStatus` — `APPROVED` 가 아니면 승인할 수 없다) ·
            **바꿀 번호의 다른 신청**(`sameRegNoApplications` — 첫 심사 신청 · 번호를 잡은 사무소 · 다른 변경 신청, `kind` 로 가른다) ·
            **바꿀 번호 · 지금 번호 · 이 사무소의 정지 이력**(`regNoSuspensionHistory`) · 승인 · 반려에 실을 `version`.
            등록증 URL 은 만료된다 — 캐시하거나 공유하지 않는다.
            """)
    @GetMapping("/{requestUuid}")
    public ApiResponse<AdminOfficeChangeRequestDetailResponse> get(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid) {
        return ApiResponse.success(service.getDetail(requestUuid, principal));
    }

    @Operation(summary = "변경 승인", description = """
            바꿀 값을 사무소에 적용한다. **등록번호가 바뀌면 사무소가 새 번호를 잡고 옛 번호를 놓는다.**
            옛 등록증은 폐기된다(같은 파일을 낸 경우는 그대로). 매물은 바뀐 사무소 정보로 그대로 공개된다.

            **에러 분기**
            - `OFFICE_CHANGE_REQUEST_CHANGED`(409) — 화면을 연 뒤 신청자가 다시 냈거나 다른 관리자가 처리했다. 상세를 다시 연다.
            - `OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE`(409) — 대기 건이 아니다(이미 판정됨 · 탈퇴로 닫힘).
            - `OFFICE_CHANGE_DIVISION_NOT_APPROVED`(409) — 그 사무소의 공인중개사 부문이 승인 상태가 아니다(정지 등). 복구 뒤에 승인한다.
            - `OFFICE_REG_NO_DUPLICATED`(409) — 바꿀 번호를 다른 사무소가 승인받아 쓰고 있다.
            - `CONFLICT`(409) — 같은 순간의 다른 처리(대표번호 변경 등)와 겹쳤다. 상세를 다시 연다.
            - `OFFICE_CHANGE_REQUEST_NOT_FOUND`(404) · `INVALID_REQUEST`(400) — `version` 이 없다.
            """)
    @PostMapping("/{requestUuid}/approve")
    public ApiResponse<AdminOfficeChangeRequestSummaryResponse> approve(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid,
            @Valid @RequestBody OfficeChangeApproveRequest request) {
        return ApiResponse.success(service.approve(principal.userUuid(), requestUuid, request.version()));
    }

    @Operation(summary = "변경 반려", description = """
            변경 신청만 닫는다 — **사무소는 그대로**(옛 번호도 그대로 쓴다). 변경 신청의 등록증은 폐기된다(사무소가 지금 쓰는
            파일이면 그대로). 사유는 **신청자에게 그대로 보인다**(알림 본문에도). 파트너는 고쳐서 다시 신청할 수 있다.

            - `OFFICE_CHANGE_REQUEST_CHANGED`(409) — 화면을 연 뒤 바뀌었다. 상세를 다시 연다.
            - `OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE`(409) — 대기 건이 아니다.
            - `OFFICE_CHANGE_REQUEST_NOT_FOUND`(404) · `INVALID_REQUEST`(400) — 사유 · `version` 이 없다.
            """)
    @PostMapping("/{requestUuid}/reject")
    public ApiResponse<AdminOfficeChangeRequestSummaryResponse> reject(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid,
            @Valid @RequestBody OfficeChangeRejectRequest request) {
        return ApiResponse.success(service.reject(principal.userUuid(), requestUuid, request.reason(),
                request.version()));
    }
}