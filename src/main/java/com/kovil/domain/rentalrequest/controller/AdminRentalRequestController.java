package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.web.Paging;
import com.kovil.domain.rentalrequest.dto.response.AdminRentalRequestResponse;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.service.AdminRentalRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin · Rental Request", description = """
        임대 요청 열람 (`RENTAL_REQUEST_VIEW`) — **읽기 전용**이다. 잡기 · 놓기 · 종료는 파트너스(공인중개사 사무소)가 한다.

        요청 본문에 신청자(uuid · 이름 · 계정 상태)와 맡은 사무소(uuid · 상호 · 등록번호) · 잡은 시각 · 종료 결과가 붙는다.
        **전화번호는 싣지 않는다** — 신청자 번호는 회원 상세의 번호 열람(`POST /api/v1/admin/users/{userUuid}/phone-reveal`)으로 본다.

        진행 중이던 요청은 사무소가 놓거나, 대표자가 탈퇴하거나, 부문 · 대표자 계정이 정지되면 **대기로 돌아간다**(`office` 가 비워진다).
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/rental-requests")
@PreAuthorize("hasAuthority('PERM_RENTAL_REQUEST_VIEW')")
public class AdminRentalRequestController {

    private final AdminRentalRequestService adminRentalRequestService;

    @Operation(summary = "임대 요청 목록", description = """
            **최신순.** 조건은 비우면 걸지 않는다.

            - `status` — 그 상태만.
            - `officeUuid` — 그 사무소가 맡은(맡았던) 요청만 — 진행 중 · 종료 · 맡은 뒤 취소된 것. 놓았거나 대기로 돌아간 요청은
              더는 그 사무소 것이 아니라 빠진다. **없는 사무소면 빈 목록**이다.
            - `query` — **요청번호 또는 신청자 이름**(회원 이름 · 여권 영문 성명)의 부분일치. 대소문자는 무시한다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<AdminRentalRequestResponse>> list(
            @Parameter(description = "SUBMITTED · IN_PROGRESS · CLOSED · CANCELED. 비우면 전체")
            @RequestParam(required = false) RentalRequestStatus status,
            @Parameter(description = "맡은 사무소 uuid")
            @RequestParam(required = false) String officeUuid,
            @Parameter(description = "요청번호 · 신청자 이름 부분일치")
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminRentalRequestService.search(
                status, officeUuid, query, Paging.of(page, size)));
    }

    @Operation(summary = "임대 요청 상세", description = """
            목록 한 줄에 **잡기 이력(`claimHistory`)** 이 붙은 모양이다 — 사무소가 잡고 · 놓고 · 종료하고, 맡을 사람이 사라져
            대기로 돌아간 순간들(일어난 순서대로). "이 요청을 잡았다 놓은 사무소"를 여기서 본다.
            없는 요청은 `RENTAL_REQUEST_NOT_FOUND`(404).
            """)
    @GetMapping("/{requestUuid}")
    public ApiResponse<AdminRentalRequestResponse> get(@PathVariable String requestUuid) {
        return ApiResponse.success(adminRentalRequestService.get(requestUuid));
    }
}
