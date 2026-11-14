package com.kovil.domain.region.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.request.MasterTreeOrderRequest;
import com.kovil.domain.region.dto.AdminRegionRequest;
import com.kovil.domain.region.dto.AdminRegionResponse;
import com.kovil.domain.region.service.AdminRegionService;
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
@RequestMapping("/api/v1/admin/master/regions")
@PreAuthorize("hasAuthority('PERM_MASTER_DATA_MANAGE')")
public class AdminRegionController {

    private final AdminRegionService adminRegionService;

    @Operation(summary = "지역 전체(비활성 포함)", description = "시·도 → 구·군 트리는 parentId로 조립한다.")
    @GetMapping
    public ApiResponse<List<AdminRegionResponse>> findAll() {
        return ApiResponse.success(adminRegionService.findAll());
    }

    @Operation(summary = "지역 추가", description = "코드 정본은 법정동코드다.")
    @PostMapping
    public ApiResponse<AdminRegionResponse> create(@Valid @RequestBody AdminRegionRequest request) {
        return ApiResponse.success(adminRegionService.create(request));
    }

    @Operation(summary = "지역 수정", description = """
            코드·부모는 바꿀 수 없다.

            ⚠ **`isActive` 를 끄면(닫으면) 그 순간 그 지역의 공개 매물을 내린다** — 시도를 닫으면 그 아래 시군구 전부.
            매물은 `HIDDEN` 이 되고 중개사에게 알림(`LISTING_REGION_CLOSED`)이 간다.
            **다시 켜도 매물은 되살아나지 않는다** — 중개사가 하나씩 다시 게시해야 한다. 이름 · 순서만 고치는 저장은 매물을 건드리지 않는다.
            연장을 기다리던(만료 후 7일 이내) 매물도 함께 `HIDDEN` 으로 보존된다. 알림은 중개사별 1건(내려간 건수).
            닫는 순간 그 지역 매물이 다른 곳에서 바뀌고 있으면 **409 `CONFLICT`** 이고 **지역도 닫히지 않는다**(통째 롤백) — 다시 시도한다.
            """)
    @PutMapping("/{id}")
    public ApiResponse<AdminRegionResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody AdminRegionRequest request) {
        return ApiResponse.success(adminRegionService.update(id, request));
    }

    @Operation(summary = "지역 순서 재부여 (형제 단위)", description = """
            같은 부모 아래 형제들의 순서를 한 번에 재부여한다. `parentId` null = 최상위 형제들.
            `parentId`는 **최상위 행**이어야 한다(깊이 2 고정 — 리프를 주면 400).

            - `MASTER_ORDER_SCOPE_MISMATCH`(409) — 목록이 그새 바뀌었다. 재조회 후 재시도.
            - `MASTER_REFERENCE_NOT_FOUND`(400) — parentId가 없거나 최상위가 아니다.
            """)
    @PutMapping("/order")
    public ApiResponse<Void> reorder(@Valid @RequestBody MasterTreeOrderRequest request) {
        adminRegionService.reorder(request);
        return ApiResponse.ok();
    }
}
