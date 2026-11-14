package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.request.MasterTreeOrderRequest;
import com.kovil.domain.rentalrequest.dto.request.AdminRequestOptionRequest;
import com.kovil.domain.rentalrequest.dto.response.AdminRequestOptionResponse;
import com.kovil.domain.rentalrequest.service.AdminRequestOptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin/master/request-options")
@PreAuthorize("hasAuthority('PERM_MASTER_DATA_MANAGE')")
public class AdminRequestOptionController {

    private final AdminRequestOptionService adminRequestOptionService;

    @Operation(summary = "요청 옵션 전체(비활성 포함)", description = "그룹 → 항목 트리는 parentId로 조립한다.")
    @GetMapping
    public ApiResponse<List<AdminRequestOptionResponse>> findAll() {
        return ApiResponse.success(adminRequestOptionService.findAll());
    }

    @Operation(summary = "옵션 추가", description = "SINGLE 그룹의 복수 선택 거부(FR-304)는 제출 시점에 그룹의 현재 값으로 검증된다.")
    @PostMapping
    public ApiResponse<Void> create(@Valid @RequestBody AdminRequestOptionRequest request) {
        adminRequestOptionService.create(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "옵션 수정", description = """
            코드·부모는 바꿀 수 없다. 그 외에는 **아무것도 잠그지 않는다**(V15) — 답변이 달린
            질문의 구조를 바꿔도 지난 요청은 스냅샷이 지킨다.
            """)
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody AdminRequestOptionRequest request) {
        adminRequestOptionService.update(id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "옵션 삭제", description = """
            **조건 없이 지운다**(질문이면 선택지·번역까지 함께, V15). 답변이 달린 행을 지워도
            지난 요청의 화면은 변하지 않는다 — 답변 행은 남고(마스터 링크만 SET NULL) 표시는
            스냅샷이 정본이다. 이 행이 노출 조건이던 질문은 조건이 사라져 항상 노출이 된다.
            """)
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminRequestOptionService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "요청 옵션 순서 재부여 (형제 단위)", description = """
            같은 부모 아래 형제들의 순서를 한 번에 재부여한다. `parentId` null = 최상위 형제들.
            `parentId`는 **최상위 행**이어야 한다(깊이 2 고정 — 리프를 주면 400).

            - `MASTER_ORDER_SCOPE_MISMATCH`(409) — 목록이 그새 바뀌었다. 재조회 후 재시도.
            - `MASTER_REFERENCE_NOT_FOUND`(400) — parentId가 없거나 최상위가 아니다.
            """)
    @PutMapping("/order")
    public ApiResponse<Void> reorder(@Valid @RequestBody MasterTreeOrderRequest request) {
        adminRequestOptionService.reorder(request);
        return ApiResponse.ok();
    }
}
