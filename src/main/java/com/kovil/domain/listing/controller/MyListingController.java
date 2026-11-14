package com.kovil.domain.listing.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.domain.listing.dto.response.ListingFavoriteItem;
import com.kovil.domain.listing.service.ListingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me")
@Tag(name = "Listing · Me", description = "내 찜 목록. 로그인 필요.")
public class MyListingController {

    private final ListingQueryService listingQueryService;

    @Operation(summary = "내 찜 목록", description = """
            최근에 담은 순. 내려간 매물도 남음 — `available=false` 이고 `listing` 대신
            `brief`(사진 · 가격 · 방 종류)만 온다. 내려간 이유는 가르지 않음.
            지우려면 `DELETE /api/v1/listings/{listingUuid}/favorite`.
            """)
    @GetMapping("/listing-favorites")
    public ApiResponse<PageResponse<ListingFavoriteItem>> findMyFavorites(
            @CurrentUser AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                listingQueryService.findMyFavorites(principal.userUuid(), Paging.of(page, size)));
    }

}
