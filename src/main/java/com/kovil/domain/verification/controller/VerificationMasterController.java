package com.kovil.domain.verification.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.verification.dto.response.RequiredDocumentResponse;
import com.kovil.domain.verification.dto.response.ResidentStatusResponse;
import com.kovil.domain.verification.service.VerificationMasterQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Verification Master", description = """
        외국인 인증의 신분·요구 문서 마스터 (인증 필요).

        **제출 화면은 이 두 응답만으로 그려진다.** 신분·문서가 늘거나 요건이 바뀌어도
        **앱 배포 없이** 서버 데이터만 바꾸면 된다 — FE에 신분별 분기를 두지 말 것.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/verifications")
public class VerificationMasterController {

    private final VerificationMasterQueryService verificationMasterQueryService;

    @Operation(summary = "1) 신분 목록", description = """
            인증 신청의 첫 화면. 사용자가 자기 신분을 고른다.

            **화면 설계**
            - 서버 순서를 그대로 쓴다(정렬하지 말 것 — 노출 순서를 서버가 큐레이션한다).
            - `iconKey`로 아이콘을 고르고 **모르는 키는 기본 아이콘으로 떨어뜨린다**.
              그래야 신분이 늘어도 앱 배포가 필요 없다.
            - 고른 `code`를 다음 단계(요건 조회)에 넘긴다.
            """)
    @GetMapping("/resident-statuses")
    public ApiResponse<List<ResidentStatusResponse>> getResidentStatuses() {
        return ApiResponse.success(verificationMasterQueryService.findActiveStatuses());
    }

    @Operation(summary = "2) 신분별 요구 문서", description = """
            **이 목록이 곧 제출 화면이다.** 순서대로 그리고, 각 항목에 파일 업로더를 붙인다.

            **화면 설계**
            - `guideNote`를 **반드시 노출**한다 — 촬영 가이드가 반려율을 좌우한다.
            - `required=false`는 "선택(있으면 심사가 빨라집니다)"로 안내한다.
            - `maxFiles`가 업로더의 장수 상한이다(여권 앞·뒤 등).
            - 파일은 `/files/presign` → `/files/confirm`으로 올리고 받은 `fileUuid`를 제출에 넘긴다.
            - 목록 **마지막에는 기타 증빙 슬롯(`documentCode=ETC`, 선택)이 항상 붙는다** —
              요건 외에 본인을 증명할 서류(장학 증명·잔고 증명·기숙사 계약서 등)를 덧붙이는 통로다.
              필수 서류들과 구분되는 섹션으로 그리고, 제출 시 다른 서류와 똑같이 `documentCode=ETC`로 넘긴다.

            - `RESIDENT_STATUS_NOT_FOUND`(404) — 없거나 더 이상 선택할 수 없는 신분.
            """)
    @GetMapping("/requirements")
    public ApiResponse<List<RequiredDocumentResponse>> getRequirements(
            @Parameter(description = "신분 목록에서 고른 code", example = "STUDENT")
            @RequestParam String statusCode) {
        return ApiResponse.success(verificationMasterQueryService.findRequirements(statusCode));
    }
}
