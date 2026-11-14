package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.rentalrequest.dto.request.RentalRequestCloseRequest;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestSummaryResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestView;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.service.PartnerRentalRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Partner · Rental Request", description = """
        파트너스(공인중개사 사무소)의 임대 요청 처리 (`RENTAL_REQUEST_HANDLE` — 공인중개사 부문이 **승인된** 파트너).

        **먼저 잡은 사무소가 맡는다(선착순).** 잡는 단위는 사무소다. 잡으면 유학생 휴대폰이 열리고, 유학생 앱에는
        사무소 · 대표번호 · 대표자 휴대폰이 보인다 — 서로 전화 · 문자로 연락한다(채팅은 추후).

        - 대기 목록은 승인된 **모든 사무소가 전국**의 대기 요청을 본다. 희망 지역으로 거르는 것은 앱의 몫이다(`regionCodes`).
          새 요청은 알림으로 오지 않는다 — 목록을 새로고침해 본다.
        - 응답 모양은 둘이다(`view`). **`SUMMARY`** — 잡기 전 요약(이름 · 전화 · 자유 요청사항 없음).
          **`FULL`** — 내 사무소가 맡은 요청(이름 · 자유 요청사항 · **휴대폰은 진행 중일 때만**).
        - 맡은 요청은 **놓을 수 있다**(다시 대기 — 다른 사무소가 잡을 수 있다). 끝나면 결과를 골라 **종료**한다.
        - 유학생은 대기 · 진행 중 어느 때나 수정 · 취소할 수 있고, **사무소에 알림은 가지 않는다** — "맡은 요청"에서 상태로 본다.
        - 대표자가 탈퇴하거나 부문 · 계정이 정지되면 맡은 진행 중 요청은 **대기로 돌아간다**(복구돼도 되찾지 않는다).
        - 잡고 두기만 해도 자동으로 풀리지 않는다. 사무소당 진행 중 요청은 상한(기본 **50건**)까지다.

        **남의 사무소 요청 · 대기 목록에 없는 요청은 404** 다(존재를 흘리지 않는다).
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner/rental-requests")
@PreAuthorize("hasAuthority('PERM_RENTAL_REQUEST_HANDLE')")
public class PartnerRentalRequestController {

    private final PartnerRentalRequestService partnerRentalRequestService;

    @Operation(summary = "대기 목록", description = """
            대기(`SUBMITTED`) 중이고 신청자 계정이 살아 있는 요청 — **오래 기다린 것부터**. 요약(`SUMMARY`)만 준다.

            `regionCodes` 는 `GET /api/v1/regions` 의 **시군구** 코드다(여러 개). 고른 지역 중 하나라도 걸리는 요청만 나온다.
            비우면 전국이다. 시도 코드는 `REGION_INVALID_LEVEL`(400), 없거나 닫힌 코드는 `REGION_NOT_FOUND`(400).
            """)
    @GetMapping
    public ApiResponse<PageResponse<PartnerRentalRequestSummaryResponse>> pool(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "희망 지역(시군구 코드). 비우면 전국")
            @RequestParam(required = false) List<String> regionCodes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(partnerRentalRequestService.findPool(
                principal.userUuid(), regionCodes, Paging.of(page, size)));
    }

    @Operation(summary = "내 사무소가 맡은 요청", description = """
            진행 중 · 종료 · 맡은 뒤 유학생이 취소한 요청 — **최신순**, 전체(`FULL`) 모양.
            놓은 요청 · 대기로 돌아간 요청은 더는 내 사무소 것이 아니라 빠진다.
            휴대폰은 진행 중(`IN_PROGRESS`)일 때만 실린다.
            """)
    @GetMapping("/mine")
    public ApiResponse<PageResponse<PartnerRentalRequestResponse>> mine(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "IN_PROGRESS · CLOSED · CANCELED. 비우면 전체")
            @RequestParam(required = false) RentalRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(partnerRentalRequestService.findMine(
                principal.userUuid(), status, Paging.of(page, size)));
    }

    @Operation(summary = "요청 상세", description = """
            **내 사무소 건이면 `FULL`, 대기 목록에 있는 건이면 `SUMMARY`** 다(`view` 로 가른다).
            남의 사무소 건 · 취소 · 종료 · 신청자가 정지된 대기 건은 `RENTAL_REQUEST_NOT_FOUND`(404).
            """)
    @GetMapping("/{requestUuid}")
    public ApiResponse<PartnerRentalRequestView> get(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid) {
        return ApiResponse.success(partnerRentalRequestService.get(principal.userUuid(), requestUuid));
    }

    @Operation(summary = "잡기", description = """
            **선착순.** 성공하면 진행 중(`IN_PROGRESS`)이 되고 전체(`FULL`) 모양 — 유학생 휴대폰이 실린다.
            유학생에게 "○○ 사무소가 요청을 맡았습니다" 알림이 간다.

            **멱등** — 이미 내 사무소가 잡아 진행 중인 요청을 다시 잡으면(두 번 누름 · 재시도) 같은 전체 응답(200)이다.
            알림 · 잡기 이력 · 진행 중 개수는 바뀌지 않고, 상한에 닿아 있어도 막히지 않는다.

            **에러 분기**
            - `RENTAL_REQUEST_ALREADY_CLAIMED`(409) — **다른** 사무소가 먼저 잡았다(동시에 눌렀으면 하나만 이긴다). 목록을 새로고침한다.
            - `RENTAL_REQUEST_CLAIM_LIMIT`(409) — 내 사무소의 진행 중 요청이 상한(50)이다. 맡은 요청을 종료하거나 놓은 뒤 다시.
            - `REQUEST_NOT_OPEN`(409) — 그사이 유학생이 취소했거나 이미 종료된 요청이다(내 사무소가 종료한 것 포함).
            - `RENTAL_REQUEST_NOT_FOUND`(404) — 없는 요청이거나 신청자 계정이 정지 · 탈퇴했다(대기 목록에서도 빠진다).
            - `RENTAL_REQUEST_OFFICE_NOT_ACTIVE`(403) · `USER_SUSPENDED`(403) — 부문 · 계정이 정지됐다(토큰이 낡았다). 다시 로그인한다.
            - `BROKER_PROFILE_NOT_FOUND`(403) — 사무소 대표자 연결이 없다.
            """)
    @PostMapping("/{requestUuid}/claim")
    public ApiResponse<PartnerRentalRequestResponse> claim(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid) {
        return ApiResponse.success(partnerRentalRequestService.claim(principal.userUuid(), requestUuid));
    }

    @Operation(summary = "놓기", description = """
            맡은 요청을 **다시 대기**로 돌린다 — 다른 사무소가 잡을 수 있다. 유학생에게 "다시 대기 중" 알림이 간다.
            응답은 이제 대기 건의 요약(`SUMMARY`)이다.

            - `RENTAL_REQUEST_NOT_IN_PROGRESS`(409) — 그사이 종료 · 취소됐다.
            - `RENTAL_REQUEST_NOT_FOUND`(404) — 내 사무소가 맡은 요청이 아니다.
            - 판정 실패(403)는 잡기와 같다.
            """)
    @PostMapping("/{requestUuid}/release")
    public ApiResponse<PartnerRentalRequestSummaryResponse> release(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid) {
        return ApiResponse.success(partnerRentalRequestService.release(principal.userUuid(), requestUuid));
    }

    @Operation(summary = "종료", description = """
            결과를 **한 번** 고른다 — `FOUND`(방을 구함) · `NOT_PROCEEDED`(진행 안 함). **되돌릴 수 없다.**
            유학생에게는 결과 없이 "종료" 알림이 간다. 휴대폰은 이제 양쪽 다 가려진다.

            - `RENTAL_REQUEST_OUTCOME_REQUIRED`(400, `validationErrors[]`) — 결과를 고르지 않았다.
            - `RENTAL_REQUEST_NOT_IN_PROGRESS`(409) — 그사이 유학생이 취소했거나 이미 종료됐다.
            - `RENTAL_REQUEST_NOT_FOUND`(404) — 내 사무소가 맡은 요청이 아니다.
            - 판정 실패(403)는 잡기와 같다.
            """)
    @PostMapping("/{requestUuid}/close")
    public ApiResponse<PartnerRentalRequestResponse> close(
            @CurrentUser AuthenticatedUser principal, @PathVariable String requestUuid,
            @Valid @RequestBody RentalRequestCloseRequest request) {
        return ApiResponse.success(partnerRentalRequestService.close(
                principal.userUuid(), requestUuid, request.outcome()));
    }
}
