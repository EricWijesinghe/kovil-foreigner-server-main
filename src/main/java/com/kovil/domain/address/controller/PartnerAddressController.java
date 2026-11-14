package com.kovil.domain.address.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.common.web.Paging;
import com.kovil.domain.address.dto.response.AddressCoordinateResponse;
import com.kovil.domain.address.dto.response.AddressSearchResponse;
import com.kovil.domain.address.service.AddressCoordinateService;
import com.kovil.domain.address.service.AddressSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Partner · Address", description = """
        주소 검색 프록시 — 파트너스의 사무소 소재지(가입 · 다시 제출 · 변경 신청)와 매물 주소 입력.

        키를 클라이언트에 내리지 않기 위해 서버가 벤더를 대신 부른다.
        - **검색**(`/search`, 비로그인 공개): 서버 설정 `address-search.provider` — `juso`(행정안전부 도로명주소, 무료 ·
          건물명 · 동 이름으로도 찾음 · **좌표 없음**) 또는 `geocoding`(좌표 벤더로 검색 · 좌표 함께).
        - **좌표**(`/coordinate`, 매물 관리 권한): 고른 후보 하나의 좌표를 좌표 벤더(`geocoding.provider`, 운영은 네이버)로
          받는다. 검색 결과에 좌표가 비어 있을 때(juso) 고른 뒤 부른다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner/address")
public class PartnerAddressController {

    private final AddressSearchService addressSearchService;
    private final AddressCoordinateService addressCoordinateService;

    // 가입 중(로그인 전)인 공인중개사도 소재지를 검색해야 해서 공개. 비로그인 전체 상한(GUEST)은 로그인한 호출을 세지 않아,
    // 비로그인 폭주가 매물 등록의 검색까지 막지 않음. 전역 상한(BURST · GLOBAL)은 행정안전부가 우리 서버 IP 를 막지 않게.
    @Operation(summary = "주소 검색", description = """
            도로명 · 지번 · 건물명 · 동 이름 일부로 후보 목록을 돌려준다. page는 0부터.
            검색어가 비면 벤더를 부르지 않고 빈 목록이다. 검색어는 앞뒤 공백을 떼고 80자까지만 본다.
            결과마다 서비스 지역(시군구 `regionCode` · 열림 `regionActive`)과 법정동 `dongName` 을 서버가 판정해 싣는다.

            **오류**: `ADDRESS_SEARCH_UNAVAILABLE`(503) — 행정안전부 점검 · 장애 · 키 문제. 잠시 뒤 다시. 검색어가 잘못된 것은
            빈 목록이다. `GEOCODING_*`(502) — 좌표 벤더로 검색할 때(`geocoding`)의 벤더 오류.

            요청 제한(넘으면 429 `RATE_LIMITED` + `Retry-After`): 사용자(비로그인은 IP)마다 1분 20회 · 하루 300회,
            비로그인 호출 전체 하루 3,000회, 전체 1분 600회 · 하루 80,000회.
            """)
    @GetMapping("/search")
    @RateLimited({RateLimitPolicy.ADDRESS_SEARCH, RateLimitPolicy.ADDRESS_SEARCH_DAILY,
            RateLimitPolicy.ADDRESS_SEARCH_GUEST, RateLimitPolicy.ADDRESS_SEARCH_BURST,
            RateLimitPolicy.ADDRESS_SEARCH_GLOBAL})
    public ApiResponse<AddressSearchResponse> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var paging = Paging.of(page, size);
        return ApiResponse.success(addressSearchService.search(query, paging.getPageNumber(), paging.getPageSize()));
    }

    // 매물 주소에만 좌표가 든다(소재지는 글자만) — 매물을 쓸 수 있는 사람만. 호출마다 네이버 과금이라 사람마다 · 전체 상한을 건다.
    @Operation(summary = "고른 주소의 좌표", description = """
            검색 결과에서 고른 후보의 도로명(없으면 지번)으로 좌표를 받는다. 도로명으로 못 찾으면 지번으로 한 번 더 본다.
            **못 찾으면 200 에 둘 다 null**(오류 아님) — 앱은 그 후보를 고르지 않고 다른 결과를 고르게 안내한다.
            둘 다 비우면 벤더를 부르지 않고 null.

            **오류**: `GEOCODING_*`(502) — 좌표 벤더 키 · 한도 · 장애. 403 — 매물 관리 권한 없음(요청 제한보다 먼저 막는다).

            요청 제한(넘으면 429 `RATE_LIMITED` + `Retry-After`): 사용자마다 1분 20회 · 하루 300회, 전체 하루 10,000회.
            """)
    @GetMapping("/coordinate")
    @PreAuthorize("hasAuthority('PERM_LISTING_MANAGE')")
    @RateLimited({RateLimitPolicy.ADDRESS_COORDINATE, RateLimitPolicy.ADDRESS_COORDINATE_DAILY,
            RateLimitPolicy.ADDRESS_COORDINATE_GLOBAL})
    public ApiResponse<AddressCoordinateResponse> coordinate(
            @Parameter(description = "검색 결과의 roadAddress 그대로", example = "경기도 성남시 분당구 불정로 6")
            @RequestParam(required = false) String roadAddress,
            @Parameter(description = "검색 결과의 jibunAddress 그대로", example = "경기도 성남시 분당구 정자동 178-1")
            @RequestParam(required = false) String jibunAddress) {
        return ApiResponse.success(addressCoordinateService.locate(roadAddress, jibunAddress));
    }
}
