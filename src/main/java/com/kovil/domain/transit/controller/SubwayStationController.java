package com.kovil.domain.transit.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.transit.dto.response.SubwayStationResponse;
import com.kovil.domain.transit.service.SubwayStationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "Transit", description = "교통 지도 레이어 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transit/subway-stations")
public class SubwayStationController {

    private final SubwayStationQueryService subwayStationQueryService;

    @Operation(summary = "지하철역 — 지도 영역 안", description = """
            **비로그인 공개.** 지도 영역(네 좌표, 모두 필수 — 빠지면 400 `INVALID_REQUEST`) 안의 지하철역(전국 도시철도).
            환승역은 **역 하나 + `lines[]` 여럿**(마커 하나에 색 점 여럿, 배열 순서가 점 순서).

            - 한 응답 최대 300개(지도 영역 가운데에서 가까운 순, 같은 거리면 역 코드 순). 넘으면 가장자리 역이 빠진다.
            - 역 이름 · 노선 이름은 `Accept-Language` 가 ko 면 국문, 그 밖은 영문. 노선색은 `#RRGGBB`.
            - 역을 누르면 앱이 `lat` · `lng` 를 중심으로 `GET /api/v1/listings?lat=&lng=&radius=500` 을 부른다.
            """)
    @GetMapping
    public ApiResponse<List<SubwayStationResponse>> findWithin(
            @RequestParam BigDecimal swLat, @RequestParam BigDecimal swLng,
            @RequestParam BigDecimal neLat, @RequestParam BigDecimal neLng) {
        return ApiResponse.success(subwayStationQueryService.findWithin(swLat, swLng, neLat, neLng));
    }
}