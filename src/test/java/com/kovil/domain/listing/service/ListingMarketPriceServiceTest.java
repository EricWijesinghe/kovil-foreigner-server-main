package com.kovil.domain.listing.service;

import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.response.ListingMarketPriceResponse;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * 시세 조립 격자 — 실거래가가 아니라 광고 중 매물의 평균 호가다.
 * ★ 대상 구는 지도 영역 안 개수로 고르고, 숫자는 그 구 **전체**(지도 영역 없이 센 집계)에서 온다.
 *   두 집계를 섞으면(영역 안 평균을 시세로 쓰면) 지도를 조금 옮길 때마다 시세가 바뀐다.
 * ★ 표본 5건 미만이면 행은 남고 두 평균이 null — 행을 빼면 앱이 "매물이 적어…"를 띄울 자리가 없다.
 */
class ListingMarketPriceServiceTest {

    private static final long SEODAEMUN = 38L;
    private static final long MAPO = 39L;
    private static final BigDecimal SW_LAT = new BigDecimal("37.55");
    private static final BigDecimal SW_LNG = new BigDecimal("126.90");
    private static final BigDecimal NE_LAT = new BigDecimal("37.58");
    private static final BigDecimal NE_LNG = new BigDecimal("126.96");

    private ListingRepository listingRepository;
    private RegionRepository regionRepository;
    private ListingQueryService service;
    private Locale before;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        regionRepository = mock(RegionRepository.class);
        service = new ListingQueryService(mock(UserQueryService.class), listingRepository,
                mock(BrokerageOfficeRepository.class), mock(ListingMaintenanceRepository.class),
                mock(ListingMaintenanceItemRepository.class), mock(ListingMaintenanceIncludeRepository.class),
                mock(ListingOptionRepository.class), mock(ListingSafetyRepository.class),
                mock(ListingImageRepository.class), mock(ListingFavoriteRepository.class), regionRepository,
                mock(StoredFileRepository.class));
        when(regionRepository.findByIdIn(any())).thenReturn(List.of(
                Region.builder().id(SEODAEMUN).parentId(1L).code("11410").nameKo("서대문구").nameEn("Seodaemun-gu").build(),
                Region.builder().id(MAPO).parentId(1L).code("11440").nameKo("마포구").nameEn("Mapo-gu").build()));
        before = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.KOREAN);
    }

    @AfterEach
    void restoreLocale() {
        LocaleContextHolder.setLocale(before);
    }

    private static ListingSearchCondition condition(SellingType sellingType) {
        return new ListingSearchCondition(ListingCategory.ONE_TWO_ROOM, List.of(sellingType), List.of(RoomType.ONE_ROOM),
                null, null, null, null, null, null, null, false, false, null, null, null,
                SW_LAT, SW_LNG, NE_LAT, NE_LNG);
    }

    private void inView(RegionCluster... clusters) {
        when(listingRepository.aggregatePublicByRegion(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), any(), any())).thenReturn(List.of(clusters));
    }

    private void wholeRegion(RegionPriceStat... stats) {
        when(listingRepository.aggregatePublicPricesByRegion(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(List.of(stats));
    }

    @Test
    @DisplayName("지도 영역 안에 매물이 없으면 빈 목록 · 전체 집계는 부르지 않는다")
    void nothing_in_view() {
        inView();

        ListingMarketPriceResponse response = service.marketPrices(condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT);

        assertThat(response.minSampleSize()).isEqualTo(5);
        assertThat(response.regions()).isEmpty();
        verify(listingRepository, never()).aggregatePublicPricesByRegion(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("대상 구는 영역 안 집계로 고르고, 숫자는 지도 영역 없이 그 구들만 센 전체 집계로 낸다")
    void picks_regions_in_view_but_counts_whole_region() {
        inView(new RegionCluster(SEODAEMUN, 3, 37.56, 126.93));
        wholeRegion(new RegionPriceStat(SEODAEMUN, 63, 5_000_000.0, 480_000.0, 37.559, 126.939));

        ListingMarketPriceResponse.Region row = service.marketPrices(
                condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT).regions().get(0);

        // 영역 안 집계는 지도 영역을 그대로 쓴다
        verify(listingRepository).aggregatePublicByRegion(any(), eq(ListingCategory.ONE_TWO_ROOM),
                eq(List.of(SellingType.MONTHLY_RENT)), eq(List.of(RoomType.ONE_ROOM)), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), isNull(), isNull(),
                eq(SW_LAT), eq(SW_LNG), eq(NE_LAT), eq(NE_LNG));
        // 전체 집계는 같은 조건이되 지도 영역 없이 · 대상 구로만
        verify(listingRepository).aggregatePublicPricesByRegion(any(), eq(ListingCategory.ONE_TWO_ROOM),
                eq(List.of(SellingType.MONTHLY_RENT)), eq(List.of(RoomType.ONE_ROOM)), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(List.of(SEODAEMUN)));
        assertThat(row.inViewCount()).isEqualTo(3);
        assertThat(row.sampleSize()).isEqualTo(63);
        // 말풍선 자리는 영역 안 평균(37.56)이 아니라 구 전체 평균
        assertThat(row.lat()).isEqualByComparingTo("37.559");
        assertThat(row.lng()).isEqualByComparingTo("126.939");
        assertThat(row.regionCode()).isEqualTo("11410");
        assertThat(row.regionName()).isEqualTo("서대문구");
    }

    @Test
    @DisplayName("표본 4건이면 행은 남고 두 평균이 null, 5건이면 값이 나온다")
    void sample_threshold() {
        inView(new RegionCluster(SEODAEMUN, 2, 37.56, 126.93), new RegionCluster(MAPO, 1, 37.55, 126.92));
        wholeRegion(new RegionPriceStat(SEODAEMUN, 5, 5_000_000.0, 480_000.0, 37.56, 126.93),
                new RegionPriceStat(MAPO, 4, 5_200_000.0, 520_000.0, 37.55, 126.92));

        List<ListingMarketPriceResponse.Region> rows = service.marketPrices(
                condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT).regions();

        assertThat(rows.get(0).regionCode()).isEqualTo("11410");
        assertThat(rows.get(0).avgDeposit()).isEqualTo(5_000_000L);
        assertThat(rows.get(0).avgMonthlyRent()).isEqualTo(480_000L);
        assertThat(rows.get(1).regionCode()).isEqualTo("11440");
        assertThat(rows.get(1).sampleSize()).isEqualTo(4);
        assertThat(rows.get(1).avgDeposit()).isNull();
        assertThat(rows.get(1).avgMonthlyRent()).isNull();
    }

    @Test
    @DisplayName("평균은 원 단위로 반올림한다")
    void rounds_to_won() {
        inView(new RegionCluster(SEODAEMUN, 1, 37.56, 126.93));
        wholeRegion(new RegionPriceStat(SEODAEMUN, 7, 4_999_999.5, 480_000.4, 37.56, 126.93));

        ListingMarketPriceResponse.Region row = service.marketPrices(
                condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT).regions().get(0);

        assertThat(row.avgDeposit()).isEqualTo(5_000_000L);
        assertThat(row.avgMonthlyRent()).isEqualTo(480_000L);
    }

    @Test
    @DisplayName("전세는 표본이 넉넉해도 월세 평균이 없다")
    void lease_has_no_monthly_rent() {
        inView(new RegionCluster(SEODAEMUN, 1, 37.56, 126.93));
        wholeRegion(new RegionPriceStat(SEODAEMUN, 9, 150_000_000.0, 0.0, 37.56, 126.93));

        ListingMarketPriceResponse.Region row = service.marketPrices(
                condition(SellingType.LEASE), SellingType.LEASE).regions().get(0);

        assertThat(row.avgDeposit()).isEqualTo(150_000_000L);
        assertThat(row.avgMonthlyRent()).isNull();
    }

    @Test
    @DisplayName("영역 안 개수가 많은 구부터 — 같으면 지역 id 순")
    void ordered_by_in_view_count() {
        inView(new RegionCluster(MAPO, 2, 37.55, 126.92), new RegionCluster(SEODAEMUN, 7, 37.56, 126.93),
                new RegionCluster(40L, 2, 37.57, 126.94));
        wholeRegion(new RegionPriceStat(MAPO, 10, 1.0, 1.0, 37.55, 126.92),
                new RegionPriceStat(SEODAEMUN, 10, 1.0, 1.0, 37.56, 126.93),
                new RegionPriceStat(40L, 10, 1.0, 1.0, 37.57, 126.94));

        List<ListingMarketPriceResponse.Region> rows = service.marketPrices(
                condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT).regions();

        assertThat(rows).extracting(ListingMarketPriceResponse.Region::inViewCount).containsExactly(7L, 2L, 2L);
        assertThat(rows).extracting(ListingMarketPriceResponse.Region::regionCode)
                .containsExactly("11410", "11440", null);
    }

    @Test
    @DisplayName("구 이름은 요청 언어 규칙 — 한국어가 아니면 영어")
    void region_name_follows_language() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        inView(new RegionCluster(SEODAEMUN, 1, 37.56, 126.93));
        wholeRegion(new RegionPriceStat(SEODAEMUN, 5, 1.0, 1.0, 37.56, 126.93));

        assertThat(service.marketPrices(condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT)
                .regions().get(0).regionName()).isEqualTo("Seodaemun-gu");
    }

    @Test
    @DisplayName("전체 집계에 그 구가 없으면(읽는 사이 내려감) 표본 0 · 평균 · 좌표 null 로 행만 남는다")
    void missing_whole_region_stat() {
        inView(new RegionCluster(SEODAEMUN, 1, 37.56, 126.93));
        wholeRegion();

        ListingMarketPriceResponse.Region row = service.marketPrices(
                condition(SellingType.MONTHLY_RENT), SellingType.MONTHLY_RENT).regions().get(0);

        assertThat(row.sampleSize()).isZero();
        assertThat(row.avgDeposit()).isNull();
        assertThat(row.lat()).isNull();
        assertThat(row.inViewCount()).isEqualTo(1);
    }
}
