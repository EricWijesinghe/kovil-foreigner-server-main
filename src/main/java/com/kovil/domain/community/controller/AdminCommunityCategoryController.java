package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.request.MasterOrderRequest;
import com.kovil.domain.community.dto.request.AdminCategoryRequest;
import com.kovil.domain.community.dto.response.AdminCategoryResponse;
import com.kovil.domain.community.service.AdminCommunityCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin · Master")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/master/community-categories")
@PreAuthorize("hasAuthority('PERM_MASTER_DATA_MANAGE')")
public class AdminCommunityCategoryController {

    private final AdminCommunityCategoryService adminCategoryService;

    @Operation(summary = "게시판 전체(비활성 포함)")
    @GetMapping
    public ApiResponse<List<AdminCategoryResponse>> findAll() {
        return ApiResponse.success(adminCategoryService.findAll());
    }

    @Operation(summary = "게시판 추가", description = "v1 정책은 전면 익명 — 비익명 게시판을 만들기 전에 정책 확인이 먼저다.")
    @PostMapping
    public ApiResponse<Void> create(@Valid @RequestBody AdminCategoryRequest request) {
        adminCategoryService.create(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "게시판 수정", description = "**글이 하나라도 있으면 익명 설정을 바꿀 수 없다**(`CATEGORY_HAS_POSTS` 409).")
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody AdminCategoryRequest request) {
        adminCategoryService.update(id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "게시판 순서 재부여", description = """
            이동 조작(위/아래/맨 위/맨 아래)의 원자 단위 — 축 전 행(비활성 포함)의 완전한 순열을
            받아 한 트랜잭션에서 1..N으로 다시 매긴다. 이름·번역·활성은 건드리지 않는다.

            - `MASTER_ORDER_SCOPE_MISMATCH`(409) — 목록이 그새 바뀌었다(누락·여분·중복). 재조회 후 재시도.
            """)
    @PutMapping("/order")
    public ApiResponse<Void> reorder(@Valid @RequestBody MasterOrderRequest request) {
        adminCategoryService.reorder(request);
        return ApiResponse.ok();
    }
}
