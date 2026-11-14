package com.kovil.domain.listing.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.listing.dto.request.BrokerListingSaveRequest;
import com.kovil.domain.listing.dto.response.BrokerListingResponse;
import com.kovil.domain.listing.dto.response.BrokerListingStatusCountsResponse;
import com.kovil.domain.listing.dto.response.BrokerListingSummaryResponse;
import com.kovil.domain.listing.enums.BrokerListingSort;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.service.BrokerListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Broker · Listing", description = """
        **외부 공인중개사**(파트너 · 공인중개사 부문)의 매물 관리 (`LISTING_MANAGE`).

        공인중개사 부문이 **승인된** 파트너에게만 열린다 — 심사 중 · 반려 · 정지면 403 이다.
        이 능력은 매물만 만진다 — 임대 요청·외국인 인증 **어디에도 닿지 않는다.**

        범위는 **사무소**다. 같은 사무소의 다른 계정이 올린 매물도 보인다 —
        대표자가 바뀌어도 사무소 매물은 이어져야 하기 때문이다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/broker/listings")
@PreAuthorize("hasAuthority('PERM_LISTING_MANAGE')")
public class BrokerListingController {

    private final BrokerListingService brokerListingService;

    @Operation(summary = "내 사무소 매물 목록", description = """
            `status` 를 **비우면 전체**다. 여러 개를 보내면 그 상태들만.

            만료가 가까운 건을 먼저 처리해야 하므로 `expiresAt` 이 목록에 실린다 —
            `EXPIRED` 라면 그 시각 **+7일**까지만 연장할 수 있다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<BrokerListingSummaryResponse>> list(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "DRAFT · ACTIVE · RESERVED · COMPLETED · EXPIRED · HIDDEN · BLOCKED · ARCHIVED. 비우면 전체")
            @RequestParam(required = false) List<ListingStatus> status,
            @Parameter(description = "UPDATED_DESC(기본 · 최근 바뀐 순) · CREATED_DESC · POSTED_DESC · EXPIRES_ASC(만료 임박 — 연장 탭)")
            @RequestParam(required = false) BrokerListingSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(brokerListingService.findMine(
                principal.userUuid(), status, sort, Paging.of(page, size)));
    }

    @Operation(summary = "내 사무소 매물 상태별 개수", description = """
            홈 숫자에 쓴다 — 게시 중 = `ACTIVE + RESERVED`, 연장할 수 있는 = `renewable`, 임시저장 = `DRAFT`.
            상태 8개는 0 이어도 전부 실린다. `renewable` 은 `EXPIRED` 중 만료 시각 **+7일**이 아직 지나지 않은 것만 —
            배치가 아직 `ARCHIVED` 로 옮기지 못한, 창이 닫힌 건은 세지 않는다.
            """)
    @GetMapping("/status-counts")
    public ApiResponse<BrokerListingStatusCountsResponse> statusCounts(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(brokerListingService.countMine(principal.userUuid()));
    }

    @Operation(summary = "매물 상세(편집용)", description = """
            편집 화면이 이걸로 폼을 채운다. **공개 응답과 두 가지가 다르다** —
            자기 매물이므로 ① **실좌표**를 주고(핀 조정에 필요하다) ② **숨긴 층도** 준다.
            """)
    @GetMapping("/{listingUuid}")
    public ApiResponse<BrokerListingResponse> get(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.get(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "매물 등록", description = """
            **항상 `DRAFT` 로 생긴다.** 사진이 presign→confirm 2단계라 한 번의 호출로
            완성될 수 없고, 게시 관문이 따로 있기 때문이다.

            - 좌표는 **실좌표**를 보낸다. 흐린 좌표는 서버가 만든다.
            - `floor` 와 `floorBand` 는 **둘 중 하나만** — 둘 다 보내면 `FLOOR_INPUT_INVALID`.
            - 사진은 최대 10장(`LISTING_IMAGE_LIMIT_EXCEEDED`). 확정하고 **24시간 안에 어느 매물에도 붙이지 않은 사진은 정리된다** —
              그 뒤 그 `fileUuid` 로 저장하면 `LISTING_IMAGE_INVALID`(400). 다시 올려 붙인다.
            - `regionCode` 는 `GET /api/v1/regions` 의 **시군구** 코드다. 비우면 지역 없는 초안이 된다(게시 때 필요).
              없거나 닫힌 코드 · 시도 코드 · 부모 시도가 닫힌 시군구는 `LISTING_REGION_INVALID`(400).
            """)
    @RateLimited(RateLimitPolicy.LISTING_CREATE)
    @PostMapping
    public ApiResponse<BrokerListingResponse> create(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody BrokerListingSaveRequest request) {
        return ApiResponse.success(brokerListingService.create(principal.userUuid(), request));
    }

    @Operation(summary = "매물 수정", description = """
            옵션·안전시설·관리비·사진은 **전량 교체**된다 — 빠진 항목은 삭제로 읽는다.
            빠진 사진 파일은 폐기된다(다른 매물에도 붙어 있으면 남는다) — 같은 `fileUuid` 를 나중에 다시 붙일 수 없다.

            **가격이 바뀌면 이력이 남는다.** 「온라인 부동산 광고 자율규약」이
            "가격이 사실과 다른 매물"을 거짓매물로 보므로, 신고가 오면 그 이력이 답변 근거다.

            ⚠ **상세설명을 바꾸면 그 매물의 번역 캐시가 전부 무효화된다** —
            수정 레이트리밋이 남용 방지가 아니라 **비용 방어**인 이유다.

            **지역**: 편집 응답의 `regionCode` 를 그대로 되보내면 그 지역이 닫혔어도 그대로 둔다(가격만 고칠 수 있다).
            다른 코드로 바꾸면 새 지역은 검사를 받는다(`LISTING_REGION_INVALID`).
            **공개 중(ACTIVE·RESERVED)인 매물은 수정 뒤에도 게시 조건을 지켜야 한다** — 깨지면 게시와 같은 `PUBLISH_*` 400.

            본문 `version` 이 지금 매물과 다르면(그사이 다른 기기 · 만료 배치 · 지역 닫힘 · 차단 해제로 바뀜) **409 `CONFLICT`** 이고
            아무것도 저장하지 않는다 — 다시 불러와 고친 뒤 저장한다. 같은 순간 겹친 저장도 409 `CONFLICT` 다.
            성공하면 응답 `version` 이 바뀐다(사진 · 관리비만 바꿔도) — 같은 폼에서 다시 저장할 때는 그 값을 싣는다.
            """)
    @RateLimited(RateLimitPolicy.LISTING_UPDATE)
    @PutMapping("/{listingUuid}")
    public ApiResponse<BrokerListingResponse> update(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid,
            @Valid @RequestBody BrokerListingSaveRequest request) {
        return ApiResponse.success(
                brokerListingService.update(principal.userUuid(), listingUuid, request));
    }

    @Operation(summary = "게시", description = """
            여기가 **관문**이다. 다섯이 갖춰져야 올라간다 —
            사진 1장 이상 · 관리비 정보 · 상세설명 · **전입신고 가능 여부 확정** · **서비스 지역의 시군구**.

            지역이 없거나 그 지역이 지금 닫혀 있으면 막는다(`PUBLISH_REGION_REQUIRED`).
            같은 순간 겹친 요청이면 **409 `CONFLICT`** — 다시 불러와 재시도한다(그사이 바뀐 상태는 상태 검사 코드로 온다).

            전입신고를 `ASK` 로 두면 막는다(`PUBLISH_RESIDENT_REGISTRATION_REQUIRED`).
            유학생에게는 외국인등록증 주소지가 걸린 문제라 **모른 채 계약하면 체류 행정이 막힌다.**
            '불가'는 통과한다 — 막는 것이 아니라 **밝히게** 하는 장치다.

            게시하면 **1개월** 노출된다. 만료 후 **7일** 안에 연장하지 않으면 `ARCHIVED` 가 된다.
            """)
    @PostMapping("/{listingUuid}/publish")
    public ApiResponse<BrokerListingResponse> publish(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.publish(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "내리기", description = "`HIDDEN` 으로 간다. **다시 올릴 수 있다** — 관리자 차단(`BLOCKED`)과 다른 점이다.")
    @PostMapping("/{listingUuid}/unpublish")
    public ApiResponse<BrokerListingResponse> unpublish(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.unpublish(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "거래 완료", description = """
            `COMPLETED`. **자동 종료(`ARCHIVED`)와 반드시 구분한다** —
            섞으면 나중에 성약률 통계가 오염된다.
            """)
    @PostMapping("/{listingUuid}/complete")
    public ApiResponse<BrokerListingResponse> complete(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.complete(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "예약 중으로", description = """
            `ACTIVE` → `RESERVED`. 유학생 목록 · 지도 · 상세에 **그대로 보이고** "예약 중"이 붙는다 —
            조건이 맞으면 대기하려는 사람이 있다.
            기한(`expiresAt`)은 바뀌지 않는다 — 예약 중도 게시 1개월 안이다. 만료되면 예약 표시는 사라지고,
            연장하면 게시 중(`ACTIVE`)으로 돌아온다.

            - `LISTING_NOT_ACTIVE`(409) — 게시 중(`ACTIVE`)이 아니다.
            - `CONFLICT`(409) — 같은 순간 겹친 요청이다. 다시 불러와 재시도한다.
            """)
    @PostMapping("/{listingUuid}/reserve")
    public ApiResponse<BrokerListingResponse> reserve(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.reserve(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "예약 해제", description = """
            `RESERVED` → `ACTIVE`. 기한은 그대로다.

            - `LISTING_NOT_RESERVED`(409) — 예약 중인 매물이 아니다.
            - `CONFLICT`(409) — 같은 순간 겹친 요청이다. 다시 불러와 재시도한다.
            """)
    @PostMapping("/{listingUuid}/unreserve")
    public ApiResponse<BrokerListingResponse> unreserve(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.unreserve(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "연장", description = """
            만료된 매물을 다시 **1개월** 띄운다. 창은 **만료 + 7일**이고 지나면
            `LISTING_NOT_RENEWABLE` 이다 — 배치가 아직 `ARCHIVED` 로 옮기지 못했어도 막힌다.
            **연장도 게시다** — 게시 관문을 다시 거친다(`PUBLISH_*`, 지역이 닫혔으면 `PUBLISH_REGION_REQUIRED`).
            """)
    @PostMapping("/{listingUuid}/renew")
    public ApiResponse<BrokerListingResponse> renew(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(brokerListingService.renew(principal.userUuid(), listingUuid));
    }

    @Operation(summary = "삭제", description = """
            **`DRAFT` 만 지울 수 있다**(`LISTING_NOT_DELETABLE`).
            한 번이라도 게시된 매물은 허위매물 분쟁의 증거라 행을 남긴다 —
            내리려면 `unpublish` 를 쓴다. 지운 초안의 사진 파일은 폐기된다(다른 매물에도 붙어 있으면 남는다).
            """)
    @DeleteMapping("/{listingUuid}")
    public ApiResponse<Void> delete(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        brokerListingService.delete(principal.userUuid(), listingUuid);
        return ApiResponse.ok();
    }
}
