package com.kovil.domain.listing.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.listing.dto.request.ListingBlockRequest;
import com.kovil.domain.listing.dto.response.AdminListingDetailResponse;
import com.kovil.domain.listing.dto.response.AdminListingReportResponse;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.service.AdminListingModerationService;
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

@Tag(name = "Admin · Listing", description = """
        매물 신고 처리 · 차단 (`LISTING_MODERATE`).

        노출 판정은 매물의 status 가 소유한다 — 신고 행은 이력이다.
        **신고자는 어떤 응답에도 없다**(관리자 포함). 차단 사유는 관리자 내부 기록이다 — 중개사 응답 · 알림에 싣지 않는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('PERM_LISTING_MODERATE')")
public class AdminListingController {

    private final AdminListingModerationService moderationService;

    @Operation(summary = "신고 대기열", description = """
            `status=PENDING`(기본)이 처리 대기열이다 — 오래 기다린 신고가 먼저. RESOLVED · DISMISSED 는 최신부터.
            `sameListingPendingCount` 가 2 이상이면 여러 사람이 지목한 매물이다.
            차단 한 번이 그 매물의 대기 신고를 전부 닫으므로 남은 행도 함께 사라진다.
            """)
    @GetMapping("/listing-reports")
    public ApiResponse<PageResponse<AdminListingReportResponse>> reportQueue(
            @Parameter(description = "PENDING · RESOLVED · DISMISSED. 기본 PENDING")
            @RequestParam(defaultValue = "PENDING") ListingReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(moderationService.findQueue(status, Paging.of(page, size)));
    }

    @Operation(summary = "신고 기각", description = """
            그 신고 한 건만 닫는다. 매물은 그대로다.

            - `LISTING_REPORT_NOT_FOUND`(404)
            - `LISTING_REPORT_ALREADY_HANDLED`(409) — 이미 처리됐다(차단으로 닫혔거나 기각됐다).
            """)
    @PostMapping("/listing-reports/{reportUuid}/dismiss")
    public ApiResponse<Void> dismiss(
            @CurrentUser AuthenticatedUser principal, @PathVariable String reportUuid) {
        moderationService.dismiss(principal.userUuid(), reportUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "매물 상세 (관리자)", description = """
            상태와 상관없이 본다(차단 · 초안 · 거래 완료 포함). 조회수를 올리지 않는다.
            신고는 최신 100건까지 싣는다 — 전체 수는 `reportCount` · `pendingReportCount`.

            - `LISTING_NOT_FOUND`(404)
            """)
    @GetMapping("/listings/{listingUuid}")
    public ApiResponse<AdminListingDetailResponse> getListing(@PathVariable String listingUuid) {
        return ApiResponse.success(moderationService.getDetail(listingUuid));
    }

    @Operation(summary = "차단", description = """
            → `BLOCKED`. 공개 · 예약 중 · 초안 · 내림 · 만료 어디서든. **그 매물의 대기 신고를 전부 RESOLVED 로 닫는다.**
            중개사에게 `LISTING_BLOCKED` 알림이 간다 — 사유 없이 "운영 정책에 따라 차단 · 고객센터 문의"만.
            차단된 매물은 중개사가 고치거나 다시 올릴 수 없다.

            - `LISTING_NOT_FOUND`(404)
            - `LISTING_NOT_BLOCKABLE`(409) — 이미 차단됐거나 거래 완료 · 자동 종료된 매물, 또는 연장 기간(만료 + 7일)이 지난 만료 매물.
            - `CONFLICT`(409) — 같은 순간의 중개사 수정 · 다른 관리자 처리와 겹쳤다. 상세를 다시 연다.
            - `INVALID_REQUEST`(400) — 사유가 없거나 500자를 넘는다.
            """)
    @PostMapping("/listings/{listingUuid}/block")
    public ApiResponse<Void> block(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid,
            @Valid @RequestBody ListingBlockRequest request) {
        moderationService.block(principal.userUuid(), listingUuid, request.reason());
        return ApiResponse.ok();
    }

    @Operation(summary = "차단 해제", description = """
            `BLOCKED` → `HIDDEN`(내림). 바로 공개되지 않는다 — 중개사가 고쳐 다시 게시하면 게시 관문을 다시 탄다.
            해제는 중개사에게 알리지 않는다.

            - `LISTING_NOT_FOUND`(404)
            - `LISTING_NOT_BLOCKED`(409) — 차단된 매물이 아니다.
            - `CONFLICT`(409) — 같은 순간의 다른 처리와 겹쳤다. 상세를 다시 연다.
            """)
    @PostMapping("/listings/{listingUuid}/unblock")
    public ApiResponse<Void> unblock(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        moderationService.unblock(principal.userUuid(), listingUuid);
        return ApiResponse.ok();
    }
}