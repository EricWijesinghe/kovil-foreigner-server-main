package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.community.dto.request.CommentCreateRequest;
import com.kovil.domain.community.dto.request.CommentUpdateRequest;
import com.kovil.domain.community.dto.response.CommentResponse;
import com.kovil.domain.community.service.CommunityCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Community · Comment", description = """
        댓글 — 깊이 2(댓글·대댓글). 익명 축은 `anonymousNo`(0=글쓴이) · `isAuthor` · `isMine` 셋뿐이다.

        같은 글 안에서 같은 사람은 항상 같은 번호를 받는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community")
public class CommunityCommentController {

    private final CommunityCommentService commentService;

    @Operation(summary = "댓글 목록", description = """
            최상위 작성순, 그 아래 대댓글 작성순.
            **대댓글이 있는 삭제 댓글은 자리(deleted=true)로 남는다** — 없는 삭제는 목록에서 빠진다.
            """)
    @GetMapping("/posts/{postUuid}/comments")
    public ApiResponse<List<CommentResponse>> findThread(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String postUuid) {
        return ApiResponse.success(commentService.findThread(principal.userUuid(), postUuid));
    }

    @Operation(summary = "댓글 작성", description = """
            `parentCommentUuid`를 채우면 대댓글. 대댓글에 다시 달면 `COMMENT_DEPTH_EXCEEDED`(400).
            글쓴이에게 알림이 간다(내 글에 내가 달면 없음).
            """)
    @PostMapping("/posts/{postUuid}/comments")
    public ApiResponse<CommentResponse> create(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String postUuid,
            @Valid @RequestBody CommentCreateRequest request) {
        return ApiResponse.success(commentService.create(principal.userUuid(), postUuid, request));
    }

    @Operation(summary = "댓글 수정", description = "본인만.")
    @PutMapping("/comments/{commentUuid}")
    public ApiResponse<CommentResponse> update(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String commentUuid,
            @Valid @RequestBody CommentUpdateRequest request) {
        return ApiResponse.success(commentService.update(principal.userUuid(), commentUuid, request));
    }

    @Operation(summary = "댓글 삭제", description = "본인만. 대댓글이 있으면 자리가 남는다.")
    @DeleteMapping("/comments/{commentUuid}")
    public ApiResponse<Void> delete(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String commentUuid) {
        commentService.delete(principal.userUuid(), commentUuid);
        return ApiResponse.ok();
    }
}
