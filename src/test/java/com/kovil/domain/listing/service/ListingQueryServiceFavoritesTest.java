package com.kovil.domain.listing.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.response.ListingFavoriteItem;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingFavorite;
import com.kovil.domain.listing.entity.ListingImage;
import com.kovil.domain.listing.entity.ListingMaintenance;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 내 찜 목록의 **조립부**. 쿼리는 ListingFavoritePageQueryTest 가 보고, 여기서는
 * 공개 · 내려간 매물을 어떻게 나눠 담는지, 그리고 내려간 매물의 brief 로 무엇이 새지 않는지를 본다.
 *
 * ★ 찜 순서를 **매물 id 와 반대로** 둔다(내려간 매물 id 2 를 나중에 담아 먼저 나온다). 매물 맵은 HashMap 이라
 *   작은 id 를 오름차순으로 돌려준다 — 찜 순서가 id 순서와 같으면, 결과를 매물 맵에서 늘어놓는 실수가 초록으로 지나간다.
 * ★ 두 매물은 방 종류 · 분리형 · 복층 · 거래 유형이 전부 다르다 — 같으면 칸을 바꿔 끼워도 보이지 않는다.
 */
class ListingQueryServiceFavoritesTest {

    private static final BigDecimal REAL_LAT = new BigDecimal("37.4862378");
    private static final BigDecimal DISPLAY_LAT = new BigDecimal("37.4862552");

    private static final Long ME = 10L;
    private static final String ME_UUID = "u-1";
    private static final Long PUBLIC_ID = 1L;
    private static final Long GONE_ID = 2L;
    private static final LocalDateTime PUBLIC_AT = LocalDateTime.of(2026, 10, 1, 9, 5);
    private static final LocalDateTime GONE_AT = LocalDateTime.of(2026, 10, 8, 14, 20);

    private UserQueryService userQueryService;
    private ListingRepository listingRepository;
    private ListingMaintenanceRepository maintenanceRepository;
    private ListingImageRepository imageRepository;
    private ListingFavoriteRepository favoriteRepository;
    private StoredFileRepository storedFileRepository;
    private ListingQueryService service;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        listingRepository = mock(ListingRepository.class);
        maintenanceRepository = mock(ListingMaintenanceRepository.class);
        imageRepository = mock(ListingImageRepository.class);
        favoriteRepository = mock(ListingFavoriteRepository.class);
        storedFileRepository = mock(StoredFileRepository.class);
        service = new ListingQueryService(userQueryService, listingRepository,
                mock(BrokerageOfficeRepository.class), maintenanceRepository,
                mock(ListingMaintenanceItemRepository.class), mock(ListingMaintenanceIncludeRepository.class),
                mock(ListingOptionRepository.class), mock(ListingSafetyRepository.class),
                imageRepository, favoriteRepository, mock(RegionRepository.class), storedFileRepository);

        lenient().when(userQueryService.getActiveUser(ME_UUID))
                .thenReturn(User.builder().id(ME).uuid(ME_UUID).build());
    }

    @Test
    @DisplayName("공개 매물은 listing 카드로, 내려간 매물은 brief 로 — 둘 중 하나만 온다")
    void splits_public_and_gone() {
        givenTwoFavorites();

        List<ListingFavoriteItem> items = service.findMyFavorites(ME_UUID, PageRequest.of(0, 20)).content();

        ListingFavoriteItem gone = items.get(0);
        assertThat(gone.listingUuid()).isEqualTo("l-gone");
        assertThat(gone.favoritedAt()).isEqualTo(GONE_AT);
        assertThat(gone.available()).isFalse();
        assertThat(gone.listing()).isNull();
        assertThat(gone.brief()).isNotNull();

        ListingFavoriteItem available = items.get(1);
        assertThat(available.listingUuid()).isEqualTo("l-public");
        assertThat(available.favoritedAt()).isEqualTo(PUBLIC_AT);
        assertThat(available.available()).isTrue();
        assertThat(available.brief()).isNull();
        assertThat(available.listing().listingUuid()).isEqualTo("l-public");
        assertThat(available.listing().maintenanceCost()).isEqualTo(70_000L);
        assertThat(available.listing().thumbnailUrl()).isEqualTo(urlOf(501L));
    }

    @Test
    @DisplayName("brief 는 내려간 매물의 번호 · 사진 · 방 종류 · 분리형 · 복층 · 거래 유형 · 가격을 그대로 담는다")
    void brief_maps_each_field_of_the_gone_listing() {
        givenTwoFavorites();

        ListingFavoriteItem.Brief brief = service.findMyFavorites(ME_UUID, PageRequest.of(0, 20))
                .content().get(0).brief();

        assertThat(brief.listingNo()).isEqualTo("LS-261001-D4E5F6");
        assertThat(brief.thumbnailUrl()).isEqualTo(urlOf(502L));
        assertThat(brief.roomType()).isEqualTo(RoomType.TWO_ROOM);
        assertThat(brief.division()).isTrue();
        assertThat(brief.duplex()).isFalse();
        assertThat(brief.sellingType()).isEqualTo(SellingType.LEASE);
        assertThat(brief.deposit()).isEqualTo(150_000_000L);
        assertThat(brief.monthlyRent()).isEqualTo(0L);
    }

    // 찜 목록 카드의 하트는 늘 켜져 있다 — 내 찜 목록이므로 따로 묻지 않는다.
    @Test
    @DisplayName("공개 카드의 favorited 는 늘 true 이고, 찜 여부를 다시 묻지 않는다")
    void card_is_always_favorited() {
        givenTwoFavorites();

        ListingFavoriteItem available = service.findMyFavorites(ME_UUID, PageRequest.of(0, 20)).content().get(1);

        assertThat(available.listing().favorited()).isTrue();
        verify(favoriteRepository, never()).findFavoritedListingIds(any(), any());
    }

    /*
     * ★ 공개 판정은 공개 상세와 같은 상태(게시 중 · 예약 중)로 묻는다. 좁히면 예약 중인 방이 내려간 매물로 보이고,
     *   넓히면 거래가 끝난 방이 카드로 보이는데 누르면 404 다.
     */
    @Test
    @DisplayName("공개 판정은 게시 중 · 예약 중 두 상태로 묻는다")
    void public_judgement_uses_visible_statuses() {
        givenTwoFavorites();

        service.findMyFavorites(ME_UUID, PageRequest.of(0, 20));

        verify(listingRepository).findPublicIdsIn(any(),
                eq(List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED)));
    }

    /*
     * ★ 순서는 찜 페이지가 정한다(최근에 담은 순). findAllById 의 순서도, 매물 맵의 순서도 화면 순서가 아니다.
     */
    @Test
    @DisplayName("순서는 찜 페이지 순서다 — 매물을 읽어 온 순서도, 매물 id 순서도 아니다")
    void order_follows_favorite_page() {
        givenTwoFavorites();
        given(listingRepository.findAllById(any())).willReturn(List.of(publicListing(), goneListing()));

        List<ListingFavoriteItem> items = service.findMyFavorites(ME_UUID, PageRequest.of(0, 20)).content();

        assertThat(items).extracting(ListingFavoriteItem::listingUuid).containsExactly("l-gone", "l-public");
    }

    /*
     * ★★ 내려간 광고를 다시 퍼뜨리지 않는다 — brief 에는 좌표 · 동 · 제목 · 사무소 · 조회수가 없다.
     *    직렬화한 칸 이름으로 본다. 필드를 하나씩 보면 다른 이름으로 새는 칸을 놓친다.
     */
    @Test
    @DisplayName("내려간 매물의 brief 에는 흐린 카드에 필요한 8칸만 있고, 좌표 · 제목 · 동 · 사무소 · 조회수는 없다")
    void brief_carries_only_the_dim_card_fields() throws Exception {
        givenTwoFavorites();

        ListingFavoriteItem gone = service.findMyFavorites(ME_UUID, PageRequest.of(0, 20)).content().get(0);
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode brief = mapper.readTree(mapper.writeValueAsString(gone)).get("brief");

        List<String> names = brief.properties().stream().map(Map.Entry::getKey).toList();
        assertThat(names).containsExactlyInAnyOrder("listingNo", "thumbnailUrl", "roomType",
                "division", "duplex", "sellingType", "deposit", "monthlyRent");
        assertThat(mapper.writeValueAsString(gone))
                .doesNotContain(REAL_LAT.toPlainString())
                .doesNotContain(DISPLAY_LAT.toPlainString())
                .doesNotContain("창천동")
                .doesNotContain("내려간 매물 제목");
    }

    // 관리비는 카드에만 쓰인다 — 내려간 매물 것까지 읽을 이유가 없다.
    @Test
    @DisplayName("관리비는 공개 매물 것만 읽는다")
    void maintenance_is_read_for_public_listings_only() {
        givenTwoFavorites();

        service.findMyFavorites(ME_UUID, PageRequest.of(0, 20));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(maintenanceRepository).findByListingIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactly(PUBLIC_ID);
    }

    /*
     * ★ 빈 IN 목록은 벤더마다 다르게 깨진다 — 비었으면 공개 판정을 묻지 않는다.
     *   그래도 페이지 정보는 찜 페이지 그대로여야 한다(범위를 넘긴 page 요청에서 전체 개수가 남는다).
     */
    @Test
    @DisplayName("빈 페이지면 공개 판정을 묻지 않고, 전체 개수는 그대로 준다")
    void empty_page_skips_public_judgement_and_keeps_total() {
        Pageable beyond = PageRequest.of(5, 20);
        given(favoriteRepository.findPageByUserId(ME, beyond)).willReturn(new PageImpl<>(List.of(), beyond, 2));

        PageResponse<ListingFavoriteItem> out = service.findMyFavorites(ME_UUID, beyond);

        assertThat(out.content()).isEmpty();
        assertThat(out.totalElements()).isEqualTo(2);
        verify(listingRepository, never()).findPublicIdsIn(any(), any());
        verifyNoInteractions(maintenanceRepository, imageRepository, storedFileRepository);
    }

    @Test
    @DisplayName("정지된 계정은 찜 목록을 볼 수 없다 — 찜을 읽기 전에 막힌다")
    void suspended_user_is_rejected_before_reading_favorites() {
        given(userQueryService.getActiveUser(ME_UUID)).willThrow(new UserException(UserErrorCode.USER_SUSPENDED));

        assertThatThrownBy(() -> service.findMyFavorites(ME_UUID, PageRequest.of(0, 20)))
                .isInstanceOf(UserException.class);
        verifyNoInteractions(favoriteRepository);
    }

    // 관리자가 차단한 매물(사진 도용 · 사기 신고 등)은 찜 카드에서도 사진을 내린다 — 다른 내려간 매물은 사진을 남긴다(PO 2026-10-13).
    @Test
    @DisplayName("차단된 매물의 brief 는 사진만 비우고 번호 · 가격은 남긴다 — 거래 완료 등 다른 내려간 매물은 사진을 그대로 준다")
    void blocked_listing_brief_has_no_thumbnail() {
        Long blockedId = 3L;
        Pageable pageable = PageRequest.of(0, 20);
        given(favoriteRepository.findPageByUserId(ME, pageable)).willReturn(new PageImpl<>(List.of(
                favorite(blockedId, GONE_AT), favorite(GONE_ID, PUBLIC_AT)), pageable, 2));
        Listing blocked = base(blockedId, "l-blocked", "LS-261002-B10C4D").title("차단된 매물")
                .roomType(RoomType.ONE_ROOM).sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(350_000L).status(ListingStatus.BLOCKED).build();
        given(listingRepository.findAllById(any())).willReturn(List.of(goneListing(), blocked));
        given(listingRepository.findPublicIdsIn(any(), any())).willReturn(List.of());
        given(imageRepository.findByListingIdInOrderBySortOrderAsc(any())).willReturn(List.of(
                ListingImage.builder().listingId(GONE_ID).fileId(502L).sortOrder(0).build(),
                ListingImage.builder().listingId(blockedId).fileId(503L).sortOrder(0).build()));
        given(storedFileRepository.findByIdInAndDeletedAtIsNull(any())).willReturn(List.of(file(502L), file(503L)));

        List<ListingFavoriteItem> items = service.findMyFavorites(ME_UUID, pageable).content();

        assertThat(items).extracting(ListingFavoriteItem::listingUuid).containsExactly("l-blocked", "l-gone");
        ListingFavoriteItem.Brief blockedBrief = items.get(0).brief();
        assertThat(items.get(0).available()).isFalse();
        assertThat(blockedBrief.thumbnailUrl()).isNull();
        assertThat(blockedBrief.listingNo()).isEqualTo("LS-261002-B10C4D");
        assertThat(blockedBrief.monthlyRent()).isEqualTo(350_000L);
        assertThat(items.get(1).brief().thumbnailUrl()).isEqualTo(urlOf(502L));
    }

    // ───────────────────────── 픽스처

    // 찜 페이지는 최근에 담은 순 — 내려간 매물(id 2, 10-08)이 공개 매물(id 1, 10-01)보다 먼저다.
    private void givenTwoFavorites() {
        Pageable pageable = PageRequest.of(0, 20);
        given(favoriteRepository.findPageByUserId(ME, pageable)).willReturn(new PageImpl<>(List.of(
                favorite(GONE_ID, GONE_AT), favorite(PUBLIC_ID, PUBLIC_AT)), pageable, 2));
        given(listingRepository.findAllById(any())).willReturn(List.of(goneListing(), publicListing()));
        given(listingRepository.findPublicIdsIn(any(), any())).willReturn(List.of(PUBLIC_ID));
        given(imageRepository.findByListingIdInOrderBySortOrderAsc(any())).willReturn(List.of(
                ListingImage.builder().listingId(PUBLIC_ID).fileId(501L).sortOrder(0).build(),
                ListingImage.builder().listingId(GONE_ID).fileId(502L).sortOrder(0).build()));
        given(storedFileRepository.findByIdInAndDeletedAtIsNull(any())).willReturn(List.of(file(501L), file(502L)));
        given(maintenanceRepository.findByListingIdIn(any())).willReturn(List.of(
                ListingMaintenance.builder().listingId(PUBLIC_ID)
                        .chargeType(MaintenanceChargeType.FIXED_FEE).totalCost(70_000L).build()));
    }

    private ListingFavorite favorite(Long listingId, LocalDateTime at) {
        return ListingFavorite.builder().userId(ME).listingId(listingId).createdAt(at).build();
    }

    private Listing publicListing() {
        return base(PUBLIC_ID, "l-public", "LS-261008-A1B2C3").title("신촌역 5분 원룸")
                .roomType(RoomType.ONE_ROOM).division(false).duplex(false)
                .sellingType(SellingType.MONTHLY_RENT).deposit(5_000_000L).monthlyRent(450_000L)
                .status(ListingStatus.ACTIVE).build();
    }

    private Listing goneListing() {
        return base(GONE_ID, "l-gone", "LS-261001-D4E5F6").title("내려간 매물 제목")
                .roomType(RoomType.TWO_ROOM).division(true).duplex(false)
                .sellingType(SellingType.LEASE).deposit(150_000_000L).monthlyRent(0L)
                .status(ListingStatus.COMPLETED).build();
    }

    private Listing.ListingBuilder<?, ?> base(Long id, String uuid, String listingNo) {
        return Listing.builder()
                .id(id).uuid(uuid).listingNo(listingNo)
                .officeId(3L).createdByUserId(20L)
                .category(ListingCategory.ONE_TWO_ROOM)
                .exclusiveArea(new BigDecimal("23.10")).floorType(FloorType.GROUND)
                .realLat(REAL_LAT).realLng(new BigDecimal("126.9331220"))
                .displayLat(DISPLAY_LAT).displayLng(new BigDecimal("126.9332423"))
                .dongName("창천동").addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
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
}
