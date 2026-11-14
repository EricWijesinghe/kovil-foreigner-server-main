package com.kovil.domain.verification.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.verification.dto.request.VerificationApproveRequest;
import com.kovil.domain.verification.dto.request.VerificationRejectRequest;
import com.kovil.domain.verification.dto.request.VerificationRevokeRequest;
import com.kovil.domain.verification.dto.response.AdminVerificationResponse;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.service.VerificationReviewService;
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

@Tag(name = "Admin · Verification", description = """
        외국인 인증 심사 (**ADMIN 전용**).

        **심사는 사람이 전량 수행한다** — 자동 판독이 없다(FR-205).
        이 서비스의 신원 확인 품질이 곧 사업 리스크의 크기이므로, 서류를 눈으로 확인하고 판단한다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/verifications")
@PreAuthorize("hasAuthority('PERM_VERIFICATION_REVIEW')")
public class AdminVerificationController {

    private final VerificationReviewService verificationReviewService;

    @Operation(summary = "심사 대기열", description = """
            **오래 기다린 건이 먼저 나온다**(신청 순서 = 처리 순서).

            **화면 설계**
            - 서류 이미지는 응답에 없다. 각 `fileUuid`로 `/files/{fileUuid}/url`을 호출해
              **단기 URL을 받아 연다**(INV-08 — 민감 파일은 공개 URL을 갖지 않는다).
            - `requiredDocumentCodes`는 **제출 시점의 요건**이다. 지금 요건과 다를 수 있으며,
              심사 근거는 그때 요구한 것이다.
            - `status`를 바꿔 승인·반려 건도 조회할 수 있다(감사·재확인용).
            """)
    @GetMapping
    public ApiResponse<PageResponse<AdminVerificationResponse>> list(
            @Parameter(description = "PENDING_REVIEW · APPROVED · REJECTED · REVOKED")
            @RequestParam(defaultValue = "PENDING_REVIEW") VerificationStatus status,
            @Parameter(description = "체류 신분 코드 필터. 신분마다 볼 서류가 달라 묶어 처리하는 편이 빠르다.",
                    example = "STUDENT")
            @RequestParam(required = false) String residentStatusCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                verificationReviewService.findByStatus(status, residentStatusCode, Paging.of(page, size)));
    }

    @Operation(summary = "승인 무효화", description = """
            잘못 승인한 건을 되돌린다. 상태가 `REVOKED`가 되고 **그 즉시 미인증으로 취급**되어
            임대 요청 게이트가 다시 닫힌다. 유저에게는 알림이 나간다.

            승인 이력(`reviewedAt`)은 **지우지 않는다** — 누가 승인했는지가 사라지면
            무효화 자체를 감사할 수 없다. 사유는 `revokeReason`에 따로 남는다.

            - `VERIFICATION_NOT_APPROVED`(409) — 승인 건이 아니다.
            """)
    @PostMapping("/{verificationUuid}/revoke")
    public ApiResponse<AdminVerificationResponse> revoke(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String verificationUuid,
            @Valid @RequestBody VerificationRevokeRequest request) {
        return ApiResponse.success(verificationReviewService.revoke(
                principal.userUuid(), verificationUuid, request.reason()));
    }

    @Operation(summary = "승인", description = """
            **되돌릴 수 없다.** 승인하면 사용자가 임대 요청 단계로 진입한다.

            승인 시 **신청자의 국적이 심사값으로 확정**된다(FR-210) — 자기 신고값이 아니라
            서류로 확인한 값이 정본이 된다.

            `expiresAt`을 넣으면 그날 이후 인증이 자동으로 무효가 된다. 비우면 무기한이다 —
            **체류 자격 만료일이 서류에 보이면 그 날짜를 넣어 두는 편이 안전하다.**

            - `VERIFICATION_NOT_PENDING`(409) — 이미 심사된 건. 목록을 새로고침한다.
            - `VERIFICATION_EXPIRY_IN_PAST`(400) — 지난 날짜.
            - `CONFLICT`(409) — 사용자가 동시에 재제출했다. 목록을 새로고침한다.
            """)
    @PostMapping("/{verificationUuid}/approve")
    public ApiResponse<AdminVerificationResponse> approve(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String verificationUuid,
            @Valid @RequestBody VerificationApproveRequest request) {
        return ApiResponse.success(verificationReviewService.approve(
                principal.userUuid(), verificationUuid, request.expiresAt()));
    }

    @Operation(summary = "반려", description = """
            **사유는 사용자에게 그대로 노출된다**(FR-206). 무엇을 고쳐야 통과하는지 적을 것 —
            사용자는 이 문구만 보고 재제출한다.

            반려된 건은 수정되지 않는다. 사용자가 다시 내면 **새 신청 행**이 만들어진다(이력 보존).

            - `REJECT_REASON_REQUIRED`(400) — 사유 누락.
            - `VERIFICATION_NOT_PENDING`(409) — 이미 심사된 건.
            """)
    @PostMapping("/{verificationUuid}/reject")
    public ApiResponse<AdminVerificationResponse> reject(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String verificationUuid,
            @Valid @RequestBody VerificationRejectRequest request) {
        return ApiResponse.success(verificationReviewService.reject(
                principal.userUuid(), verificationUuid, request.reason()));
    }
}
