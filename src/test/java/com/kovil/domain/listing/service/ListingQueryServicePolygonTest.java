package com.kovil.domain.listing.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.request.ListingPolygon;
import com.kovil.domain.listing.dto.request.ListingRadius;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.response.ListingSummaryResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingImage;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 그린 구역 목록의 **조립부**. 쿼리는 ListingPolygonApiTest 가, 판정 기하는 ListingPolygonTest 가 본다.
 * 여기서는 순서 · 개수 · 상한 · 쿼리 고르기를 본다.
 *
 * ★ findAllById 는 IN 목록 순서를 지키지 않는다 — 스텁이 **거꾸로** 돌려줘도 응답은 정렬 순서여야 한다.
 * ★ 상한은 "5,000 까지 받고 5,001 부터 거절"이다. 한 건 차이를 둘 다 박아야 경계를 옮기는 실수가 보인다.
 * 구역은 위도 37.55 ~ 37.57 · 경도 126.93 ~ 126.95 정사각형이고, 안 = 37.56 줄 · 밖 = 37.58 줄이다.
 */
class ListingQueryServicePolygonTest {

    private static final ListingPolygon SQUARE = ListingPolygon.parseOrNull(
            "37.55:126.93,37.55:126.95,37.57:126.95,37.57:126.93");
    private static final BigDecimal INSIDE_LAT = new BigDecimal("37.5600000");
    private static final BigDecimal OUTSIDE_LAT = new BigDecimal("37.5800000");

    private ListingRepository listingRepository;
    private UserQueryService userQueryService;
    private ListingFavoriteRepository favoriteRepository;
    private ListingImageRepository imageRepository;
    private StoredFileRepository storedFileRepository;
    private ListingQueryService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        userQueryService = mock(UserQueryService.class);
        favoriteRepository = mock(ListingFavoriteRepository.class);
        imageRepository = mock(ListingImageRepository.class);
        storedFileRepository = mock(StoredFileRepository.class);
        service = new ListingQueryService(userQueryService, listingRepository,
                mock(BrokerageOfficeRepository.class), mock(ListingMaintenanceRepository.class),
                mock(ListingMaintenanceItemRepository.class), mock(ListingMaintenanceIncludeRepository.class),
                mock(ListingOptionRepository.class), mock(ListingSafetyRepository.class),
                imageRepository, favoriteRepository, mock(RegionRepository.class), storedFileRepository);
        // 받은 id 를 거꾸로 돌려준다 — 서비스가 순서를 되살리지 않으면 응답 순서가 뒤집힌다.
        lenient().when(listingRepository.findAllById(any())).thenAnswer(inv -> {
            List<Listing> found = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                found.add(listing((Long) id));
            }
            Collections.reverse(found);
            return found;
        });
    }

    @Test
    @DisplayName("판정 뒤에 자른다 — 안 7건 중 2쪽(3건씩)은 4 ~ 6번째, totalElements 7 · 3쪽")
    void pages_after_judging() {
        givenRecommendedPoints(List.of(in(11), out(12), in(13), in(14), out(15), in(16), in(17), in(18), in(19)));

        PageResponse<ListingSummaryResponse> page = service.search(null, noFilter(), null, SQUARE, null, 1, 3);

        assertThat(page.content()).extracting(ListingSummaryResponse::listingUuid)
                .containsExactly("l-16", "l-17", "l-18");
        assertThat(page.totalElements()).isEqualTo(7);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.currentPage()).isEqualTo(1);
        assertThat(page.hasNext()).isTrue();
        verify(listingRepository).findAllById(List.of(16L, 17L, 18L));
    }

    @Test
    @DisplayName("끝을 넘긴 쪽은 빈 목록 · 개수는 그대로, 매물을 읽지 않는다")
    void page_past_the_end() {
        // 밖 후보를 섞는다 — 자를 위치를 후보 수로 재면 안 · 밖 수가 갈릴 때 틀린다.
        givenRecommendedPoints(List.of(in(1), out(2), in(3)));

        PageResponse<ListingSummaryResponse> page = service.search(null, noFilter(), null, SQUARE, null, 5, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(2);
        verify(listingRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("후보 5,001건은 400 LISTING_POLYGON_TOO_LARGE — 일부만 판정해 틀린 개수를 내지 않는다")
    void too_many_candidates() {
        givenRecommendedPoints(points(5_001));

        assertThatThrownBy(() -> service.search(null, noFilter(), null, SQUARE, null, 0, 20))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> {
                    assertThat(((ListingException) e).getErrorCode().getCode()).isEqualTo("LISTING_POLYGON_TOO_LARGE");
                    assertThat(((ListingException) e).getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(listingRepository, never()).findAllById(any());
    }

    // 상한은 DB 가 넘긴 후보 수에 건다 — 안 개수에 걸면 잘린 5,001건에 밖이 섞였을 때 틀린 개수가 조용히 나간다.
    @Test
    @DisplayName("후보 5,001건 중 밖이 섞여 안이 5,000건이어도 400 LISTING_POLYGON_TOO_LARGE")
    void too_many_candidates_even_if_some_are_outside() {
        List<ListingPoint> candidates = new ArrayList<>(points(5_000));
        candidates.add(out(5_001));
        givenRecommendedPoints(candidates);

        assertThatThrownBy(() -> service.search(null, noFilter(), null, SQUARE, null, 0, 20))
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_POLYGON_TOO_LARGE"));
        verify(listingRepository, never()).findAllById(any());
    }

    // 페이지 번호에는 위 끝이 없다 — page × size 를 int 로 곱하면 음수 위치가 되어 500 이 난다.
    @Test
    @DisplayName("아주 먼 쪽 번호도 빈 목록이다 — page × size 가 int 를 넘어도 500 이 아니다")
    void huge_page_number_does_not_overflow() {
        givenRecommendedPoints(List.of(in(1), in(2)));

        PageResponse<ListingSummaryResponse> page =
                service.search(null, noFilter(), null, SQUARE, null, 21_474_837, 100);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(2);
        verify(listingRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("두 조회 사이에 사라진 매물은 빼고 나머지만 순서대로 낸다")
    void listing_gone_between_queries_is_skipped() {
        givenRecommendedPoints(List.of(in(1), in(2), in(3)));
        given(listingRepository.findAllById(List.of(1L, 2L, 3L))).willReturn(List.of(listing(3L), listing(1L)));

        PageResponse<ListingSummaryResponse> page = service.search(null, noFilter(), null, SQUARE, null, 0, 20);

        assertThat(page.content()).extracting(ListingSummaryResponse::listingUuid).containsExactly("l-1", "l-3");
    }

    @Test
    @DisplayName("로그인이면 그린 구역 카드도 찜이 켜지고 첫 사진이 썸네일이다 — 그 쪽 매물만 한 번에 묻는다")
    void cards_carry_favorited_and_thumbnail() {
        givenRecommendedPoints(List.of(in(1), out(2), in(3)));
        given(userQueryService.getActiveUser("u-1")).willReturn(User.builder().id(10L).uuid("u-1").build());
        given(favoriteRepository.findFavoritedListingIds(eq(10L), any())).willReturn(List.of(3L));
        given(imageRepository.findByListingIdInOrderBySortOrderAsc(List.of(1L, 3L))).willReturn(List.of(
                ListingImage.builder().listingId(1L).fileId(21L).sortOrder(1).build(),
                ListingImage.builder().listingId(1L).fileId(22L).sortOrder(2).build()));
        given(storedFileRepository.findByIdInAndDeletedAtIsNull(any())).willReturn(List.of(StoredFile.builder()
                .id(21L).uuid("f-21").purpose(FilePurpose.LISTING_IMAGE).uploaderId(20L)
                .storageKey("listing-images/f-21").contentType("image/jpeg").byteSize(1024L)
                .fileUrl("https://file.example.test/f-21.jpg").build()));

        PageResponse<ListingSummaryResponse> page = service.search("u-1", noFilter(), null, SQUARE, null, 0, 20);

        assertThat(page.content()).extracting(ListingSummaryResponse::favorited).containsExactly(false, true);
        assertThat(page.content()).extracting(ListingSummaryResponse::thumbnailUrl)
                .containsExactly("https://file.example.test/f-21.jpg", null);
        verify(favoriteRepository, times(1)).findFavoritedListingIds(10L, List.of(1L, 3L));
    }

    @Test
    @DisplayName("후보 5,000건은 받는다 — 매물은 그 쪽 20건만 읽는다")
    void exactly_the_limit() {
        givenRecommendedPoints(points(5_000));

        PageResponse<ListingSummaryResponse> page = service.search(null, noFilter(), null, SQUARE, null, 0, 20);

        assertThat(page.totalElements()).isEqualTo(5_000);
        assertThat(page.content()).hasSize(20);
        verify(listingRepository).findAllById(LongStream.rangeClosed(1, 20).boxed().toList());
    }

    @Test
    @DisplayName("추천순 — 후보 쿼리에 상한 + 1 을 정렬 없이 넘기고, 구역의 외접 사각형을 건다")
    void recommended_uses_recommended_points() {
        givenRecommendedPoints(List.of());

        service.search(null, noFilter(), null, SQUARE, null, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listingRepository).findPublicPointsRecommended(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.93")),
                eq(new BigDecimal("37.57")), eq(new BigDecimal("126.95")), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5_001);
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
        verifyNoListQuery();
    }

    @Test
    @DisplayName("최신순 — 일반 후보 쿼리에 게시 시각 내림차순을 넘긴다")
    void latest_uses_sorted_points() {
        givenSortedPoints(List.of());

        service.search(null, noFilter(), null, SQUARE, ListingSort.LATEST, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listingRepository).findPublicPoints(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.93")),
                eq(new BigDecimal("37.57")), eq(new BigDecimal("126.95")), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5_001);
        assertThat(pageable.getValue().getSort().toList())
                .containsExactly(Sort.Order.desc("postedAt"), Sort.Order.desc("id"));
        verify(listingRepository, never()).findPublicPointsRecommended(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verifyNoListQuery();
    }

    @Test
    @DisplayName("지도 영역이 같이 오면 두 사각형의 겹침을 넘긴다")
    void bounding_box_is_intersected() {
        givenRecommendedPoints(List.of());
        ListingSearchCondition northHalf = new ListingSearchCondition(null, null, null, null,
                null, null, null, null, null, null, false, false, null, null, null,
                new BigDecimal("37.5600000"), new BigDecimal("126.9000000"),
                new BigDecimal("37.6000000"), new BigDecimal("126.9420000"));

        service.search(null, northHalf, null, SQUARE, null, 0, 20);

        verify(listingRepository).findPublicPointsRecommended(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                eq(new BigDecimal("37.5600000")), eq(new BigDecimal("126.93")),
                eq(new BigDecimal("37.57")), eq(new BigDecimal("126.9420000")), any());
    }

    // 변마다 더 좁은 쪽을 고른다 — 지도가 구역을 감싸면 구역 값, 구역 안쪽이면 지도 값. 네 변 중 하나만 틀려도 걸린다.
    @Test
    @DisplayName("겹침은 네 변마다 더 좁은 쪽 — 지도가 구역을 감싸면 구역, 구역 안이면 지도")
    void intersection_takes_tighter_side_on_every_edge() {
        givenRecommendedPoints(List.of());

        service.search(null, boxed("37.50", "126.90", "37.60", "126.98"), null, SQUARE, null, 0, 20);
        service.search(null, boxed("37.555", "126.935", "37.565", "126.945"), null, SQUARE, null, 0, 20);

        verify(listingRepository).findPublicPointsRecommended(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.93")),
                eq(new BigDecimal("37.57")), eq(new BigDecimal("126.95")), any());
        verify(listingRepository).findPublicPointsRecommended(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                eq(new BigDecimal("37.555")), eq(new BigDecimal("126.935")),
                eq(new BigDecimal("37.565")), eq(new BigDecimal("126.945")), any());
    }

    /*
     * ★ 후보 쿼리는 자리로 받는 인자가 20개이고, 같은 형(보증금 · 월세 · 지역의 Long)끼리는 바꿔 끼워도 컴파일된다(사무소 uuid 는 하나뿐인 String).
     *   칸마다 다른 값을 넣고 두 쿼리 모두 제자리를 본다.
     */
    @Test
    @DisplayName("모든 필터가 추천순 · 정렬 후보 쿼리 둘 다에 제자리로 간다")
    void every_filter_reaches_both_candidate_queries() {
        givenRecommendedPoints(List.of());
        givenSortedPoints(List.of());
        ListingSearchCondition every = new ListingSearchCondition(
                ListingCategory.OFFICETEL, List.of(SellingType.LEASE), List.of(RoomType.ONE_ROOM),
                List.of(FloorType.GROUND), 1L, 2L, 3L, 4L, new BigDecimal("5"), new BigDecimal("6"),
                true, false, AvailabilityAnswer.POSSIBLE, 7L, "o-every", null, null, null, null);

        service.search(null, every, null, SQUARE, null, 0, 20);
        service.search(null, every, null, SQUARE, ListingSort.LATEST, 0, 20);

        verify(listingRepository).findPublicPointsRecommended(
                eq(List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED)), eq(ListingCategory.OFFICETEL),
                eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), eq("o-every"),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.93")),
                eq(new BigDecimal("37.57")), eq(new BigDecimal("126.95")), any());
        verify(listingRepository).findPublicPoints(
                eq(List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED)), eq(ListingCategory.OFFICETEL),
                eq(List.of(SellingType.LEASE)), eq(List.of(RoomType.ONE_ROOM)), eq(List.of(FloorType.GROUND)),
                eq(1L), eq(2L), eq(3L), eq(4L), eq(new BigDecimal("5")), eq(new BigDecimal("6")),
                eq(true), eq(false), eq(AvailabilityAnswer.POSSIBLE), eq(7L), eq("o-every"),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.93")),
                eq(new BigDecimal("37.57")), eq(new BigDecimal("126.95")), any());
    }

    @Test
    @DisplayName("반경과 함께 오면 400 LISTING_RADIUS_INVALID — 쿼리를 하나도 부르지 않는다")
    void radius_and_polygon_together() {
        ListingRadius radius = ListingRadius.ofNullable(
                new BigDecimal("37.5600000"), new BigDecimal("126.9400000"), 500);

        assertThatThrownBy(() -> service.search(null, noFilter(), radius, SQUARE, null, 0, 20))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_RADIUS_INVALID"));
        verifyNoInteractions(listingRepository);
    }

    private void verifyNoListQuery() {
        verify(listingRepository, never()).findPublicRecommended(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(listingRepository, never()).findPublic(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    private void givenRecommendedPoints(List<ListingPoint> points) {
        given(listingRepository.findPublicPointsRecommended(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any())).willReturn(points);
    }

    private void givenSortedPoints(List<ListingPoint> points) {
        given(listingRepository.findPublicPoints(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any())).willReturn(points);
    }

    private static List<ListingPoint> points(int count) {
        return LongStream.rangeClosed(1, count).mapToObj(ListingQueryServicePolygonTest::in).toList();
    }

    private static ListingPoint in(long id) {
        return new ListingPoint(id, INSIDE_LAT, new BigDecimal("126.9400000"));
    }

    private static ListingPoint out(long id) {
        return new ListingPoint(id, OUTSIDE_LAT, new BigDecimal("126.9400000"));
    }

    private static ListingSearchCondition noFilter() {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null, null);
    }

    private static ListingSearchCondition boxed(String swLat, String swLng, String neLat, String neLng) {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, null, new BigDecimal(swLat), new BigDecimal(swLng),
                new BigDecimal(neLat), new BigDecimal(neLng));
    }

    private static Listing listing(Long id) {
        return Listing.builder()
                .id(id).uuid("l-" + id).listingNo("LS-261013-PG" + id)
                .officeId(3L).createdByUserId(20L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .realLat(INSIDE_LAT).realLng(new BigDecimal("126.9400000"))
                .displayLat(INSIDE_LAT).displayLng(new BigDecimal("126.9400000"))
                .postedAt(LocalDateTime.of(2026, 10, 5, 9, 0))
                .status(ListingStatus.ACTIVE).build();
    }
}
