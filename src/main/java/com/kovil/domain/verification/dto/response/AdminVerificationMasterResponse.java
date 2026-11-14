package com.kovil.domain.verification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public class AdminVerificationMasterResponse {

    @Schema(description = "체류 신분 (관리자 뷰 — 비활성 포함)")
    public record StatusRow(Long id, String code, String iconKey, Integer sortOrder, Boolean active,
                            Map<String, String> names, Map<String, String> descriptions) {
    }

    @Schema(description = "문서 유형 (관리자 뷰)")
    public record DocumentRow(Long id, String code, Boolean isSensitive, Integer maxFiles,
                              Integer sortOrder, Boolean active,
                              Map<String, String> names, Map<String, String> guideNotes) {
    }

    @Schema(description = "요건 매트릭스 행")
    // sortOrder가 없는 이유: 유저가 보는 서류 순서는 문서 유형 축이 정한다(단일 진실).
    // 매트릭스는 "이 신분이 이 서류를 내나"만 답하고, 그리드는 코드 쌍으로 셀을 찾는다.
    public record MatrixRow(Long id, String residentStatusCode, String documentTypeCode,
                            Boolean isRequired) {
    }

    private AdminVerificationMasterResponse() {
    }

    public static StatusRow statusRow(Long id, String code, String iconKey, Integer sortOrder,
                                      Boolean active, Map<String, String> names, Map<String, String> descriptions) {
        return new StatusRow(id, code, iconKey, sortOrder, active, names, descriptions);
    }

    public static List<StatusRow> none() {
        return List.of();
    }
}
