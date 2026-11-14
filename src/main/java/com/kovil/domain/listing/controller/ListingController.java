package com.kovil.domain.listing.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.listing.dto.request.ListingPolygon;
import com.kovil.domain.listing.dto.request.ListingRadius;
import com.kovil.domain.listing.dto.request.ListingReportCreateRequest;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.response.*;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "Listing", description = """
        **유학생이 보는 매물.**

        여기 응답에는 **실좌표·중개사 전화번호·숨긴 층이 없다.**
        - 좌표는 약 10m 흐린 값이다 — 목록을 훑어 임대인 주소를 전수 수집하는 것을 막는다.
        - 전화번호는 **문의 버튼을 눌렀을 때만** 나온다(목록에서 번호를 긁을 수 없다).
        - 중개사가 층을 '중층'으로 숨겼으면 정확한 층은 내려가지 않는다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/listings")
public class ListingController {

    private final ListingQueryService listingQueryService;
    private final ListingFavoriteService listingFavoriteService;
    private final ListingInquiryService listingInquiryService;
    private final ListingTranslationService listingTranslationService;
    private final ListingNearbyService listingNearbyService;
    private final ListingReportService listingReportService;
    private final ListingRegionResolver regionResolver;

    @Operation(summary = "매물 목록", description = """
            필터는 **평평한 쿼리 파라미터**다. 비우면 그 축은 전체다.

            `sort` 기본값은 `RECOMMENDED` — **전입신고 가능한 매물을 먼저** 올리고 그 안에서
            최신순이다. 유학생에게는 외국인등록증 주소지가 걸린 조건이라 그것이 첫 축이다.

            ⚠ `LATEST` 는 **최초 게시 시각** 기준이다. 중개사가 수정해도 위로 올라오지 않는다 —
            갱신 기준으로 두면 버튼 한 번으로 상단을 차지하는 끌어올리기가 된다.

            **반경**: `lat` · `lng` · `radius`(미터, 100 ~ 3,000)를 **셋 다** 주면 그 원 안의 매물만 나온다 — 대학교 1km ·
            역 500m · 정류장 300m 원의 "○○ 주변 매물 N개"가 이 목록의 `totalElements` 다. 판정은 매물의 흐린 좌표로 한다.
            셋 중 일부만 주거나 범위를 벗어나면 400 `LISTING_RADIUS_INVALID` — 조용히 무시하면 "○○ 주변" 제목 아래 전체 매물이 나온다.
            지도 영역(네 좌표)과 함께 주면 둘 다 만족하는 매물이다. 지도(`/map`)는 반경을 받지 않는다.

            **그린 구역**: `polygon` 을 주면 **그 구역 안의 매물만** 온다 — `totalElements` 가 "그린 구역 매물 N" 이다.
            다른 필터 · 지도 영역과 함께 걸리고, 정렬 · 페이지도 그대로 쓴다. 경계 위의 매물은 안으로 친다.
            반경과 함께 주면 400 `LISTING_RADIUS_INVALID`. 지도(`/map`)는 polygon 을 받지 않는다 — 그리기 화면은 지도에 핀을 그리지 않는다.

            **사무소**: `officeUuid`(상세의 `office.officeUuid`)를 주면 그 사무소의 공개 매물만 온다 — "이 사무소의 다른 방".
            없는 · 광고할 수 없는 사무소면 빈 페이지(200, 400 아님). 다른 필터 · 반경 · 그린 구역과 함께 걸린다.
            지금 보던 매물은 앱이 결과에서 뺀다. 지도(`/map`)는 officeUuid 를 받지 않는다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<ListingSummaryResponse>> search(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "ONE_TWO_ROOM · HOUSE_VILLA · OFFICETEL · APT")
            @RequestParam(required = false) ListingCategory category,
            @RequestParam(required = false) List<SellingType> sellingTypes,
            @RequestParam(required = false) List<RoomType> roomTypes,
            @Parameter(description = "GROUND · SEMI_BASEMENT · ROOFTOP. 반지하·옥탑을 거르는 축")
            @RequestParam(required = false) List<FloorType> floorTypes,
            @RequestParam(required = false) Long depositMin,
            @RequestParam(required = false) Long depositMax,
            @RequestParam(required = false) Long rentMin,
            @RequestParam(required = false) Long rentMax,
            @RequestParam(required = false) BigDecimal areaMin,
            @RequestParam(required = false) BigDecimal areaMax,
            @RequestParam(defaultValue = "false") boolean canParking,
            @RequestParam(defaultValue = "false") boolean shortLease,
            @Parameter(description = "POSSIBLE 로 주면 전입신고 되는 매물만")
            @RequestParam(required = false) AvailabilityAnswer residentRegistration,
            @Parameter(description = "시군구 법정동코드(`GET /api/v1/regions` 의 code). 닫혔거나 없는 코드면 400")
            @RequestParam(required = false) String regionCode,
            @Parameter(description = "\"이 사무소의 다른 방\" — 상세의 `office.officeUuid`. 없거나 광고할 수 없는 사무소면 빈 페이지(200)")
            @RequestParam(required = false) String officeUuid,
            @Parameter(description = "지도 영역. 네 값을 **모두** 줘야 적용된다")
            @RequestParam(required = false) BigDecimal swLat,
            @RequestParam(required = false) BigDecimal swLng,
            @RequestParam(required = false) BigDecimal neLat,
            @RequestParam(required = false) BigDecimal neLng,
            @Parameter(description = "반경 중심 위도 — lat · lng · radius 는 셋 다 주거나 셋 다 비운다")
            @RequestParam(required = false) BigDecimal lat,
            @Parameter(description = "반경 중심 경도")
            @RequestParam(required = false) BigDecimal lng,
            @Parameter(description = "반경(미터, 100 ~ 3,000). 앱은 대학교 1000 · 역 500 · 정류장 300 을 보낸다")
            @RequestParam(required = false) Integer radius,
            @Parameter(description = "그린 구역. 꼭짓점마다 `위도:경도`, 꼭짓점 사이 `,` — "
                    + "예 `37.5601234:126.9368123,37.5590456:126.9420789,37.5550123:126.9405456`. "
                    + "꼭짓점 3~30개(같은 점이 이어 오면 하나로 친다 · 처음 점을 끝에 다시 써도 된다 · 보내는 점은 60개 · 2,000자까지). "
                    + "좌표는 소수 7자리 이상으로 보낸다(4자리로 반올림하면 이웃이 아닌 꼭짓점이 겹쳐 400 이 날 수 있다) · 지수 표기 불가. "
                    + "빈 값 · 형식 · 개수 · 범위가 틀리거나 선이 겹치면 400 `LISTING_POLYGON_INVALID`, "
                    + "구역 안 후보가 5,000건을 넘으면 400 `LISTING_POLYGON_TOO_LARGE`")
            @RequestParam(required = false) String polygon,
            @RequestParam(defaultValue = "RECOMMENDED") ListingSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var nearby = ListingRadius.ofNullable(lat, lng, radius);
        var drawn = ListingPolygon.parseOrNull(polygon);
        var condition = new ListingSearchCondition(category, sellingTypes, roomTypes, floorTypes,
                depositMin, depositMax, rentMin, rentMax, areaMin, areaMax,
                canParking, shortLease, residentRegistration,
                regionResolver.resolveIdOrNull(regionCode), officeUuid,
                swLat, swLng, neLat, neLng);
        var paging = Paging.of(page, size);
        return ApiResponse.success(listingQueryService.search(
                viewerUuidOf(principal), condition, nearby, drawn, sort,
                paging.getPageNumber(), paging.getPageSize()));
    }

    @Operation(summary = "지도", description = """
            `bbox`(네 좌표) 안의 매물을 준다. 응답은 **두 모양 중 하나**다.

            - `mode=PIN` — 개별 매물. 좌표는 흐린 값이다.
            - `mode=CLUSTER` — **시군구 묶음**. 영역 안 매물이 많으면 이쪽이 온다.

            ⚠ 전환 기준은 **zoom 이 아니라 개수**다. zoom 만 믿으면 축소 상태에서
            수천 건을 핀으로 내려보내 클라이언트가 멈춘다. `zoom` 은 참고로만 받는다.

            ⚠ v1 의 묶음 단위는 시군구뿐이다 — `regions` 가 2단이라 동 집계를 할 수 없다.
            """)
    @GetMapping("/map")
    public ApiResponse<ListingMapResponse> map(
            @RequestParam BigDecimal swLat, @RequestParam BigDecimal swLng,
            @RequestParam BigDecimal neLat, @RequestParam BigDecimal neLng,
            @Parameter(description = "참고용. 전환은 개수로 판정한다")
            @RequestParam(required = false) Integer zoom,
            @RequestParam(required = false) ListingCategory category,
            @RequestParam(required = false) List<SellingType> sellingTypes,
            @RequestParam(required = false) List<RoomType> roomTypes,
            @RequestParam(required = false) List<FloorType> floorTypes,
            @RequestParam(required = false) Long depositMin,
            @RequestParam(required = false) Long depositMax,
            @RequestParam(required = false) Long rentMin,
            @RequestParam(required = false) Long rentMax,
            @RequestParam(required = false) BigDecimal areaMin,
            @RequestParam(required = false) BigDecimal areaMax,
            @RequestParam(defaultValue = "false") boolean canParking,
            @RequestParam(defaultValue = "false") boolean shortLease,
            @RequestParam(required = false) AvailabilityAnswer residentRegistration) {

        return ApiResponse.success(listingQueryService.map(new ListingSearchCondition(
                category, sellingTypes, roomTypes, floorTypes,
                depositMin, depositMax, rentMin, rentMax, areaMin, areaMax,
                canParking, shortLease, residentRegistration, null, null,
                swLat, swLng, neLat, neLng)));
    }

    @Operation(summary = "시세 — 광고 매물 평균", description = """
            ⚠ **실거래가가 아니다.** 지금 광고 중인(공개 · 예약 중) 매물의 **호가 평균**이다. 화면 라벨은 "광고 매물 평균".

            대상 구 = 지도 영역(네 좌표) 안에 조건에 맞는 공개 매물이 1건 이상 있는 시군구.
            숫자는 **그 구 전체** 기준이다 — 지도를 조금 옮길 때마다 시세가 바뀌면 안 된다.
            `regions` 는 화면 안 개수(`inViewCount`) 내림차순 — 요약 칩은 첫 행을 쓴다.

            보증금 · 월세를 **각각** 산술 평균한다(전환율 보정 없음, 원 단위 반올림). 전세(`LEASE`)는 `avgMonthlyRent` 가 없다.
            표본이 `minSampleSize`(5) 보다 적으면 행은 남고 두 평균이 null — 앱은 "매물이 적어 시세를 내지 않았어요".
            지도 영역 · `sellingType` 이 빠지면 400 `INVALID_REQUEST`.
            """)
    @GetMapping("/market-prices")
    public ApiResponse<ListingMarketPriceResponse> marketPrices(
            @RequestParam BigDecimal swLat, @RequestParam BigDecimal swLng,
            @RequestParam BigDecimal neLat, @RequestParam BigDecimal neLng,
            @Parameter(description = "필수 · 하나 — 월세(MONTHLY_RENT)와 전세(LEASE)를 섞은 평균은 뜻이 없다")
            @RequestParam SellingType sellingType,
            @RequestParam(required = false) ListingCategory category,
            @Parameter(description = "라벨 '원룸 월세 평균'의 방 종류. 비우면 그 카테고리 전체")
            @RequestParam(required = false) RoomType roomType) {

        return ApiResponse.success(listingQueryService.marketPrices(new ListingSearchCondition(
                category, List.of(sellingType), roomType == null ? null : List.of(roomType), null,
                null, null, null, null, null, null, false, false, null, null, null,
                swLat, swLng, neLat, neLng), sellingType));
    }

    @Operation(summary = "매물 묶음 요약 — 조회수 안 올림", description = """
            핀 미리보기 · 최근 본 방 새로고침용. `uuids` 를 **1~50개** 반복해서 보낸다(`?uuids=a&uuids=b`).
            0개이거나 50개를 넘으면 400 `INVALID_REQUEST` — 일부만 잘라 주지 않는다(잘린 것을 "없는 매물"로 오인한다).
            같은 uuid 를 여러 번 보내면 한 번만 온다.

            - `items`: **요청 순서대로**, 지금 공개인 매물만. 목록 카드와 같은 모양이고 로그인이면 `favorited` 가 채워진다.
            - `missing`: 공개가 아니거나 없는 uuid. 내려간 이유는 가르지 않는다.

            상세(`GET /{listingUuid}`)와 달리 **조회수를 올리지 않는다.** 한도도 쓰지 않는다.
            """)
    @GetMapping("/summaries")
    public ApiResponse<ListingSummaryBatchResponse> summaries(
            @CurrentUser AuthenticatedUser principal,
            @Parameter(description = "매물 uuid 1~50개. `?uuids=a&uuids=b`")
            @RequestParam(required = false) List<String> uuids) {
        return ApiResponse.success(listingQueryService.summaries(viewerUuidOf(principal), uuids));
    }

    @Operation(summary = "매물 상세", description = """
            **전화번호는 여기 없다.** `POST /{listingUuid}/inquiries` 를 호출해야 나온다 —
            상세에 실으면 목록을 훑어 중개사 번호를 전수 수집할 수 있다.

            상세 주소는 중개사가 공개를 켠 매물에서만 채워진다. 아니면 `address` 가 비고
            `dongName` 만 온다.

            `description` 은 **한국어 원문**이다. 번역은 별도 엔드포인트가 준다.

            호출할 때마다 조회수가 오른다.
            """)
    @GetMapping("/{listingUuid}")
    public ApiResponse<ListingDetailResponse> get(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        return ApiResponse.success(
                listingQueryService.getDetail(viewerUuidOf(principal), listingUuid));
    }

    @Operation(summary = "주변시설", description = """
            매물 둘레 500m 안의 편의점 · 카페 · 은행 · 공공기관 · 대형마트 · 병원 · 약국 · 지하철역.
            카테고리마다 전체 수(`count`)와 가까운 순 최대 3곳(이름 · 좌표)을 준다. 순서와 8개는 매물마다 같다.

            중심은 매물의 **흐린 좌표**다. 시간 · 거리는 싣지 않는다 — 직선거리 어림값은 실제와 달라서다.

            ⚠ **실패해도 200 이다.** 카카오 장애 · 키 없음 · 쿼터 초과면 `status=UNAVAILABLE` 에 빈 `categories` —
            앱은 주변시설 칸을 숨긴다. 공개 매물이 아니면 404, 한도를 넘으면 429.

            조회수를 올리지 않는다. 상세 화면에서만 부른다(목록 · 지도에서 부르지 말 것).
            """)
    @RateLimited({RateLimitPolicy.LISTING_NEARBY, RateLimitPolicy.LISTING_NEARBY_GLOBAL})
    @GetMapping("/{listingUuid}/nearby")
    public ApiResponse<ListingNearbyResponse> nearby(@PathVariable String listingUuid) {
        return ApiResponse.success(listingNearbyService.nearby(listingUuid));
    }

    private String viewerUuidOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.userUuid();
    }

    @Operation(summary = "문의 — 중개사 연락처 받기", description = """
            **로그인 필요** — 가입 유도. 비로그인은 401 이고 앱은 로그인 안내를 띄운다.

            **중개사 번호가 나오는 유일한 경로다.** 상세 응답에는 번호가 없다 —
            목록을 훑어 전수 수집하는 것을 막기 위해서다. 그래서 여기에는 한도가 걸려 있다
            (개인 하루 30건 + 전역 캡, 초과하면 429).

            **서버는 문자를 보내지 않는다.** 앱이 이 응답으로 `sms:` 링크를 열 뿐이라
            발송 비용도 발신번호 등록도 없다.

            `smsBody` 는 **한국어로 고정**이다 — 받는 사람이 한국인 중개사라
            유학생의 언어로 보내면 말이 통하지 않는다. 매물번호가 들어 있는 이유는
            중개사가 매물을 수백 건 들고 있어서다.

            매물에 지정된 번호가 없으면 **사무소 공시 번호**로 떨어진다.
            호출할 때마다 문의 수가 오른다.
            """)
    @RateLimited({RateLimitPolicy.LISTING_INQUIRY, RateLimitPolicy.LISTING_INQUIRY_GLOBAL})
    @PostMapping("/{listingUuid}/inquiries")
    public ApiResponse<ListingInquiryResponse> inquire(@PathVariable String listingUuid) {
        return ApiResponse.success(listingInquiryService.inquire(listingUuid));
    }

    @Operation(summary = "매물 신고", description = """
            **로그인 필요** — 비로그인 신고는 경쟁 사무소의 허위 신고 남발 통로가 된다.
            공개 중인 매물만 신고할 수 있다(아니면 404). 같은 매물을 두 번 신고하면 409 `LISTING_REPORT_DUPLICATE`.
            사유(`reasonCode`)가 없으면 400 — 오류 목록에 `LISTING_REPORT_REASON_REQUIRED`.

            신고해도 신고자 · 중개사 누구에게도 알리지 않는다. 매물을 내릴지는 관리자가 판단한다.
            한도: 하루 20건(넘으면 429).
            """)
    @RateLimited(RateLimitPolicy.LISTING_REPORT)
    @PostMapping("/{listingUuid}/reports")
    public ApiResponse<Void> report(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String listingUuid,
            @Valid @RequestBody ListingReportCreateRequest request) {
        listingReportService.report(principal.userUuid(), listingUuid, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "상세설명 번역", description = """
            상세 응답의 `description` 은 **한국어 원문**이고, 번역은 여기서만 나온다.

            ⚠ **실패해도 200 이다.** 벤더가 죽었거나 한도에 걸리면 한국어 원문을 그대로 돌려준다 —
            번역은 곁다리라 여기서 에러를 내면 설명이 통째로 안 보인다.
            그래서 응답의 `translated` 를 보고 "번역됨" 표시를 결정해야 한다.
            `ko` 를 요청해도 `translated=false` 다(원문이 한국어다).

            결과는 **(매물, 언어, 원문 버전)** 으로 캐시된다. 중개사가 설명을 고치면 버전이 올라
            자동으로 다시 번역된다 — 옛 번역이 남아 **존재하지 않는 조건**을 읽히는 일은 없다.

            호출당 과금이라 한도가 있다(개인 **시간당 30건** + 전역 하루 캡, 초과하면 429).
            캐시 히트는 벤더를 부르지 않지만 **한도는 똑같이 소비한다** —
            캐시 여부를 몰라야 호출자가 한도를 피해 다니지 못한다.
            """)
    @RateLimited({RateLimitPolicy.LISTING_TRANSLATE, RateLimitPolicy.LISTING_TRANSLATE_GLOBAL})
    @PostMapping("/{listingUuid}/description/translate")
    public ApiResponse<ListingTranslationResponse> translateDescription(
            @PathVariable String listingUuid,
            @Parameter(description = "languages.code (예: en). 꺼져 있거나 없는 코드면 400")
            @RequestParam String language) {
        return ApiResponse.success(
                listingTranslationService.translate(listingUuid, language));
    }

    @Operation(summary = "찜하기", description = """
            이미 찜했으면 아무 일도 일어나지 않는다 — 연타·재시도로 카운터가 두 번 오르지 않는다.
            **공개 중인 매물만** 찜할 수 있다.
            """)
    @PostMapping("/{listingUuid}/favorite")
    public ApiResponse<Void> favorite(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        listingFavoriteService.add(principal.userUuid(), listingUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "찜 해제", description = """
            **내려간 매물도 해제할 수 있다** — 공개가 끝난 매물이 찜 목록에 박혀
            지울 길이 없으면 안 되기 때문이다.
            """)
    @DeleteMapping("/{listingUuid}/favorite")
    public ApiResponse<Void> unfavorite(
            @CurrentUser AuthenticatedUser principal, @PathVariable String listingUuid) {
        listingFavoriteService.remove(principal.userUuid(), listingUuid);
        return ApiResponse.ok();
    }
}