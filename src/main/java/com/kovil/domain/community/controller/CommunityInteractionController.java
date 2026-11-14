package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.community.dto.request.ReportCreateRequest;
import com.kovil.domain.community.service.CommunityBlockService;
import com.kovil.domain.community.service.CommunityLikeService;
import com.kovil.domain.community.service.CommunityReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community · Interaction", description = """
        좋아요·신고·차단. 전부 익명을 깨지 않는다 —
        좋아요는 수만 보이고, 신고는 상대가 모르고, 차단은 계정이 아니라 글·댓글을 지목한다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community")
public class CommunityInteractionController {

    private final CommunityLikeService likeService;
    private final CommunityReportService reportService;
    private final CommunityBlockService blockService;

    @Operation(summary = "글 좋아요", description = "멱등 — 두 번 눌러도 한 번이다.")
    @PostMapping("/posts/{postUuid}/like")
    public ApiResponse<Void> likePost(
            @CurrentUser AuthenticatedUser principal, @PathVariable String postUuid) {
        likeService.likePost(principal.userUuid(), postUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "글 좋아요 취소", description = "안 눌렀어도 조용히 지나간다.")
    @DeleteMapping("/posts/{postUuid}/like")
    public ApiResponse<Void> unlikePost(
            @CurrentUser AuthenticatedUser principal, @PathVariable String postUuid) {
        likeService.unlikePost(principal.userUuid(), postUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "댓글 좋아요")
    @PostMapping("/comments/{commentUuid}/like")
    public ApiResponse<Void> likeComment(
            @CurrentUser AuthenticatedUser principal, @PathVariable String commentUuid) {
        likeService.likeComment(principal.userUuid(), commentUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "댓글 좋아요 취소")
    @DeleteMapping("/comments/{commentUuid}/like")
    public ApiResponse<Void> unlikeComment(
            @CurrentUser AuthenticatedUser principal, @PathVariable String commentUuid) {
        likeService.unlikeComment(principal.userUuid(), commentUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "글 신고", description = "같은 글 재신고는 `REPORT_DUPLICATE`(409). 상대는 알 수 없다.")
    @PostMapping("/posts/{postUuid}/report")
    public ApiResponse<Void> reportPost(
            @CurrentUser AuthenticatedUser principal, @PathVariable String postUuid,
            @Valid @RequestBody ReportCreateRequest request) {
        reportService.reportPost(principal.userUuid(), postUuid, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "댓글 신고")
    @PostMapping("/comments/{commentUuid}/report")
    public ApiResponse<Void> reportComment(
            @CurrentUser AuthenticatedUser principal, @PathVariable String commentUuid,
            @Valid @RequestBody ReportCreateRequest request) {
        reportService.reportComment(principal.userUuid(), commentUuid, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "글 작성자 차단", description = """
            **계정이 아니라 이 글을 지목한다** — 서버가 내부에서 변환하므로 상대가 누군지는 끝까지 모른다.
            효과: 그 사람의 글·댓글이 내 목록에서 사라진다(단방향). 자기 글이면 `SELF_BLOCK`(400).
            """)
    @PostMapping("/posts/{postUuid}/block-author")
    public ApiResponse<Void> blockPostAuthor(
            @CurrentUser AuthenticatedUser principal, @PathVariable String postUuid) {
        blockService.blockPostAuthor(principal.userUuid(), postUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "댓글 작성자 차단")
    @PostMapping("/comments/{commentUuid}/block-author")
    public ApiResponse<Void> blockCommentAuthor(
            @CurrentUser AuthenticatedUser principal, @PathVariable String commentUuid) {
        blockService.blockCommentAuthor(principal.userUuid(), commentUuid);
        return ApiResponse.ok();
    }
}
