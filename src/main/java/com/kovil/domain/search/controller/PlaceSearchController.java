package com.kovil.domain.search.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.search.dto.response.PlaceSearchResponse;
import com.kovil.domain.search.service.PlaceSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search", description = "장소 검색 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/search/places")
public class PlaceSearchController {

    private final PlaceSearchService placeSearchService;

    @Operation(summary = "장소 통합 검색 — 지역 · 대학교 · 지하철역", description = """
            **비로그인 공개.** 국문 · 영문 이름에 검색어가 들어 있는(대소문자 무시) 열린 시군구 · 대학교(캠퍼스) · 지하철역(전국).

            - 순서: 완전 일치 → 앞부분 일치 → 포함, 같으면 지역 → 대학교 → 역. 유형마다 최대 10개.
            - 이름 · 부제는 `Accept-Language` 가 ko 면 국문, 그 밖은 영문.
            - 지역을 누르면 `lat` · `lng` 로 지도를 옮기고(없으면 `regionCode` 로 목록만), 대학교 · 역은 반경 목록
              (`GET /api/v1/listings?lat=&lng=&radius=` — 대학교 1000 · 역 500)을 연다.
            """)
    @GetMapping
    public ApiResponse<PlaceSearchResponse> search(
            @Parameter(description = "검색어 — 앞뒤 공백을 떼고 50자까지만 본다. 비었으면 빈 목록(200), 오류는 없다", example = "신촌")
            @RequestParam(defaultValue = "") String query,
            @Parameter(description = "최대 개수 — 기본 20, 1 ~ 50 밖이면 가까운 끝값으로 맞춘다")
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(placeSearchService.search(query, limit));
    }
}