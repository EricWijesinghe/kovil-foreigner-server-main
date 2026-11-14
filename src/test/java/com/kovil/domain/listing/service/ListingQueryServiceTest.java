package com.kovil.domain.listing.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.response.ListingDetailResponse;
import com.kovil.domain.listing.dto.response.ListingMapResponse;
import com.kovil.domain.listing.dto.response.ListingSummaryResponse;
import com.kovil.domain.listing.entity.*;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.listing.repository.RegionCluster;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.listing.dto.request.ListingRadius;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 유학생이 보는 매물 조회의 **조립부**.
 *
 * ★★ DTO 테스트(PublicResponseLeakTest)는 DTO 를 직접 부르고, 저장소 테스트(ListingPublicQueryTest)는
 *    쿼리만 본다 — 둘 다 이 서비스를 한 줄도 실행하지 않는다. 그래서 정렬 컬럼을 바꾸거나
 *    조회수 증가를 지우거나 비로그인에게 찜 조회를 걸어도 매물 테스트가 전부 초록이었다.
 *    판별 축은 넷이다 — **정렬 매핑**, **유출(좌표)**, **부수효과(조회수)**, **쿼리 수(묶음·비로그인)**.
 *
 * 지도 CLUSTER 모드는 **밖으로 내보내는 지역 식별자**까지 본다 — 공개 API 는 숫자 id 가 아니라
 * 법정동코드를 쓴다. 지역 필터의 code → id 변환은 ListingRegionResolverTest 가 맡는다.
 */
class ListingQueryServiceTest {

    // ★ 실좌표와 흐린 좌표는 소수 넷째 자리부터 갈린다 — 같은 값이면 바꿔 끼워도 안 보인다.
    private static final BigDecimal REAL_LAT = new BigDecimal("37.4862378");
    private static final BigDecimal REAL_LNG = new BigDecimal("126.9331220");
    private static final BigDecimal DISPLAY_LAT = new BigDecimal("37.4862552");
    private static final BigDecimal DISPLAY_LNG = new BigDecimal("126.9332423");

    // 매물 id · 사무소 id · 조회자 id 를 서로 다른 수로 둔다 — 인자를 바꿔 끼우면 스텁이 빗나간다.
    private static final Long LISTING_1 = 1L;
    private static final Long LISTING_2 = 2L;
    private static final Long OFFICE_ID = 3L;
    private static final Long VIEWER_ID = 10L;
    private static final String VIEWER_UUID = "u-1";

    private UserQueryService userQueryService;
    private ListingRepository listingRepository;
    private BrokerageOfficeRepository officeRepository;
    private ListingMaintenanceRepository maintenanceRepository;
    private ListingMaintenanceItemRepository itemRepository;
    private ListingMaintenanceIncludeRepository includeRepository;
    private ListingOptionRepository optionRepository;
    private ListingSafetyRepository safetyRepository;
    private ListingImageRepository imageRepository;
    private ListingFavoriteRepository favoriteRepository;
    private RegionRepository regionRepository;
    private StoredFileRepository storedFileRepository;
    private ListingQueryService service;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        listingRepository = mock(ListingRepository.class);
        officeRepository = mock(BrokerageOfficeRepository.class);
        maintenanceRepository = mock(ListingMaintenanceRepository.class);
        itemRepository = mock(ListingMaintenanceItemRepository.class);
        includeRepository = mock(ListingMaintenanceIncludeRepository.class);
        optionRepository = mock(ListingOptionRepository.class);
        safetyRepository = mock(ListingSafetyRepository.class);
        imageRepository = mock(ListingImageRepository.class);
        favoriteRepository = mock(ListingFavoriteRepository.class);
        regionRepository = mock(RegionRepository.class);
        storedFileRepository = mock(StoredFileRepository.class);
        service = new ListingQueryService(userQueryService, listingRepository, officeRepository,
                maintenanceRepository, itemRepository, includeRepository, optionRepository,
                safetyRepository, imageRepository, favoriteRepository, regionRepository,
                storedFileRepository);

        lenient().when(userQueryService.getActiveUser(VIEWER_UUID))
                .thenReturn(User.builder().id(VIEWER_ID).uuid(VIEWER_UUID).build());
    }

    @Nested
    @DisplayName("정렬")
    class Sorting {

        /*
         * ★★ 이 테스트가 지키는 것은 **끌어올리기(bump) 금지**다. updatedAt 으로 바뀌면
         *    중개사가 갱신 한 번으로 상단을 차지한다 — 다방 실측에서 5개월 전 매물이
         *    55초 전 갱신으로 맨 위에 있었다. 컴파일도, 저장소 테스트도 이 교체를 못 잡는다.
         */
        @Test
        @DisplayName("최신순은 최초 게시 시각 내림차순이다 — 갱신 시각이 아니다")
        void latest_sorts_by_posted_at_not_updated_at() {
            givenPublicPage(List.of());

            service.search(null, noFilter(), null, null, ListingSort.LATEST, 0, 20);

            assertThat(sortedPageable().getSort().toList()).containsExactly(
                    Sort.Order.desc("postedAt"), Sort.Order.desc("id"));
        }

        // 보증금이 같은 매물은 월세로 다시 가른다 — 빠지면 "싼 순"이 보증금만 보는 반쪽이 된다.
        @Test
        @DisplayName("낮은 가격순은 보증금, 같으면 월세 오름차순이다")
        void price_asc_sorts_by_deposit_then_rent() {
            givenPublicPage(List.of());

            service.search(null, noFilter(), null, null, ListingSort.PRICE_ASC, 0, 20);

            assertThat(sortedPageable().getSort().toList()).containsExactly(
                    Sort.Order.asc("deposit"), Sort.Order.asc("monthlyRent"), Sort.Order.desc("id"));
        }

        @Test
        @DisplayName("높은 가격순은 보증금, 같으면 월세 내림차순이다")
        void price_desc_sorts_by_deposit_then_rent() {
            givenPublicPage(List.of());

            service.search(null, noFilter(), null, null, ListingSort.PRICE_DESC, 0, 20);

            assertThat(sortedPageable().getSort().toList()).containsExactly(
                    Sort.Order.desc("deposit"), Sort.Order.desc("monthlyRent"), Sort.Order.desc("id"));
        }

        /*
         * ★ 마지막 id 내림차순이 동점 깨기다. 빠지면 같은 면적 매물의 순서가 쿼리마다 흔들려
         *   페이지를 넘길 때 같은 매물이 두 번 나오거나 사라진다 — 위 세 테스트도 같은 이유로
         *   containsExactly 로 꼬리까지 본다.
         */
        @Test
        @DisplayName("넓은 순은 전용면적 내림차순이고, 동점은 id 내림차순으로 깬다")
        void area_desc_sorts_by_exclusive_area_with_id_tiebreak() {
            givenPublicPage(List.of());

            service.search(null, noFilter(), null, null, ListingSort.AREA_DESC, 0, 20);

            assertThat(sortedPageable().getSort().toList()).containsExactly(
                    Sort.Order.desc("exclusiveArea"), Sort.Order.desc("id"));
        }

        /*
         * ★ 추천순은 "전입신고 되는 집 먼저"라 CASE 식이고 Sort 로 표현되지 않는다.
         *   컬럼 정렬 쿼리로 새면 우리가 다방과 다른 유일한 정렬 기준이 조용히 사라진다.
         */
        @Test
        @DisplayName("추천순은 전입신고 우선 전용 쿼리를 타고, 페이지는 그대로 넘긴다")
        void recommended_uses_dedicated_query() {
            givenRecommendedPage(List.of());

            service.search(null, noFilter(), null, null, ListingSort.RECOMMENDED, 2, 20);

            Pageable pageable = recommendedPageable();
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(20);
            assertThat(pageable.getSort().isSorted()).isFalse();
            verify(listingRepository, never()).findPublic(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        // 정렬 파라미터가 없을 때의 기본값. 추천순(전입신고 우선)이어야 유학생에게 맞는 집이 먼저 보인다.
        @Test
        @DisplayName("정렬을 지정하지 않으면 추천순이다")
        void missing_sort_defaults_to_recommended() {
            givenRecommendedPage(List.of());

            service.search(null, noFilter(), null, null, null, 0, 20);

            recommendedPageable();
            verify(listingRepository, never()).findPublic(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }
    }

    /*
     * ★ 반경이 오면 반경용 쿼리로 가야 한다 — 일반 쿼리로 새면 원 조건이 통째로 빠져 "○○ 주변" 제목 아래 전체 매물이 나온다.
     * ★ 영역 칸에는 원을 감싼 사각형이 들어간다. 지도 영역이 같이 오면 두 사각형의 겹침(남서는 큰 쪽, 북동은 작은 쪽)이다.
     */
    // 사무소 uuid 는 목록 쿼리 네 갈래(추천 · 정렬 × 일반 · 반경) 모두에 제자리로 가야 한다 — 한 갈래만 빠져도 정렬을 바꾸면 전체 매물이 나온다.
    @Nested
    @DisplayName("목록 — 사무소")
    class Office {

        private final ListingRadius radius =
                ListingRadius.ofNullable(
                        new BigDecimal("37.5600000"), new BigDecimal("126.9400000"), 500);

        @Test
        @DisplayName("추천순 · 정렬 · 반경 추천순 · 반경 정렬 모두 사무소 uuid 를 넘긴다")
        void office_uuid_reaches_every_list_query() {
            givenRecommendedPage(List.of());
            givenPublicPage(List.of());
            given(listingRepository.findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(Page.empty());
            given(listingRepository.findPublicWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(Page.empty());
            ListingSearchCondition ofOffice = new ListingSearchCondition(null, null, null, null, null, null, null, null,
                    null, null, false, false, null, 7L, "o-77", null, null, null, null);

            service.search(null, ofOffice, null, null, null, 0, 20);
            service.search(null, ofOffice, null, null, ListingSort.LATEST, 0, 20);
            service.search(null, ofOffice, radius, null, null, 0, 20);
            service.search(null, ofOffice, radius, null, ListingSort.LATEST, 0, 20);

            verify(listingRepository).findPublicRecommended(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(7L), eq("o-77"),
                    any(), any(), any(), any(), any());
            verify(listingRepository).findPublic(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(7L), eq("o-77"),
                    any(), any(), any(), any(), any());
            verify(listingRepository).findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(7L), eq("o-77"),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(listingRepository).findPublicWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(7L), eq("o-77"),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("목록 — 반경")
    class Radius {

        private final ListingRadius radius =
                ListingRadius.ofNullable(
                        new BigDecimal("37.5600000"), new BigDecimal("126.9400000"), 500);

        private void givenWithinPages() {
            given(listingRepository.findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(Page.empty());
            given(listingRepository.findPublicWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(Page.empty());
        }

        @Test
        @DisplayName("추천순 — 반경용 쿼리에 원을 감싼 사각형과 원 계수를 넘기고, 일반 쿼리는 부르지 않는다")
        void recommended_goes_to_within_query() {
            givenWithinPages();

            service.search(null, noFilter(), radius, null, null, 0, 20);

            verify(listingRepository).findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    eq(radius.minLat()), eq(radius.minLng()), eq(radius.maxLat()), eq(radius.maxLng()),
                    eq(radius.lat()), eq(radius.lng()), eq(radius.lngScaleSq()), eq(radius.radiusDegSq()), any());
            verify(listingRepository, never()).findPublicRecommended(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("다른 정렬 — 반경용 일반 정렬 쿼리로 간다")
        void sorted_goes_to_within_query() {
            givenWithinPages();

            service.search(null, noFilter(), radius, null, ListingSort.LATEST, 0, 20);

            verify(listingRepository).findPublicWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(listingRepository, never()).findPublic(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("지도 영역이 같이 오면 두 사각형의 겹침을 넘긴다")
        void bounding_box_is_intersected() {
            givenWithinPages();
            ListingSearchCondition northHalf = new ListingSearchCondition(null, null, null, null,
                    null, null, null, null, null, null, false, false, null, null, null,
                    new BigDecimal("37.5600000"), new BigDecimal("126.9000000"),
                    new BigDecimal("37.6000000"), new BigDecimal("126.9420000"));

            service.search(null, northHalf, radius, null, null, 0, 20);

            verify(listingRepository).findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    eq(new BigDecimal("37.5600000")), eq(radius.minLng()),
                    eq(radius.maxLat()), eq(new BigDecimal("126.9420000")),
                    any(), any(), any(), any(), any());
        }

        // 지도는 반경을 받지 않는다 — 지도 핀 조회가 반경용 쿼리로 새면 원 밖 마커가 사라진다.
        @Test
        @DisplayName("지도 핀 조회는 반경용 쿼리를 쓰지 않는다")
        void map_never_uses_within_query() {
            givenRecommendedPage(List.of());
            given(listingRepository.countPublic(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                    any(), any(), any(), any())).willReturn(0L);

            service.map(noFilter());

            verify(listingRepository, never()).findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /*
     * ★ 목록 · 지도 쿼리는 자리로 받는 인자가 19~25개이고, 같은 형(보증금 · 월세 · 지역의 Long, 면적 · 사각형 · 원 계수의
     *   BigDecimal, 주차 · 단기의 boolean)끼리는 자리를 바꿔 끼워도 컴파일된다. 칸마다 다른 값을 넣고 쿼리마다 제자리를 본다.
     * ★ 지도는 사무소 uuid 를 받지 않는다 — 지도 조건의 사무소 칸은 비우고, 핀 쿼리의 그 자리는 null 로 본다.
     * ★ 조건에 지도 영역이 없어서 반경 없는 쿼리의 영역 네 칸은 모두 null 이다 — 그 네 칸끼리 바꿔 끼운 것은 여기서 안 보인다.
     */
    @Nested
    @DisplayName("목록 · 지도 — 필터 자리")
    class EveryFilter {

        private final ListingRadius radius =
                ListingRadius.ofNullable(
                        new BigDecimal("37.5600000"), new BigDecimal("126.9400000"), 500);
        private final List<ListingStatus> visible = List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);
        private final ListingSearchCondition every = everyFilter("o-every");
        private final ListingSearchCondition everyOnMap = everyFilter(null);

        @Test
        @DisplayName("모든 필터가 추천순 · 정렬 목록 쿼리 둘 다에 제자리로 간다")
        void every_filter_reaches_both_list_queries() {
            givenRecommendedPage(List.of());
            givenPublicPage(List.of());

            service.search(null, every, null, null, null, 0, 20);
            service.search(null, every, null, null, ListingSort.LATEST, 0, 20);

            verify(listingRepository).findPublicRecommended(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), eq("o-every"),
                    isNull(), isNull(), isNull(), isNull(), any());
            verify(listingRepository).findPublic(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), eq("o-every"),
                    isNull(), isNull(), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("모든 필터와 원이 반경 추천순 · 반경 정렬 쿼리 둘 다에 제자리로 간다")
        void every_filter_reaches_both_within_queries() {
            givenWithinPages();

            service.search(null, every, radius, null, null, 0, 20);
            service.search(null, every, radius, null, ListingSort.LATEST, 0, 20);

            verify(listingRepository).findPublicRecommendedWithin(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), eq("o-every"),
                    eq(radius.minLat()), eq(radius.minLng()), eq(radius.maxLat()), eq(radius.maxLng()),
                    eq(radius.lat()), eq(radius.lng()), eq(radius.lngScaleSq()), eq(radius.radiusDegSq()), any());
            verify(listingRepository).findPublicWithin(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), eq("o-every"),
                    eq(radius.minLat()), eq(radius.minLng()), eq(radius.maxLat()), eq(radius.maxLng()),
                    eq(radius.lat()), eq(radius.lng()), eq(radius.lngScaleSq()), eq(radius.radiusDegSq()), any());
        }

        // 기준을 넘겨야 묶음 집계까지 간다 — 개수와 묶음이 다른 조건으로 세면 지도 숫자와 묶음 합이 어긋난다.
        @Test
        @DisplayName("지도 개수 세기와 묶음 집계 둘 다에 모든 필터가 제자리로 간다")
        void every_filter_reaches_map_count_and_clusters() {
            givenPublicCount(501);
            given(listingRepository.aggregatePublicByRegion(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any())).willReturn(List.of());

            service.map(everyOnMap);

            verify(listingRepository).countPublic(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L),
                    isNull(), isNull(), isNull(), isNull());
            verify(listingRepository).aggregatePublicByRegion(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L),
                    isNull(), isNull(), isNull(), isNull());
        }

        // 기준 이하면 핀 쿼리로 간다 — 핀이 개수와 다른 조건으로 걸리면 "N건" 아래 다른 매물이 찍힌다.
        @Test
        @DisplayName("지도 핀 쿼리에 모든 필터가 제자리로 가고, 사무소 자리는 비어 있다")
        void every_filter_reaches_map_pin_query() {
            givenPublicCount(500);
            givenRecommendedPage(List.of());

            service.map(everyOnMap);

            verify(listingRepository).findPublicRecommended(
                    eq(visible), eq(ListingCategory.OFFICETEL),
                    eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                    eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                    eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), any());
        }

        private void givenWithinPages() {
            given(listingRepository.findPublicRecommendedWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(Page.empty());
            given(listingRepository.findPublicWithin(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(Page.empty());
        }

        private ListingSearchCondition everyFilter(String officeUuid) {
            return new ListingSearchCondition(
                    ListingCategory.OFFICETEL, List.of(SellingType.LEASE), List.of(RoomType.ONE_ROOM),
                    List.of(FloorType.GROUND), 1L, 2L, 3L, 4L, new BigDecimal("5"), new BigDecimal("6"),
                    true, false, AvailabilityAnswer.POSSIBLE, 7L, officeUuid, null, null, null, null);
        }
    }

    @Nested
    @DisplayName("목록")
    class Search {

        // 예약 중인 방도 보여 준다 — 조건이 맞으면 대기하려는 사람이 있다. 초안·만료·차단은 안 된다.
        @Test
        @DisplayName("공개 목록은 게시 중과 예약 중인 매물만 찾는다")
        void searches_only_active_and_reserved() {
            givenRecommendedPage(List.of());

            service.search(null, noFilter(), null, null, null, 0, 20);

            ArgumentCaptor<Collection<ListingStatus>> statuses = ArgumentCaptor.captor();
            verify(listingRepository).findPublicRecommended(statuses.capture(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                    any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(statuses.getValue())
                    .containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.RESERVED);
        }

        /*
         * ★ 지도 묶음을 누르면 앱은 그 묶음의 코드를 **목록** 필터로 보낸다(지도 API 는 지역을 받지 않는다).
         *   목록 조회가 지역 id 를 흘리면 관악구 대신 전국 매물이 나온다.
         */
        @Test
        @DisplayName("지역 필터는 목록 조회에 같은 지역 id 로 걸린다")
        void region_filter_reaches_the_list_query() {
            givenRecommendedPage(List.of());

            service.search(null, withRegion(38L), null, null, null, 0, 20);

            verify(listingRepository).findPublicRecommended(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(38L), any(), any(), any(), any(),
                    any(), any());
        }

        /*
         * ★★ 비로그인 열람은 PO 확정이다. 비로그인에게 찜 조회를 걸면 getActiveUser(null) 이
         *    터져 **목록 자체가 안 열린다** — 둘러보다 가입하는 길이 막힌다.
         */
        @Test
        @DisplayName("비로그인이면 찜 조회를 아예 하지 않고 모두 찜 안 함으로 나간다")
        void anonymous_viewer_skips_favorite_lookup() {
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));

            PageResponse<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20);

            assertThat(out.content()).extracting(ListingSummaryResponse::favorited)
                    .containsExactly(false, false);
            verifyNoInteractions(userQueryService, favoriteRepository);
        }

        // 매물마다 물으면 한 페이지 20건이 쿼리 20개가 된다. 한 번에 집어 매물별로 가른다.
        @Test
        @DisplayName("로그인하면 찜 여부를 한 번에 묶어 묻고 매물별로 가른다")
        void favorites_are_fetched_in_one_batch() {
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));
            given(favoriteRepository.findFavoritedListingIds(eq(VIEWER_ID), any()))
                    .willReturn(List.of(LISTING_2));

            PageResponse<ListingSummaryResponse> out =
                    service.search(VIEWER_UUID, noFilter(), null, null, null, 0, 20);

            assertThat(out.content()).extracting(ListingSummaryResponse::favorited)
                    .containsExactly(false, true);
            verify(favoriteRepository, times(1))
                    .findFavoritedListingIds(VIEWER_ID, List.of(LISTING_1, LISTING_2));
            verify(favoriteRepository, never()).existsByUserIdAndListingId(any(), any());
        }

        /*
         * ★ 썸네일은 **정렬 순서상 첫 사진**이고, 파일 조회는 첫 사진들만 한 번에 한다.
         *   사진 없는 매물은 null — 빈 문자열이나 다른 매물 사진이 끼면 목록이 엉뚱한 집을 보여 준다.
         */
        @Test
        @DisplayName("썸네일은 매물별 첫 사진의 공개 URL 이고, 사진·파일 조회는 각각 한 번이다")
        void thumbnails_are_first_image_fetched_in_one_batch() {
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));
            given(imageRepository.findByListingIdInOrderBySortOrderAsc(List.of(LISTING_1, LISTING_2)))
                    .willReturn(List.of(image(LISTING_1, 21L, 1), image(LISTING_1, 22L, 2)));
            given(storedFileRepository.findByIdInAndDeletedAtIsNull(any()))
                    .willReturn(List.of(file(21L), file(22L)));

            PageResponse<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20);

            assertThat(out.content()).extracting(ListingSummaryResponse::thumbnailUrl)
                    .containsExactly(urlOf(21L), null);
            verify(imageRepository, times(1))
                    .findByListingIdInOrderBySortOrderAsc(List.of(LISTING_1, LISTING_2));
            verify(storedFileRepository, times(1)).findByIdInAndDeletedAtIsNull(Set.of(21L));
            verify(imageRepository, never()).findByListingIdOrderBySortOrderAsc(any());
        }

        /*
         * ★ 확인불가 매물은 합계가 null 이다. 이걸 거르지 않고 toMap 에 넣으면 NPE 로
         *   **목록 전체가 500** 이 된다 — 확인불가 매물 하나가 페이지를 죽인다.
         */
        @Test
        @DisplayName("관리비 합계는 한 번에 묶어 붙이고, 확인불가 매물은 null 로 나간다")
        void maintenance_costs_are_batched_and_unknown_is_null() {
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));
            given(maintenanceRepository.findByListingIdIn(List.of(LISTING_1, LISTING_2)))
                    .willReturn(List.of(maintenance(LISTING_1, 50_000L), maintenance(LISTING_2, null)));

            PageResponse<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20);

            assertThat(out.content()).extracting(ListingSummaryResponse::maintenanceCost)
                    .containsExactly(50_000L, null);
            verify(maintenanceRepository, times(1)).findByListingIdIn(any());
        }

        // ★★ 목록을 훑어 임대인 주소를 전수 수집하는 것을 막는 방벽이다. 직렬화 문자열로 본다 —
        //    필드 하나씩 보면 다른 이름의 필드로 새는 것을 놓친다.
        @Test
        @DisplayName("목록 응답의 좌표는 흐린 값이고 실좌표는 어디에도 없다")
        void summary_carries_display_coordinates_only() throws Exception {
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1")));

            ListingSummaryResponse row = service.search(null, noFilter(), null, null, null, 0, 20).content().get(0);

            assertThat(row.lat()).isEqualByComparingTo(DISPLAY_LAT);
            assertThat(row.lng()).isEqualByComparingTo(DISPLAY_LNG);
            assertThat(JsonMapper.builder().build().writeValueAsString(row))
                    .doesNotContain(REAL_LAT.toPlainString())
                    .doesNotContain(REAL_LNG.toPlainString());
        }

        /*
         * ★ 결과가 비면 뒤따르는 묶음 조회를 하지 않는다. 빈 `IN ()` 은 벤더마다 다르게 깨지고,
         *   로그인한 사람의 빈 검색이 사용자 조회까지 일으킬 이유도 없다.
         */
        @Test
        @DisplayName("결과가 비면 관리비·사진·파일·찜 조회를 하지 않는다")
        void empty_page_skips_batch_lookups() {
            givenRecommendedPage(List.of());

            PageResponse<ListingSummaryResponse> out =
                    service.search(VIEWER_UUID, noFilter(), null, null, null, 0, 20);

            assertThat(out.content()).isEmpty();
            verifyNoInteractions(maintenanceRepository, imageRepository, storedFileRepository,
                    favoriteRepository, userQueryService);
        }
    }

    @Nested
    @DisplayName("지도 — 핀")
    class MapPins {

        // 개수가 적으면 핀이다. 핀 좌표가 실좌표면 지도 한 장으로 임대인 주소가 다 보인다.
        @Test
        @DisplayName("영역 안 매물이 기준 이하면 핀으로 내리고, 핀 좌표는 흐린 값이다")
        void few_listings_are_pins_with_display_coordinates() {
            givenPublicCount(2);
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));

            ListingMapResponse out = service.map(noFilter());

            assertThat(out.mode()).isEqualTo(ListingMapResponse.Mode.PIN);
            assertThat(out.clusters()).isEmpty();
            assertThat(out.pins()).extracting(ListingMapResponse.Pin::listingUuid)
                    .containsExactly("l-1", "l-2");
            ListingMapResponse.Pin pin = out.pins().get(0);
            assertThat(pin.lat()).isEqualByComparingTo(DISPLAY_LAT);
            assertThat(pin.lng()).isEqualByComparingTo(DISPLAY_LNG);
            assertThat(pin.deposit()).isEqualTo(3_000_000L);
            assertThat(pin.monthlyRent()).isEqualTo(300_000L);
            assertThat(pin.sellingType()).isEqualTo("MONTHLY_RENT");
            verify(listingRepository, never()).aggregatePublicByRegion(any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                    any(), any(), any(), any(), any(), any());
        }

        /*
         * ★ 기준값 **정확히 500건은 핀**이다(초과일 때만 묶음). 비교를 `>=` 로 뒤집으면
         *   이 칸 하나만 바뀐다. 그리고 핀 조회는 기준값만큼만 가져온다 — 개수를 세고 나서
         *   그보다 많이 가져오면 "개수로 전환한다"는 방어가 무의미해진다.
         */
        @Test
        @DisplayName("정확히 기준값만큼이면 아직 핀이고, 핀은 기준값까지만 가져온다")
        void exactly_threshold_is_still_pins() {
            givenPublicCount(500);
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1")));

            ListingMapResponse out = service.map(noFilter());

            assertThat(out.mode()).isEqualTo(ListingMapResponse.Mode.PIN);
            Pageable pageable = recommendedPageable();
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("지도 — 묶음")
    class MapClusters {

        /*
         * ★★ 묶음은 **법정동코드**를 싣는다. 앱은 묶음을 누르면 그 값을 그대로 `regionCode` 필터로
         *    되보내는데, 숫자 id 를 실으면 그 요청이 LISTING_REGION_INVALID 로 막힌다.
         *    id(38)와 코드(11620)를 다른 수로 둬서 둘을 바꿔 끼우면 이 테스트가 깨진다.
         */
        @Test
        @DisplayName("기준을 넘으면 시군구 묶음으로 내리고, 묶음은 숫자 id 가 아니라 법정동코드를 싣는다")
        void over_threshold_returns_clusters_keyed_by_region_code() {
            givenPublicCount(501);
            givenClusters(List.of(new RegionCluster(38L, 501L, 37.47, 126.95)));
            given(regionRepository.findByIdIn(List.of(38L))).willReturn(List.of(
                    Region.builder().id(38L).parentId(1L).code("11620").nameKo("관악구").nameEn("Gwanak-gu").build()));

            ListingMapResponse out = inLanguage(Locale.KOREAN, () -> service.map(noFilter()));

            assertThat(out.mode()).isEqualTo(ListingMapResponse.Mode.CLUSTER);
            assertThat(out.pins()).isEmpty();
            assertThat(out.clusters()).hasSize(1);
            ListingMapResponse.Cluster cluster = out.clusters().get(0);
            assertThat(cluster.regionCode()).isEqualTo("11620");
            assertThat(cluster.regionName()).isEqualTo("관악구");
            assertThat(cluster.count()).isEqualTo(501L);
            assertThat(cluster.lat()).isEqualByComparingTo("37.47");
            assertThat(cluster.lng()).isEqualByComparingTo("126.95");
            // 개수로 전환했으면 핀 조회는 아예 돌지 않는다 — 돌면 전환의 의미가 없다.
            verify(listingRepository, never()).findPublicRecommended(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        // 지역 API 와 같은 규칙 — 같은 지역이 지도 마커와 목록 칩에서 두 이름으로 보이면 안 된다.
        @Test
        @DisplayName("묶음의 지역 이름은 요청 언어를 따른다 — 한국어가 아니면 영어")
        void cluster_name_follows_the_request_language() {
            givenPublicCount(501);
            givenClusters(List.of(new RegionCluster(38L, 501L, 37.47, 126.95)));
            given(regionRepository.findByIdIn(List.of(38L))).willReturn(List.of(
                    Region.builder().id(38L).parentId(1L).code("11620").nameKo("관악구").nameEn("Gwanak-gu").build()));

            ListingMapResponse out = inLanguage(Locale.ENGLISH, () -> service.map(noFilter()));

            assertThat(out.clusters().get(0).regionName()).isEqualTo("Gwanak-gu");
            assertThat(out.clusters().get(0).regionCode()).isEqualTo("11620");
        }

        // 지역 행을 못 찾아도 묶음 자체를 버리지 않는다 — 매물 수가 지도에서 사라지면 안 된다.
        @Test
        @DisplayName("지역 행을 못 찾은 묶음은 코드·이름을 비우고 개수는 그대로 낸다")
        void cluster_without_region_row_keeps_its_count() {
            givenPublicCount(600);
            givenClusters(List.of(new RegionCluster(99L, 600L, 37.5, 127.0)));
            given(regionRepository.findByIdIn(List.of(99L))).willReturn(List.of());

            ListingMapResponse out = service.map(noFilter());

            assertThat(out.clusters()).singleElement().satisfies(c -> {
                assertThat(c.regionCode()).isNull();
                assertThat(c.regionName()).isNull();
                assertThat(c.count()).isEqualTo(600L);
            });
        }

        // 지역 필터는 개수 세기와 묶음 집계 **둘 다**에 걸려야 한다 — 한쪽만 걸면 개수와 묶음이 어긋난다.
        @Test
        @DisplayName("지역 필터는 개수와 묶음 집계 둘 다에 같은 지역 id 로 걸린다")
        void region_filter_reaches_count_and_aggregate() {
            givenPublicCount(501);
            givenClusters(List.of());

            service.map(withRegion(38L));

            verify(listingRepository).countPublic(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(38L), any(), any(), any(),
                    any());
            verify(listingRepository).aggregatePublicByRegion(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), eq(38L), any(),
                    any(), any(), any());
        }

        private <R> R inLanguage(Locale locale, Supplier<R> call) {
            Locale before = LocaleContextHolder.getLocale();
            LocaleContextHolder.setLocale(locale);
            try {
                return call.get();
            } finally {
                LocaleContextHolder.setLocale(before);
            }
        }

        private void givenClusters(List<RegionCluster> clusters) {
            given(listingRepository.aggregatePublicByRegion(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                    any(), any(), any())).willReturn(clusters);
        }
    }

    /*
     * 목록 카드 보강 — 예약 중 배지 · "N일 전" · 구 이름 · 관리비 분기 · "+ α" · 보증보험.
     * ★ 공개 목록과 찜 목록이 같은 카드 조립(cardsOf)을 쓴다. 여기서는 공개 목록으로 태운다.
     */
    @Nested
    @DisplayName("카드 보강")
    class Card {

        private static final Long REGION_ID = 38L;

        @Test
        @DisplayName("카드에 공개 상태와 최초 게시 시각이 실린다 — 예약 중을 카드에서 가린다")
        void carries_status_and_first_posted_at() {
            LocalDateTime posted = LocalDateTime.of(2026, 10, 6, 10, 12);
            givenRecommendedPage(List.of(
                    card(LISTING_1, ListingStatus.RESERVED, null).postedAt(posted).build(),
                    card(LISTING_2, ListingStatus.ACTIVE, null).build()));

            List<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20).content();

            assertThat(out).extracting(ListingSummaryResponse::status)
                    .containsExactly(ListingStatus.RESERVED, ListingStatus.ACTIVE);
            assertThat(out.get(0).postedAt()).isEqualTo(posted);
        }

        /*
         * ★★ 금액이 null 인 이유가 둘이다 — 확인불가(UNABLE_CHECK)와 금액 없는 기타부과(ETC_CHARGE).
         *    화면 문구가 다르다("관리비 확인불가" vs "관리비 별도 부과"). 분기 칸이 없으면 둘을 가를 수 없다.
         */
        @Test
        @DisplayName("관리비 분기로 확인불가와 금액 없는 기타부과가 갈리고, 관리비 행이 없으면 둘 다 null 이다")
        void charge_type_separates_unable_check_from_amountless_etc_charge() {
            givenRecommendedPage(List.of(listing(1L, "l-1"), listing(2L, "l-2"), listing(3L, "l-3"), listing(4L, "l-4")));
            given(maintenanceRepository.findByListingIdIn(any())).willReturn(List.of(
                    charge(1L, MaintenanceChargeType.FIXED_FEE, 70_000L),
                    charge(2L, MaintenanceChargeType.UNABLE_CHECK, null),
                    charge(3L, MaintenanceChargeType.ETC_CHARGE, null)));

            List<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20).content();

            assertThat(out).extracting(ListingSummaryResponse::maintenanceCost)
                    .containsExactly(70_000L, null, null, null);
            assertThat(out).extracting(ListingSummaryResponse::maintenanceChargeType)
                    .containsExactly(MaintenanceChargeType.FIXED_FEE, MaintenanceChargeType.UNABLE_CHECK,
                            MaintenanceChargeType.ETC_CHARGE, null);
        }

        @Test
        @DisplayName("실비 비목이 있는 매물만 maintenanceActualCost=true 다 — 페이지당 한 번 묻는다")
        void actual_cost_flag_follows_items() {
            givenRecommendedPage(List.of(listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));
            given(itemRepository.findListingIdsWithActualCost(any())).willReturn(List.of(LISTING_1));

            List<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20).content();

            assertThat(out).extracting(ListingSummaryResponse::maintenanceActualCost).containsExactly(true, false);
            verify(itemRepository, times(1)).findListingIdsWithActualCost(List.of(LISTING_1, LISTING_2));
        }

        /*
         * 지역 API 와 같은 규칙 — 묶음을 눌러 목록으로 가면 지역 칩이 지역 API 의 이름으로 보인다.
         * ★ 제3언어(베트남어)를 꼭 넣는다. 한국어 · 영어만 보면 "영어면 영어, 나머지는 한국어"로 뒤집혀도
         *   초록이고, 그러면 베트남 · 중국 학생에게 읽을 수 없는 한국어 구 이름이 나간다.
         */
        @Test
        @DisplayName("구 코드와 이름이 실리고, 이름은 한국어 요청이면 한국어 · 그 밖의 언어는 모두 영어다")
        void region_code_and_name_follow_language() {
            givenRecommendedPage(List.of(card(LISTING_1, ListingStatus.ACTIVE, REGION_ID).build()));
            given(regionRepository.findByIdIn(any())).willReturn(List.of(gwanak()));

            ListingSummaryResponse ko = inLanguage(Locale.KOREAN,
                    () -> service.search(null, noFilter(), null, null, null, 0, 20).content().get(0));
            ListingSummaryResponse en = inLanguage(Locale.ENGLISH,
                    () -> service.search(null, noFilter(), null, null, null, 0, 20).content().get(0));
            ListingSummaryResponse vi = inLanguage(Locale.forLanguageTag("vi"),
                    () -> service.search(null, noFilter(), null, null, null, 0, 20).content().get(0));

            assertThat(ko.regionCode()).isEqualTo("11620");
            assertThat(ko.regionName()).isEqualTo("관악구");
            assertThat(en.regionName()).isEqualTo("Gwanak-gu");
            assertThat(vi.regionName()).isEqualTo("Gwanak-gu");
        }

        /*
         * ★ 지역 규칙 이전에 게시된 매물은 regionId 가 없다. 그런 매물만 있는 페이지에서
         *   빈 맵(Map.of())에 null 로 물으면 NPE 로 목록 전체가 500 이 된다.
         */
        @Test
        @DisplayName("지역 없는 옛 매물만 있으면 지역을 묻지 않고, 구 코드 · 이름은 null 이다")
        void listings_without_region_skip_region_lookup() {
            givenRecommendedPage(List.of(card(LISTING_1, ListingStatus.ACTIVE, null).build()));

            ListingSummaryResponse out = service.search(null, noFilter(), null, null, null, 0, 20).content().get(0);

            assertThat(out.regionCode()).isNull();
            assertThat(out.regionName()).isNull();
            verify(regionRepository, never()).findByIdIn(any());
        }

        @Test
        @DisplayName("지역 있는 매물과 없는 매물이 섞여도 각자 맞게 실린다")
        void mixed_region_and_no_region() {
            givenRecommendedPage(List.of(
                    card(LISTING_1, ListingStatus.ACTIVE, REGION_ID).build(),
                    card(LISTING_2, ListingStatus.ACTIVE, null).build()));
            given(regionRepository.findByIdIn(any())).willReturn(List.of(gwanak()));

            List<ListingSummaryResponse> out = service.search(null, noFilter(), null, null, null, 0, 20).content();

            assertThat(out).extracting(ListingSummaryResponse::regionCode).containsExactly("11620", null);
        }

        @Test
        @DisplayName("보증보험 가능 여부가 카드에 실린다")
        void carries_deposit_insurance() {
            givenRecommendedPage(List.of(card(LISTING_1, ListingStatus.ACTIVE, null)
                    .depositInsurance(AvailabilityAnswer.POSSIBLE).build()));

            assertThat(service.search(null, noFilter(), null, null, null, 0, 20).content().get(0).depositInsurance())
                    .isEqualTo(AvailabilityAnswer.POSSIBLE);
        }

        // 카드 20장이면 매물마다 묻는 순간 쿼리 60개다 — 관리비 · 실비 · 지역은 페이지당 한 번씩이어야 한다.
        @Test
        @DisplayName("관리비 · 실비 · 지역은 매물 수와 관계없이 페이지당 한 번씩만 묻는다")
        void card_lookups_are_batched_once_per_page() {
            givenRecommendedPage(List.of(
                    card(1L, ListingStatus.ACTIVE, REGION_ID).build(),
                    card(2L, ListingStatus.ACTIVE, REGION_ID).build(),
                    card(3L, ListingStatus.RESERVED, REGION_ID).build()));
            given(regionRepository.findByIdIn(any())).willReturn(List.of(gwanak()));

            service.search(null, noFilter(), null, null, null, 0, 20);

            verify(maintenanceRepository, times(1)).findByListingIdIn(any());
            verify(itemRepository, times(1)).findListingIdsWithActualCost(any());
            verify(regionRepository, times(1)).findByIdIn(any());
        }

        @Test
        @DisplayName("빈 페이지면 관리비 · 실비 · 지역을 묻지 않는다")
        void empty_page_skips_card_lookups() {
            givenRecommendedPage(List.of());

            service.search(null, noFilter(), null, null, null, 0, 20);

            verifyNoInteractions(maintenanceRepository, itemRepository, regionRepository);
        }

        private Listing.ListingBuilder<?, ?> card(Long id, ListingStatus status, Long regionId) {
            return Listing.builder()
                    .id(id).uuid("c-" + id).listingNo("LS-261012-CARD0" + id)
                    .officeId(OFFICE_ID).createdByUserId(20L)
                    .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                    .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                    .deposit(3_000_000L).monthlyRent(300_000L)
                    .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                    .realLat(REAL_LAT).realLng(REAL_LNG)
                    .displayLat(DISPLAY_LAT).displayLng(DISPLAY_LNG)
                    .dongName("신림동").addressJibun("서울특별시 관악구 신림동")
                    .regionId(regionId)
                    .postedAt(LocalDateTime.of(2026, 10, 5, 9, 0))
                    .status(status);
        }

        private ListingMaintenance charge(Long listingId, MaintenanceChargeType type, Long totalCost) {
            return ListingMaintenance.builder().listingId(listingId).chargeType(type).totalCost(totalCost).build();
        }

        private Region gwanak() {
            return Region.builder().id(REGION_ID).parentId(1L).code("11620").nameKo("관악구").nameEn("Gwanak-gu").build();
        }

        private <R> R inLanguage(Locale locale, Supplier<R> call) {
            Locale before = LocaleContextHolder.getLocale();
            LocaleContextHolder.setLocale(locale);
            try {
                return call.get();
            } finally {
                LocaleContextHolder.setLocale(before);
            }
        }
    }

    /*
     * 묶음 요약 — 핀 미리보기 · 최근 본 방 새로고침. ★ 조회수를 올리지 않는다(상세와의 차이).
     * ★ 개수가 범위 밖이면 깎지 않고 400 — 일부만 돌려주면 앱이 나머지를 "없는 매물"로 오인한다.
     */
    @Nested
    @DisplayName("묶음 요약")
    class Summaries {

        @Test
        @DisplayName("요청 순서대로 공개 매물만 싣고, 공개가 아니거나 없는 uuid 는 missing 으로 보낸다")
        void keeps_request_order_and_reports_missing() {
            given(listingRepository.findPublicByUuidIn(any(), any())).willReturn(List.of(
                    listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));

            var out = service.summaries(null, List.of("l-2", "gone", "l-1"));

            assertThat(out.items()).extracting(ListingSummaryResponse::listingUuid).containsExactly("l-2", "l-1");
            assertThat(out.missing()).containsExactly("gone");
        }

        @Test
        @DisplayName("같은 uuid 를 여러 번 보내도 한 번만 묻고 한 번만 싣는다")
        void duplicates_are_collapsed() {
            given(listingRepository.findPublicByUuidIn(any(), any())).willReturn(List.of(listing(LISTING_1, "l-1")));

            var out = service.summaries(null, List.of("l-1", "l-1", "x", "x"));

            assertThat(out.items()).hasSize(1);
            assertThat(out.missing()).containsExactly("x");
            verify(listingRepository).findPublicByUuidIn(eq(List.of("l-1", "x")), any());
        }

        // ★ 이 엔드포인트가 있는 이유다 — 미리보기를 상세로 채우면 핀을 누를 때마다 조회수가 오른다.
        @Test
        @DisplayName("조회수 · 문의 수를 올리지 않는다")
        void never_counts_a_view() {
            given(listingRepository.findPublicByUuidIn(any(), any())).willReturn(List.of(listing(LISTING_1, "l-1")));

            service.summaries(VIEWER_UUID, List.of("l-1"));

            verify(listingRepository, never()).increaseViewCount(any());
            verify(listingRepository, never()).increaseInquiryCount(any());
        }

        @Test
        @DisplayName("공개 판정은 공개 상세와 같은 상태(게시 중 · 예약 중)로 묻는다")
        void public_judgement_uses_visible_statuses() {
            given(listingRepository.findPublicByUuidIn(any(), any())).willReturn(List.of());

            service.summaries(null, List.of("l-1"));

            verify(listingRepository).findPublicByUuidIn(any(),
                    eq(List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED)));
        }

        @Test
        @DisplayName("로그인이면 찜한 매물의 favorited 가 켜진다")
        void favorited_for_logged_in_viewer() {
            given(listingRepository.findPublicByUuidIn(any(), any())).willReturn(List.of(
                    listing(LISTING_1, "l-1"), listing(LISTING_2, "l-2")));
            given(favoriteRepository.findFavoritedListingIds(eq(VIEWER_ID), any())).willReturn(List.of(LISTING_2));

            var out = service.summaries(VIEWER_UUID, List.of("l-1", "l-2"));

            assertThat(out.items()).extracting(ListingSummaryResponse::favorited).containsExactly(false, true);
        }

        @Test
        @DisplayName("0개 · 51개는 400 INVALID_REQUEST 이고 저장소에 묻지도 않는다 — 50개는 된다")
        void size_must_be_one_to_fifty() {
            assertThatThrownBy(() -> service.summaries(null, List.of()))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
            assertThatThrownBy(() -> service.summaries(null, null)).isInstanceOf(ListingException.class);
            assertThatThrownBy(() -> service.summaries(null, uuids(51))).isInstanceOf(ListingException.class);
            verify(listingRepository, never()).findPublicByUuidIn(any(), any());

            given(listingRepository.findPublicByUuidIn(any(), any())).willReturn(List.of());
            assertThat(service.summaries(null, uuids(50)).missing()).hasSize(50);
        }

        private List<String> uuids(int n) {
            return IntStream.range(0, n).mapToObj(i -> "u-" + i).toList();
        }
    }

    /*
     * 핀의 구 코드 — 넓은 줌에서 앱이 핀을 구 · 시도 알약으로 묶는 축이다. 이름은 싣지 않는다(앱이 지역 트리로 붙인다).
     */
    @Nested
    @DisplayName("지도 — 핀의 구 코드")
    class PinRegion {

        @Test
        @DisplayName("핀에 구 코드가 실리고, 지역 없는 옛 매물은 null 이며, 지역 조회는 요청당 한 번이다")
        void pins_carry_region_code() {
            givenPublicCount(3);
            givenRecommendedPage(List.of(
                    pinListing(1L, 38L), pinListing(2L, 38L), pinListing(3L, null)));
            given(regionRepository.findByIdIn(any())).willReturn(List.of(
                    Region.builder().id(38L).parentId(1L).code("11620").nameKo("관악구").nameEn("Gwanak-gu").build()));

            ListingMapResponse out = service.map(noFilter());

            assertThat(out.pins()).extracting(ListingMapResponse.Pin::regionCode)
                    .containsExactly("11620", "11620", null);
            verify(regionRepository, times(1)).findByIdIn(any());
        }

        @Test
        @DisplayName("지역 없는 옛 매물만 있으면 지역을 묻지 않는다")
        void no_region_lookup_when_none() {
            givenPublicCount(1);
            givenRecommendedPage(List.of(pinListing(1L, null)));

            assertThat(service.map(noFilter()).pins().get(0).regionCode()).isNull();
            verify(regionRepository, never()).findByIdIn(any());
        }

        private Listing pinListing(Long id, Long regionId) {
            return Listing.builder()
                    .id(id).uuid("pin-" + id).listingNo("LS-261012-PIN0" + id)
                    .officeId(OFFICE_ID).createdByUserId(20L)
                    .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                    .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                    .deposit(3_000_000L).monthlyRent(300_000L)
                    .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                    .realLat(REAL_LAT).realLng(REAL_LNG)
                    .displayLat(DISPLAY_LAT).displayLng(DISPLAY_LNG)
                    .regionId(regionId)
                    .postedAt(LocalDateTime.of(2026, 10, 5, 9, 0))
                    .status(ListingStatus.ACTIVE).build();
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        /*
         * ★ 내려간 매물의 상세가 열리면 목록에서 뺀 의미가 없다(URL 직접 접근).
         *   거절할 때 조회수가 오르면 없는 매물의 통계가 쌓인다 — 거절 전에 쓰지 않는지 같이 본다.
         */
        @Test
        @DisplayName("공개 중인 매물이 없으면 LISTING_NOT_FOUND 이고 조회수는 오르지 않는다")
        void missing_public_listing_is_not_found() {
            given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(null, "l-1"))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_NOT_FOUND"));
            verify(listingRepository, never()).increaseViewCount(any());
        }

        // 사무소 행이 없으면 법정 표시 항목(명칭·등록번호)을 채울 수 없다 — 반쪽 상세를 내보내지 않는다.
        @Test
        @DisplayName("사무소를 찾지 못하면 LISTING_NOT_FOUND 이고 조회수는 오르지 않는다")
        void missing_office_is_not_found() {
            given(listingRepository.findPublicByUuid(eq("l-1"), any()))
                    .willReturn(Optional.of(listing(LISTING_1, "l-1")));
            given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(null, "l-1"))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_NOT_FOUND"));
            verify(listingRepository, never()).increaseViewCount(any());
        }

        @Test
        @DisplayName("상세는 게시 중과 예약 중인 매물만 찾는다")
        void detail_looks_up_only_active_and_reserved() {
            givenDetail(listing(LISTING_1, "l-1"));

            service.getDetail(null, "l-1");

            ArgumentCaptor<Collection<ListingStatus>> statuses = ArgumentCaptor.captor();
            verify(listingRepository).findPublicByUuid(eq("l-1"), statuses.capture());
            assertThat(statuses.getValue())
                    .containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.RESERVED);
        }

        // 조회수는 상세 응답에 그대로 나가는 숫자다. 증가가 빠지면 0 에서 멈춘 채로 아무도 모른다.
        @Test
        @DisplayName("상세를 열면 그 매물의 조회수가 한 번 오른다")
        void detail_increases_view_count_once() {
            givenDetail(listing(LISTING_1, "l-1"));

            service.getDetail(null, "l-1");

            verify(listingRepository, times(1)).increaseViewCount(LISTING_1);
        }

        // 사무소는 매물의 officeId 로 찾는다 — 매물 id 로 찾으면 남의 사무소 등록번호가 법정 표시로 나간다.
        @Test
        @DisplayName("사무소 표시는 매물이 속한 사무소의 것이다")
        void office_view_comes_from_listing_office() {
            givenDetail(listing(LISTING_1, "l-1"));

            ListingDetailResponse out = service.getDetail(null, "l-1");

            assertThat(out.office().regNo()).isEqualTo("99999-2026-00003");
            assertThat(out.office().name()).isEqualTo("테스트공인중개사사무소");
        }

        /*
         * ★★ 상세도 목록과 같은 방벽이다. 직렬화 문자열로 실좌표의 부재를 본다 —
         *    좌표 칸이 아닌 다른 필드로 새는 것까지 잡는다.
         */
        @Test
        @DisplayName("상세 응답의 좌표는 흐린 값이고 실좌표는 어디에도 없다")
        void detail_carries_display_coordinates_only() throws Exception {
            givenDetail(listing(LISTING_1, "l-1"));

            ListingDetailResponse out = service.getDetail(null, "l-1");

            assertThat(out.lat()).isEqualByComparingTo(DISPLAY_LAT);
            assertThat(out.lng()).isEqualByComparingTo(DISPLAY_LNG);
            assertThat(JsonMapper.builder().build().writeValueAsString(out))
                    .doesNotContain(REAL_LAT.toPlainString())
                    .doesNotContain(REAL_LNG.toPlainString());
        }

        /*
         * ★ 사진은 **중개사가 정한 순서**대로 나간다. 파일 저장소는 id 순 등 제 순서로 돌려주므로
         *   그 순서를 그대로 쓰면 대표 사진이 바뀐다. 지워진 파일은 null 칸으로 남지 않고 빠진다 —
         *   지운 사진의 자리를 응답이 남길 이유가 없다.
         */
        @Test
        @DisplayName("사진은 정렬 순서대로 공개 URL 로 나가고, 지워진 파일은 빠진다")
        void images_are_public_urls_in_sort_order_without_deleted() {
            givenDetail(listing(LISTING_1, "l-1"));
            given(imageRepository.findByListingIdOrderBySortOrderAsc(LISTING_1)).willReturn(List.of(
                    image(LISTING_1, 22L, 1), image(LISTING_1, 21L, 2), image(LISTING_1, 23L, 3)));
            given(storedFileRepository.findByIdInAndDeletedAtIsNull(any()))
                    .willReturn(List.of(file(21L), file(22L)));

            ListingDetailResponse out = service.getDetail(null, "l-1");

            assertThat(out.imageUrls()).containsExactly(urlOf(22L), urlOf(21L));
        }

        // 사진이 없는 매물에 빈 IN 조회를 날리지 않는다.
        @Test
        @DisplayName("사진이 없으면 빈 목록이고 파일 조회를 하지 않는다")
        void no_images_skips_file_lookup() {
            givenDetail(listing(LISTING_1, "l-1"));

            ListingDetailResponse out = service.getDetail(null, "l-1");

            assertThat(out.imageUrls()).isEmpty();
            verifyNoInteractions(storedFileRepository);
        }

        // ★★ 비로그인 열람(PO 확정). 찜 조회를 걸면 getActiveUser(null) 에서 상세가 막힌다.
        @Test
        @DisplayName("비로그인이면 찜 안 함이고 찜·사용자 조회를 아예 하지 않는다")
        void anonymous_detail_skips_favorite_lookup() {
            givenDetail(listing(LISTING_1, "l-1"));

            ListingDetailResponse out = service.getDetail(null, "l-1");

            assertThat(out.favorited()).isFalse();
            verifyNoInteractions(userQueryService, favoriteRepository);
        }

        // 조회자 id 와 매물 id 가 다른 수라 인자를 바꿔 끼우면 스텁이 빗나가 false 가 된다.
        @Test
        @DisplayName("로그인한 조회자가 찜한 매물이면 찜함으로 나간다")
        void logged_in_detail_reflects_favorite() {
            givenDetail(listing(LISTING_1, "l-1"));
            given(favoriteRepository.existsByUserIdAndListingId(VIEWER_ID, LISTING_1)).willReturn(true);

            ListingDetailResponse out = service.getDetail(VIEWER_UUID, "l-1");

            assertThat(out.favorited()).isTrue();
        }

        // 관리비를 아직 안 적은 매물이 있다 — 없을 때 NPE 로 상세 전체가 죽으면 안 된다.
        @Test
        @DisplayName("관리비 정보가 없는 매물은 관리비 칸이 비어서 나간다")
        void missing_maintenance_is_null() {
            givenDetail(listing(LISTING_1, "l-1"));

            ListingDetailResponse out = service.getDetail(null, "l-1");

            assertThat(out.maintenance()).isNull();
        }

        @Test
        @DisplayName("관리비 상세는 합계·비목·포함 항목을 함께 담는다")
        void maintenance_view_carries_items_and_includes() {
            givenDetail(listing(LISTING_1, "l-1"));
            given(maintenanceRepository.findById(LISTING_1))
                    .willReturn(Optional.of(maintenance(LISTING_1, 70_000L)));
            given(itemRepository.findByListingId(LISTING_1)).willReturn(List.of(
                    ListingMaintenanceItem.builder().listingId(LISTING_1)
                            .itemCode(MaintenanceItemCode.WATER).cost(20_000L).build(),
                    ListingMaintenanceItem.builder().listingId(LISTING_1)
                            .itemCode(MaintenanceItemCode.ELECTRIC).actualCost(true).build()));
            given(includeRepository.findByListingId(LISTING_1)).willReturn(List.of(
                    ListingMaintenanceInclude.builder().listingId(LISTING_1)
                            .itemCode(MaintenanceItemCode.INTERNET).build()));

            ListingDetailResponse.MaintenanceView m = service.getDetail(null, "l-1").maintenance();

            assertThat(m.chargeType()).isEqualTo(MaintenanceChargeType.FIXED_FEE);
            assertThat(m.totalCost()).isEqualTo(70_000L);
            assertThat(m.items()).extracting(ListingDetailResponse.ItemView::itemCode,
                            ListingDetailResponse.ItemView::actualCost,
                            ListingDetailResponse.ItemView::cost)
                    .containsExactly(
                            tuple(MaintenanceItemCode.WATER, false, 20_000L),
                            tuple(MaintenanceItemCode.ELECTRIC, true, null));
            assertThat(m.includes()).containsExactly(MaintenanceItemCode.INTERNET);
        }
    }

    // ── 저장소 스텁

    /*
     * 필터 인자는 이 테스트의 관심사가 아니라 전부 any() 다(원시 boolean 자리만 anyBoolean).
     * 지역 인자도 any() 라 그 자리의 타입이 바뀌어도 여기는 그대로 맞는다.
     */
    private void givenPublicPage(List<Listing> listings) {
        given(listingRepository.findPublic(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(), any(),
                any(), any())).willReturn(new PageImpl<>(listings));
    }

    private void givenRecommendedPage(List<Listing> listings) {
        given(listingRepository.findPublicRecommended(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(),
                any(), any(), any())).willReturn(new PageImpl<>(listings));
    }

    private void givenPublicCount(long total) {
        given(listingRepository.countPublic(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(),
                any())).willReturn(total);
    }

    private Pageable sortedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listingRepository).findPublic(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(), any(),
                any(), pageable.capture());
        verify(listingRepository, never()).findPublicRecommended(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any());
        return pageable.getValue();
    }

    private Pageable recommendedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listingRepository).findPublicRecommended(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(),
                any(), any(), any(), pageable.capture());
        return pageable.getValue();
    }

    private void givenDetail(Listing listing) {
        given(listingRepository.findPublicByUuid(eq(listing.getUuid()), any()))
                .willReturn(Optional.of(listing));
        given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.of(office()));
    }

    // ── 픽스처

    // 지역은 비워 둔다 — 지역 필터는 이 클래스가 고정하는 축이 아니다.
    private ListingSearchCondition noFilter() {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null, null);
    }

    private ListingSearchCondition withRegion(Long regionId) {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, regionId, null, null, null, null, null);
    }

    private BrokerageOffice office() {
        return BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo("99999-2026-00003")
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build();
    }

    private Listing listing(Long id, String uuid) {
        return Listing.builder()
                .id(id).uuid(uuid).listingNo("LS-261005-ABC12" + id)
                .officeId(OFFICE_ID).createdByUserId(20L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .realLat(REAL_LAT).realLng(REAL_LNG)
                .displayLat(DISPLAY_LAT).displayLng(DISPLAY_LNG)
                .dongName("신림동").addressJibun("서울특별시 관악구 신림동")
                .postedAt(LocalDateTime.of(2026, 10, 5, 9, 0))
                .status(ListingStatus.ACTIVE).build();
    }

    private ListingImage image(Long listingId, Long fileId, int sortOrder) {
        return ListingImage.builder().listingId(listingId).fileId(fileId).sortOrder(sortOrder).build();
    }

    private StoredFile file(Long id) {
        return StoredFile.builder()
                .id(id).uuid("f-" + id).purpose(FilePurpose.LISTING_IMAGE).uploaderId(20L)
                .storageKey("listing-images/f-" + id).contentType("image/jpeg").byteSize(1024L)
                .fileUrl(urlOf(id))
                .build();
    }

    private String urlOf(Long fileId) {
        return "https://file.example.test/f-" + fileId + ".jpg";
    }

    private ListingMaintenance maintenance(Long listingId, Long totalCost) {
        return ListingMaintenance.builder().listingId(listingId)
                .chargeType(totalCost == null ? MaintenanceChargeType.UNABLE_CHECK
                        : MaintenanceChargeType.FIXED_FEE)
                .totalCost(totalCost).build();
    }
}
