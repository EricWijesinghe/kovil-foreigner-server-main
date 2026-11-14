package com.kovil.domain.listing.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.listing.dto.request.SavedAreaCreateRequest;
import com.kovil.domain.listing.dto.response.SavedAreaResponse;
import com.kovil.domain.listing.service.SavedAreaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/saved-areas")
@Tag(name = "Listing · Me")
public class MySavedAreaController {

    private final SavedAreaService savedAreaService;

    @Operation(summary = "저장한 구역 목록", description = """
            최근에 저장한 순. 계정당 최대 20개라 **페이지가 없다**.
            `polygon` · `filters` 를 그대로 `GET /api/v1/listings` 에 실으면 저장할 때의 목록이 다시 나온다.
            """)
    @GetMapping
    public ApiResponse<List<SavedAreaResponse>> findMine(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(savedAreaService.findMine(principal.userUuid()));
    }

    @Operation(summary = "구역 저장", description = """
            그린 구역 + 그때 적용한 필터 + 이름을 계정에 저장한다. 응답의 `polygon` 은 정규화한 값이다(같은 점 연속 · 닫는 점 합침).

            - 이름: 앞뒤 공백을 빼고 1~20자, 같은 이름 허용 — 어기면 400 `INVALID_REQUEST`
            - `polygon`: 목록 API 와 같은 형식 · 같은 검증 — 틀리거나 비면 400 `LISTING_POLYGON_INVALID`
            - `filters`: 목록 API 파라미터와 같은 이름. 목록 API 가 거절할 값이면 저장도 400(닫힌 지역 `LISTING_REGION_INVALID` 등)
            - 계정당 20개 — 21번째는 409 `SAVED_AREA_LIMIT_EXCEEDED`(먼저 하나를 지운다)
            """)
    @PostMapping
    public ApiResponse<SavedAreaResponse> save(@CurrentUser AuthenticatedUser principal,
                                               @Valid @RequestBody SavedAreaCreateRequest request) {
        return ApiResponse.success(savedAreaService.save(principal.userUuid(), request));
    }

    @Operation(summary = "저장한 구역 지우기", description = "없거나 남의 구역이면 404 `SAVED_AREA_NOT_FOUND`(남의 것인지 알려 주지 않는다).")
    @DeleteMapping("/{savedAreaUuid}")
    public ApiResponse<Void> delete(@CurrentUser AuthenticatedUser principal, @PathVariable String savedAreaUuid) {
        savedAreaService.delete(principal.userUuid(), savedAreaUuid);
        return ApiResponse.ok();
    }
}