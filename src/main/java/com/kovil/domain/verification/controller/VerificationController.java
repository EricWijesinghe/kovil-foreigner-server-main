package com.kovil.domain.verification.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.verification.dto.request.VerificationSubmitRequest;
import com.kovil.domain.verification.dto.response.VerificationResponse;
import com.kovil.domain.verification.service.VerificationQueryService;
import com.kovil.domain.verification.service.VerificationSubmitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Verification", description = """
        외국인 인증 신청·조회 (인증 필요).

        **이 인증이 임대 요청의 관문이다.** 승인 전에는 요청을 낼 수 없다.
        심사는 사람이 전량 수행하므로 즉시 결과가 나오지 않는다 — 화면에 대기 상태를 보여줄 것.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/verifications")
public class VerificationController {

    private final VerificationSubmitService verificationSubmitService;
    private final VerificationQueryService verificationQueryService;

    @Operation(summary = "3) 인증 신청 제출", description = """
            요건 조회 → 파일 업로드 → 이 API 순서다.

            **화면 설계**
            - 제출 직후 상태는 항상 `PENDING_REVIEW`다. **즉시 결과가 나오지 않는다**(사람이 심사한다).
            - `fullName`은 **여권 영문 성명 그대로** 받아야 한다 — 계약 명의의 정본이 된다.
            - 복수장 문서는 같은 `documentCode`로 여러 건 보낸다.

            **에러 분기**
            - `VERIFICATION_PENDING`(409) — 이미 심사 중. 제출 버튼을 잠그고 대기 화면을 보여준다.
            - `VERIFICATION_DOCS_MISSING`(400) — **응답의 `missingDocumentCodes`에 빠진 코드가 실린다.**
              그 항목만 표시해 주면 된다.
            - `VERIFICATION_DOC_NOT_REQUIRED`(400) — 요건에 없는 서류를 보냈다.
            - `VERIFICATION_DOC_TOO_MANY`(400) — `maxFiles` 초과.
            - `VERIFICATION_FILE_INVALID`(400) — 없는 파일·남의 파일·용도가 다른 파일·같은 파일 중복.
              재업로드를 안내한다.
            """)
    @PostMapping
    public ResponseEntity<ApiResponse<VerificationResponse>> submit(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody VerificationSubmitRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        verificationSubmitService.submit(principal.userUuid(), request)));
    }

    @Operation(summary = "내 인증 상태", description = """
            **최신 신청 1건만** 준다. 과거 신청은 나오지 않는다.

            **화면 설계**
            - `effective=true`면 임대 요청 화면을 연다. false면 인증 유도 배너를 띄운다.
            - `REJECTED`면 `rejectReason`을 **그대로 보여주고** 재제출 버튼을 연다.
            - `VERIFICATION_NOT_FOUND`(404)는 "아직 신청한 적 없음"이다 — 오류 화면이 아니라
              **인증 시작 화면**으로 보낸다.
            """)
    @GetMapping("/me")
    public ApiResponse<VerificationResponse> getMine(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(verificationQueryService.getMyLatest(principal.userUuid()));
    }
}
