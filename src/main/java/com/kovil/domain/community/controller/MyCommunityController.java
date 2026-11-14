package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.community.dto.response.PostSummaryResponse;
import com.kovil.domain.community.service.CommunityPostQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community · Me", description = "익명 게시판에서 내 글을 찾는 유일한 통로다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/community")
public class MyCommunityController {

    private final CommunityPostQueryService postQueryService;

    @Operation(summary = "내가 쓴 글", description = "최신순. 수정·삭제의 진입점이다.")
    @GetMapping("/posts")
    public ApiResponse<PageResponse<PostSummaryResponse>> findMyPosts(
            @CurrentUser AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(postQueryService.findMine(principal.userUuid(), Paging.of(page, size)));
    }
}
