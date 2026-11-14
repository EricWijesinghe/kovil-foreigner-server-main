package com.kovil.domain.country.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.request.MasterOrderRequest;
import com.kovil.domain.country.dto.AdminCountryRequest;
import com.kovil.domain.country.dto.AdminCountryResponse;
import com.kovil.domain.country.service.AdminCountryService;
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
@RequestMapping("/api/v1/admin/master/countries")
@PreAuthorize("hasAuthority('PERM_MASTER_DATA_MANAGE')")
public class AdminCountryController {

    private final AdminCountryService adminCountryService;

    @Operation(summary = "국가 전체(비활성 포함)")
    @GetMapping
    public ApiResponse<List<AdminCountryResponse>> findAll() {
        return ApiResponse.success(adminCountryService.findAll());
    }

    @Operation(summary = "국가 추가", description = "코드 정본은 ISO 3166-1 alpha-2다.")
    @PostMapping
    public ApiResponse<AdminCountryResponse> create(@Valid @RequestBody AdminCountryRequest request) {
        return ApiResponse.success(adminCountryService.create(request));
    }

    @Operation(summary = "국가 수정", description = "코드는 바꿀 수 없다.")
    @PutMapping("/{id}")
    public ApiResponse<AdminCountryResponse> update(@PathVariable Long id,
                                                    @Valid @RequestBody AdminCountryRequest request) {
        return ApiResponse.success(adminCountryService.update(id, request));
    }

    @Operation(summary = "국가 순서 재부여", description = """
            이동 조작(위/아래/맨 위/맨 아래)의 원자 단위 — 축 전 행(비활성 포함)의 완전한 순열을
            받아 한 트랜잭션에서 1..N으로 다시 매긴다. 이름·번역·활성은 건드리지 않는다.

            - `MASTER_ORDER_SCOPE_MISMATCH`(409) — 목록이 그새 바뀌었다(누락·여분·중복). 재조회 후 재시도.
            """)
    @PutMapping("/order")
    public ApiResponse<Void> reorder(@Valid @RequestBody MasterOrderRequest request) {
        adminCountryService.reorder(request);
        return ApiResponse.ok();
    }
}
