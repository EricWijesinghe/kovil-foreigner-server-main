package com.kovil.domain.file.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.file.dto.request.ConfirmUploadRequest;
import com.kovil.domain.file.dto.request.PresignUploadRequest;
import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.file.dto.response.FileResponse;
import com.kovil.domain.file.dto.response.PresignedUploadResponse;
import com.kovil.domain.file.service.FileQueryService;
import com.kovil.domain.file.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "File", description = """
        파일 업로드 (인증 필요).

        **2단계다.** ① `POST /presign`으로 URL을 받아 스토리지에 직접 PUT →
        ② `POST /confirm`으로 확정. 서버를 파일이 통과하지 않으므로 업로드가 서버 부하를 만들지 않는다.

        **민감 파일(인증 문서)은 공개 URL을 갖지 않는다.**
        열람할 때마다 `GET /{fileUuid}/url`로 짧은 URL을 새로 받는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;
    private final FileQueryService fileQueryService;

    @Operation(summary = "1) 업로드 URL 발급", description = """
            **이 단계는 DB에 아무것도 남기지 않는다.** 발급만 받고 올리지 않으면 흔적이 없다.

            **클라이언트 순서**
            1. 이 API로 `uploadUrl`을 받는다.
            2. 그 URL에 **요청 때와 같은 Content-Type으로** 파일을 PUT한다.
               (타입이 다르면 스토리지가 거부한다 — 서명에 포함되어 있다)
            3. `key`를 들고 confirm을 부른다.

            - `UNSUPPORTED_CONTENT_TYPE`(400) — 그 용도가 받지 않는 형식.
            - `INVALID_FILE_PURPOSE`(400) — 없는 용도이거나 은퇴한 용도.
            """)
    @RateLimited(RateLimitPolicy.UPLOAD_PRESIGN)
    @PostMapping("/presign")
    public ApiResponse<PresignedUploadResponse> presign(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody PresignUploadRequest request) {
        return ApiResponse.success(fileService.presign(principal.userUuid(), request));
    }

    @Operation(summary = "2) 업로드 확정", description = """
            서버가 **스토리지에서 실측**해 형식·용량을 검증하고 확정 경로로 옮긴 뒤 원장에 기록한다.
            클라이언트가 신고한 값은 쓰지 않는다.

            반환된 `fileUuid`를 인증 제출·매물 사진 등록 등에 넘긴다.

            - `INVALID_FILE_PURPOSE`(400) — 없는 용도이거나 은퇴한 용도.
            - `UPLOADED_FILE_NOT_FOUND`(404) — 아직 PUT하지 않았거나 키가 틀렸다.
            - `UNSUPPORTED_CONTENT_TYPE`·`FILE_TOO_LARGE`(400) — 실측 결과가 규격 밖.
            - `INVALID_TEMP_KEY`(400) — presign이 준 키가 아니다.
            """)
    @PostMapping("/confirm")
    public ApiResponse<FileResponse> confirm(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody ConfirmUploadRequest request) {
        return ApiResponse.success(fileService.confirm(principal.userUuid(), request));
    }

    @Operation(summary = "열람 URL 발급", description = """
            단기 열람 URL을 발급한다. **캐시하거나 공유하지 말 것** — 만료되는 것이 보호 장치다.

            민감 파일은 이 통로가 유일한 열람 방법이다. 비민감 파일도 같은 API를 쓸 수 있다.

            **권한**: 인증 문서는 **본인 또는 인증 심사자**만 볼 수 있다(FR-209).
            은퇴한 용도(계약서·입주 사진·은행 아이콘)는 누구에게도 열리지 않는다.
            볼 수 없는 파일과 없는 파일을 구분하지 않고 둘 다 `FILE_NOT_FOUND`(404)로 답한다 —
            구분하면 그 파일이 존재한다는 사실이 새어 나간다.
            """)
    @GetMapping("/{fileUuid}/url")
    public ApiResponse<FileReadUrlResponse> readUrl(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "confirm이 준 fileUuid") @PathVariable String fileUuid) {
        return ApiResponse.success(fileQueryService.issueReadUrl(fileUuid, principal));
    }
}
