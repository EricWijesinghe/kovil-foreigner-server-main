package com.kovil.domain.region.service;

import com.kovil.domain.listing.service.ListingRegionClosureService;
import com.kovil.domain.region.dto.AdminRegionRequest;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 지역 저장이 매물 내리기를 **언제** 부르는가. 닫히는 순간 한 번뿐이다(PO 확정 2026-10-06).
 * ★ 지역 화면에서 이름 오타 하나 고쳤다고 그 지역 광고가 전부 내려가면 안 되고,
 *   다시 열었다고 확인 없이 되살아나도 안 된다.
 */
class AdminRegionClosureTriggerTest {

    private RegionRepository regionRepository;
    private ListingRegionClosureService closureService;
    private AdminRegionService service;

    @BeforeEach
    void setUp() {
        regionRepository = mock(RegionRepository.class);
        closureService = mock(ListingRegionClosureService.class);
        service = new AdminRegionService(regionRepository, closureService);
    }

    @Test
    @DisplayName("활성 지역을 닫으면 그 지역의 매물을 내린다")
    void closing_an_active_region_hides_listings() {
        Region region = givenRegion(true);

        service.update(38L, request(false));

        verify(closureService).hideListingsIn(region);
    }

    @Test
    @DisplayName("이름만 고치면(활성 유지) 아무것도 내리지 않는다")
    void renaming_does_not_hide() {
        givenRegion(true);

        service.update(38L, request(true));

        verify(closureService, never()).hideListingsIn(any());
    }

    // 활성 칸을 생략하면 지금 값을 유지한다 — 생략이 "닫기"로 읽히면 안 된다.
    @Test
    @DisplayName("활성 칸을 생략하면 지금 값을 유지하고 아무것도 내리지 않는다")
    void omitted_active_flag_keeps_it_open() {
        givenRegion(true);

        service.update(38L, request(null));

        verify(closureService, never()).hideListingsIn(any());
    }

    @Test
    @DisplayName("이미 닫힌 지역을 다시 저장해도 다시 내리지 않는다")
    void resaving_a_closed_region_does_nothing() {
        givenRegion(false);

        service.update(38L, request(false));

        verify(closureService, never()).hideListingsIn(any());
    }

    @Test
    @DisplayName("다시 열어도 매물을 자동으로 되살리지 않는다")
    void reopening_does_not_touch_listings() {
        givenRegion(false);

        service.update(38L, request(true));

        verifyNoInteractions(closureService);
    }

    private Region givenRegion(boolean active) {
        Region region = Region.builder().id(38L).parentId(1L).code("11620")
                .nameKo("관악구").nameEn("Gwanak-gu").sortOrder(1).isActive(active).build();
        given(regionRepository.findById(38L)).willReturn(Optional.of(region));
        return region;
    }

    private static AdminRegionRequest request(Boolean active) {
        return new AdminRegionRequest(1L, "11620", "관악구", "Gwanak-gu", 1, active);
    }
}
