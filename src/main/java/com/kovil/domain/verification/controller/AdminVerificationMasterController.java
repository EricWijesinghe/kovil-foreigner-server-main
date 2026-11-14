package com.kovil.domain.verification.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.request.MasterOrderRequest;
import com.kovil.domain.verification.dto.request.AdminDocumentTypeRequest;
import com.kovil.domain.verification.dto.request.AdminMatrixRowRequest;
import com.kovil.domain.verification.dto.request.AdminResidentStatusRequest;
import com.kovil.domain.verification.dto.response.AdminVerificationMasterResponse.DocumentRow;
import com.kovil.domain.verification.dto.response.AdminVerificationMasterResponse.MatrixRow;
import com.kovil.domain.verification.dto.response.AdminVerificationMasterResponse.StatusRow;
import com.kovil.domain.verification.service.AdminVerificationMasterService;
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

@Tag(name = "Admin · Master", description = """
        인증 마스터 (**ADMIN 전용**) — 체류 신분·문서 유형·요건 매트릭스.
        매트릭스 변경은 **새 제출부터** 적용된다(기존 인증은 제출 시점 스냅샷).
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/master")
@PreAuthorize("hasAuthority('PERM_MASTER_DATA_MANAGE')")
public class AdminVerificationMasterController {

    private final AdminVerificationMasterService masterService;

    @Operation(summary = "체류 신분 전체(비활성 포함)")
    @GetMapping("/resident-statuses")
    public ApiResponse<List<StatusRow>> findStatuses() {
        return ApiResponse.success(masterService.findStatuses());
    }

    @Operation(summary = "체류 신분 추가")
    @PostMapping("/resident-statuses")
    public ApiResponse<Void> createStatus(@Valid @RequestBody AdminResidentStatusRequest request) {
        masterService.createStatus(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "체류 신분 수정", description = "코드는 바꿀 수 없다. 번역은 보낸 목록으로 통째로 교체된다(null이면 유지).")
    @PutMapping("/resident-statuses/{id}")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody AdminResidentStatusRequest request) {
        masterService.updateStatus(id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "문서 유형 전체(비활성 포함)")
    @GetMapping("/document-types")
    public ApiResponse<List<DocumentRow>> findDocuments() {
        return ApiResponse.success(masterService.findDocuments());
    }

    @Operation(summary = "문서 유형 추가")
    @PostMapping("/document-types")
    public ApiResponse<Void> createDocument(@Valid @RequestBody AdminDocumentTypeRequest request) {
        masterService.createDocument(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "문서 유형 수정")
    @PutMapping("/document-types/{id}")
    public ApiResponse<Void> updateDocument(@PathVariable Long id,
                                            @Valid @RequestBody AdminDocumentTypeRequest request) {
        masterService.updateDocument(id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "요건 매트릭스 전체")
    @GetMapping("/requirement-matrix")
    public ApiResponse<List<MatrixRow>> findMatrix() {
        return ApiResponse.success(masterService.findMatrix());
    }

    @Operation(summary = "매트릭스 행 추가", description = "같은 (신분, 문서) 쌍은 `MASTER_DUPLICATE`(409).")
    @PostMapping("/requirement-matrix")
    public ApiResponse<Void> createMatrixRow(@Valid @RequestBody AdminMatrixRowRequest request) {
        masterService.createMatrixRow(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "매트릭스 행 수정", description = "필수 여부·정렬만.")
    @PutMapping("/requirement-matrix/{id}")
    public ApiResponse<Void> updateMatrixRow(@PathVariable Long id,
                                             @Valid @RequestBody AdminMatrixRowRequest request) {
        masterService.updateMatrixRow(id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "매트릭스 행 삭제", description = "기존 제출은 스냅샷을 따르므로 과거와 무관하다.")
    @DeleteMapping("/requirement-matrix/{id}")
    public ApiResponse<Void> deleteMatrixRow(@PathVariable Long id) {
        masterService.deleteMatrixRow(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "체류 신분 순서 재부여", description = """
            이동 조작(위/아래/맨 위/맨 아래)의 원자 단위 — 축 전 행(비활성 포함)의 완전한 순열을
            받아 한 트랜잭션에서 1..N으로 다시 매긴다. 이름·번역·활성은 건드리지 않는다.

            - `MASTER_ORDER_SCOPE_MISMATCH`(409) — 목록이 그새 바뀌었다(누락·여분·중복). 재조회 후 재시도.
            """)
    @PutMapping("/resident-statuses/order")
    public ApiResponse<Void> reorderStatuses(@Valid @RequestBody MasterOrderRequest request) {
        masterService.reorderStatuses(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "문서 유형 순서 재부여", description = """
            이동 조작(위/아래/맨 위/맨 아래)의 원자 단위 — 축 전 행(비활성 포함)의 완전한 순열을
            받아 한 트랜잭션에서 1..N으로 다시 매긴다. 이름·번역·활성은 건드리지 않는다.

            - `MASTER_ORDER_SCOPE_MISMATCH`(409) — 목록이 그새 바뀌었다(누락·여분·중복). 재조회 후 재시도.
            """)
    @PutMapping("/document-types/order")
    public ApiResponse<Void> reorderDocuments(@Valid @RequestBody MasterOrderRequest request) {
        masterService.reorderDocuments(request);
        return ApiResponse.ok();
    }
}
