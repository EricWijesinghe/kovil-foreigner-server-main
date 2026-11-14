package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.community.dto.response.AdminContentResponse;
import com.kovil.domain.community.service.CommunityModerationService;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.community.dto.response.AdminReportResponse;
import com.kovil.domain.community.dto.response.AdminReportTargetResponse;
import com.kovil.domain.community.dto.response.PostSummaryResponse;
import com.kovil.domain.community.enums.ReportReasonCode;
import com.kovil.domain.community.enums.ReportStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Admin · Community", description = """
        신고 처리·블라인드 (**ADMIN 전용**).

        노출 판정은 대상의 status가 소유한다 — 신고 행은 이력으로 불변이다.
        신고 대기열 화면은 관리자 웹(#24)에서 붙는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/community")
@PreAuthorize("hasAuthority('PERM_COMMUNITY_MODERATE')")
public class AdminCommunityController {

    private final CommunityModerationService moderationService;

    @Operation(summary = "신고 처리 대기열", description = """
            **여기가 비어 있는 것이 정상이다.** 오래 기다린 신고가 먼저 나온다.
            `sameTargetPendingCount`가 2 이상이면 여러 사람이 지목한 대상 — 우선 판단 후보다.
            승인 한 번이 같은 대상의 대기 신고를 전부 닫으므로 남은 행도 함께 사라진다.

            `targetType`+`targetUuid`를 주면 **한 대상의 신고만** 모아 본다 —
            신고 3건 각각의 사유 설명을 읽어야 판단이 선다. `status`를 바꿔 처리된 건도 조회한다.

            **신고자는 어떤 응답에도 없다** — 관리자에게도 보이지 않는다.
            """)
    @GetMapping("/reports")
    public ApiResponse<PageResponse<AdminReportResponse>> reportQueue(
            @Parameter(description = "PENDING · RESOLVED · DISMISSED. 기본 PENDING")
            @RequestParam(required = false, defaultValue = "PENDING")
            ReportStatus status,
            @Parameter(description = "신고 사유 코드 필터")
            @RequestParam(required = false)
            ReportReasonCode reasonCode,
            @Parameter(description = "POST · COMMENT. targetUuid와 함께 쓴다(기본 POST)")
            @RequestParam(required = false) String targetType,
            @Parameter(description = "신고 대상 uuid — 같은 대상의 신고를 모아 본다")
            @RequestParam(required = false) String targetUuid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                moderationService.findQueue(status, reasonCode, targetType, targetUuid, Paging.of(page, size)));
    }

    @Operation(summary = "신고 대상 대기열 (묶음)", description = """
            M1 목록의 실제 작업 단위 — **같은 대상의 대기 신고 N건을 1행으로** 묶는다.
            많이 신고된 대상이 먼저, 같으면 오래 기다린 대상이 먼저다.
            개별 신고의 사유·설명은 기존 목록을 `targetUuid`로 좁혀 읽는다.
            """)
    @GetMapping("/reports/targets")
    public ApiResponse<PageResponse<AdminReportTargetResponse>> reportTargetQueue(
            @Parameter(description = "신고 사유 코드 필터")
            @RequestParam(required = false)
            ReportReasonCode reasonCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(moderationService.findTargetQueue(reasonCode, Paging.of(page, size)));
    }

    @Operation(summary = "신고 단건", description = """
            딥링크의 축이다 — 처리된 건(RESOLVED·DISMISSED)도 같은 경로로 다시 본다.
            `targetUuid`로 대상 원문·블라인드 해제까지 되짚어 갈 수 있다.

            - `REPORT_NOT_FOUND`(404) — 없는 신고.
            """)
    @GetMapping("/reports/{reportUuid}")
    public ApiResponse<AdminReportResponse> getReport(
            @PathVariable String reportUuid) {
        return ApiResponse.success(moderationService.getReportDetail(reportUuid));
    }

    @Operation(summary = "글 원문 (블라인드 포함)", description = """
            신고 대상 글의 원문 전체다. ★ **블라인드·삭제된 글도 그대로 내려온다** —
            유저 조회(`GET /community/posts/{uuid}`)는 이것들을 감추므로 재검토가 불가능하다.

            **조회수를 올리지 않는다** — 관리자의 열람이 인기 순위를 흔들면 안 된다.
            작성자 식별자는 여기에도 없다(INV-10).
            """)
    @GetMapping("/posts/{postUuid}")
    public ApiResponse<AdminContentResponse> getPost(
            @CurrentUser AuthenticatedUser principal, @PathVariable String postUuid) {
        return ApiResponse.success(moderationService.getPost(principal.userUuid(), postUuid));
    }

    @Operation(summary = "댓글 원문 (블라인드 포함)", description = """
            댓글은 맥락이 없으면 판단할 수 없다 — **달린 글이 `post`에 함께** 온다.
            """)
    @GetMapping("/comments/{commentUuid}")
    public ApiResponse<AdminContentResponse> getComment(
            @CurrentUser AuthenticatedUser principal, @PathVariable String commentUuid) {
        return ApiResponse.success(moderationService.getComment(principal.userUuid(), commentUuid));
    }

    @Operation(summary = "같은 작성자의 다른 글", description = """
            반복 스팸의 판정 재료다. 이 글을 쓴 사람의 다른 글을 최신순으로 준다.

            ★ **작성자 uuid를 응답에 싣지 않는다**(INV-10) — 전면 익명은 관리자 화면에서도
            유지되고, 서버는 "같은 작성자"라는 **관계만** 풀어 준다.
            글 검색 화면이 없는 이유도 같다 — 진입은 언제나 신고 대기열에서 시작한다.
            """)
    @GetMapping("/posts/{postUuid}/sibling-posts")
    public ApiResponse<PageResponse<PostSummaryResponse>> siblingPosts(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String postUuid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                moderationService.findSiblingPosts(principal.userUuid(), postUuid, Paging.of(page, size)));
    }

    @Operation(summary = "신고 승인 = 블라인드 집행", description = """
            대상(글·댓글)을 숨기고 **같은 대상의 대기 중 신고를 전부 RESOLVED로 닫는다** —
            신고 10건짜리 글을 10번 처리하게 하지 않는다.
            """)
    @PostMapping("/reports/{reportUuid}/resolve")
    public ApiResponse<Void> resolve(
            @CurrentUser AuthenticatedUser principal, @PathVariable String reportUuid) {
        moderationService.resolve(principal.userUuid(), reportUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "신고 기각", description = "그 신고 한 건만 닫는다. 처리 후 불변 — 재처리는 `REPORT_ALREADY_HANDLED`(409).")
    @PostMapping("/reports/{reportUuid}/dismiss")
    public ApiResponse<Void> dismiss(
            @CurrentUser AuthenticatedUser principal, @PathVariable String reportUuid) {
        moderationService.dismiss(principal.userUuid(), reportUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "글 블라인드", description = "신고 없이도 순찰로 내릴 수 있다. 멱등.")
    @PostMapping("/posts/{postUuid}/blind")
    public ApiResponse<Void> blindPost(@PathVariable String postUuid) {
        moderationService.blindPost(postUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "글 블라인드 해제")
    @PostMapping("/posts/{postUuid}/unblind")
    public ApiResponse<Void> unblindPost(@PathVariable String postUuid) {
        moderationService.unblindPost(postUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "댓글 블라인드", description = "표시 개수에서 빠지므로 글의 댓글 수도 내려간다.")
    @PostMapping("/comments/{commentUuid}/blind")
    public ApiResponse<Void> blindComment(@PathVariable String commentUuid) {
        moderationService.blindComment(commentUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "댓글 블라인드 해제")
    @PostMapping("/comments/{commentUuid}/unblind")
    public ApiResponse<Void> unblindComment(@PathVariable String commentUuid) {
        moderationService.unblindComment(commentUuid);
        return ApiResponse.ok();
    }
}
