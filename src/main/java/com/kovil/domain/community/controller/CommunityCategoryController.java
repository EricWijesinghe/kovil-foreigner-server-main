package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.community.dto.response.CommunityCategoryResponse;
import com.kovil.domain.community.service.CommunityCategoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Community · Category", description = """
        게시판 목록. **v1의 모든 게시판은 익명이다** — 글·댓글 어디에도 작성자 계정 정보가 실리지 않는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community/categories")
public class CommunityCategoryController {

    private final CommunityCategoryQueryService categoryQueryService;

    @Operation(summary = "게시판 목록", description = "노출 순서대로. `name`은 Accept-Language 언어로 번역된다(en 폴백).")
    @GetMapping
    public ApiResponse<List<CommunityCategoryResponse>> findAll() {
        return ApiResponse.success(categoryQueryService.findActive());
    }
}
