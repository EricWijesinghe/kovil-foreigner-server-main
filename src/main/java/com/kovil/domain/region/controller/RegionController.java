package com.kovil.domain.region.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.region.dto.response.RegionResponse;
import com.kovil.domain.region.service.RegionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Region", description = "지역 트리 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/regions")
public class RegionController {

    private final RegionQueryService regionQueryService;

    @Operation(summary = "임대 요청의 희망 지역 선택에 쓴다. **비로그인 공개**.", description = """
            시도 → 시군구 2단 트리.

            **서비스 미개시 지역은 목록에 없다** — FE가 따로 거를 필요가 없다.\n            시군구 노드의 `children`은 항상 빈 배열이다(null 아님).
            """)
    @GetMapping
    public ApiResponse<List<RegionResponse>> getRegions() {
        return ApiResponse.success(regionQueryService.findActiveRegionTree());
    }
}
