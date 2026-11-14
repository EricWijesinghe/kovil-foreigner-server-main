package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.rentalrequest.dto.request.RentalRequestCancelRequest;
import com.kovil.domain.rentalrequest.dto.request.RentalRequestSaveRequest;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.RequestOptionGroupResponse;
import com.kovil.domain.rentalrequest.service.RentalRequestService;
import com.kovil.domain.rentalrequest.service.RequestOptionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Rental Request", description = """
        임대 요청 — 유저가 "이런 집을 찾아 달라"고 내는 것이다. 매물을 고르는 것이 아니라
        **조건을 내면 승인된 공인중개사 사무소가 먼저 잡아(선착순) 연락해 온다.**

        상태: `SUBMITTED`(대기) → `IN_PROGRESS`(사무소가 맡음 — `office` 에 사무소 · 연락처) → `CLOSED`(사무소가 종료).
        `CANCELED` 는 유저가 접은 것이다. 맡은 사무소가 놓거나 더는 맡을 수 없게 되면 **다시 `SUBMITTED`** 로 돌아간다.

        ★ **이 도메인에 외국인 인증 게이트가 걸려 있다**(FR-301). 인증 없이 요청을 만들 수 없다.
        가입은 인증 없이 되지만 여기서 막힌다 — 시안의 "가입 단계 인증"은 오설계다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rental-requests")
public class RentalRequestController {

    private final RentalRequestService rentalRequestService;
    private final RequestOptionQueryService requestOptionQueryService;

    @Operation(summary = "선택 항목 트리", description = """
            요청 작성 화면을 그리기 위한 **2단 트리**(그룹 → 항목).

            **인증 없이도 볼 수 있다** — 무엇을 물어보는지 미리 보여줘야 인증할 마음이 생긴다.
            `selectionType`이 `SINGLE`이면 라디오, `MULTI`면 체크박스로 그린다.
            서버가 같은 규칙으로 검증하므로 위젯과 검증이 어긋나지 않는다.

            **항목 코드를 하드코딩하지 말 것.** 서버 데이터만 바꿔 항목을 늘리는 것이 설계 전제다.
            """)
    @GetMapping("/options")
    public ApiResponse<List<RequestOptionGroupResponse>> options() {
        return ApiResponse.success(requestOptionQueryService.findActiveTree());
    }

    @Operation(summary = "요청 등록", description = """
            ★ **인증 게이트**(FR-301). 승인된(그리고 만료되지 않은) 인증이 없으면
            `VERIFICATION_REQUIRED`(403)이며, **FE는 이 코드를 보고 인증 화면으로 보낸다.**

            성공하면 **업무번호**(`RQ-yyMMdd-RAND6`)가 발급된다 — 맡은 사무소가 전화 · 문자로 이 번호를
            부르므로 완료 화면에 크게 노출할 것.

            - `RENTAL_REQUEST_LIMIT_EXCEEDED`(409) — 진행 중인 요청이 이미 있다. 그 요청으로 안내한다.
            - `REGION_INVALID_LEVEL`(400) — 시도를 골랐다. 시군구를 고르게 한다.
            - `OPTION_SELECTION_INVALID`(400) — SINGLE 그룹 복수 선택 또는 그룹 코드 전송.
            - `BUDGET_RANGE_INVALID`(400) — 하한 > 상한.
            """)
    @PostMapping
    public ApiResponse<RentalRequestResponse> create(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody RentalRequestSaveRequest request) {
        return ApiResponse.success(rentalRequestService.create(principal.userUuid(), request));
    }

    @Operation(summary = "내 요청 목록", description = """
            최신순. **`editable`을 보고 수정·취소 버튼을 그린다** — FE가 상태 전이 규칙을 알 필요가 없다.

            맡은 사무소(`office`)의 **대표자 휴대폰은 진행 중일 때만** 실린다. 종료 결과는 응답에 없다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<RentalRequestResponse>> findMine(
            @CurrentUser AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(rentalRequestService.findMine(principal.userUuid(), Paging.of(page, size)));
    }

    @Operation(summary = "요청 상세", description = """
            남의 요청은 403이 아니라 **404**로 답한다 — uuid가 존재하는지 흘리지 않는다.
            """)
    @GetMapping("/{requestUuid}")
    public ApiResponse<RentalRequestResponse> getMine(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String requestUuid) {
        return ApiResponse.success(rentalRequestService.getMine(principal.userUuid(), requestUuid));
    }

    @Operation(summary = "요청 수정", description = """
            **PATCH가 아니라 PUT이다** — 등록과 같은 본문으로 **통째로 덮어쓴다.**
            수정 화면은 등록 화면과 같은 폼이므로 **받은 상세를 그대로 폼에 채워 두고 전송**하면 된다.
            보내지 않은 지역·옵션은 빠진다.

            **사무소가 맡은 뒤(진행 중)에도 고칠 수 있다** — 사무소에 알림은 가지 않는다(목록에서 바뀐 내용을 본다).

            - `REQUEST_NOT_EDITABLE`(409) — 이미 종료됐거나 취소된 요청.
            - `CONFLICT`(409) — 사무소가 동시에 상태를 바꿨다(잡기 · 놓기 · 종료). 상세를 다시 받아 화면을 갱신한다.
            """)
    @PutMapping("/{requestUuid}")
    public ApiResponse<RentalRequestResponse> update(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String requestUuid,
            @Valid @RequestBody RentalRequestSaveRequest request) {
        return ApiResponse.success(
                rentalRequestService.update(principal.userUuid(), requestUuid, request));
    }

    @Operation(summary = "요청 취소", description = """
            **되돌릴 수 없다.** 다시 찾으려면 새 요청을 낸다 — 취소 확인 다이얼로그를 둘 것.

            대기 · 진행 중 어느 때나 취소할 수 있다. 맡은 사무소에는 알림이 가지 않는다.
            취소하면 진행 중 건수에서 빠지므로 곧바로 새 요청을 낼 수 있다.
            `REQUEST_NOT_EDITABLE`(409)이면 이미 종료 · 취소된 건이다.
            """)
    @PostMapping("/{requestUuid}/cancel")
    public ApiResponse<RentalRequestResponse> cancel(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String requestUuid,
            @Valid @RequestBody(required = false) RentalRequestCancelRequest request) {
        return ApiResponse.success(rentalRequestService.cancel(
                principal.userUuid(), requestUuid, request == null ? null : request.reason()));
    }
}
