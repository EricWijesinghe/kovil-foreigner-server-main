package com.kovil.domain.university.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.university.dto.response.UniversityResponse;
import com.kovil.domain.university.service.UniversityQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "University", description = "대학교 지도 레이어 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/universities")
public class UniversityController {

    private final UniversityQueryService universityQueryService;

    @Operation(summary = "대학교 — 지도 영역 안", description = """
            **비로그인 공개.** 지도 영역(네 좌표, 모두 필수 — 빠지면 400 `INVALID_REQUEST`) 안의 대학교 캠퍼스.
            캠퍼스마다 한 점이다(본교 · 제2캠퍼스 · 대학원만 있는 별도 캠퍼스는 각각 한 점).

            - 한 응답 최대 200개(지도 영역 가운데에서 가까운 순, 같은 거리면 학교코드 순). 넘으면 가장자리 학교가 빠진다.
            - 이름 · 짧은 이름 · 캠퍼스 이름은 `Accept-Language` 가 ko 면 국문, 그 밖은 영문.
            - 학교를 누르면 앱이 `lat` · `lng` 를 중심으로 `GET /api/v1/listings?lat=&lng=&radius=1000` 을 부른다.
            - 원격 대학(사이버 · 방송통신)은 없다 — 통학 캠퍼스가 없다.
            """)
    @GetMapping
    public ApiResponse<List<UniversityResponse>> findWithin(
            @RequestParam BigDecimal swLat, @RequestParam BigDecimal swLng,
            @RequestParam BigDecimal neLat, @RequestParam BigDecimal neLng) {
        return ApiResponse.success(universityQueryService.findWithin(swLat, swLng, neLat, neLng));
    }
}