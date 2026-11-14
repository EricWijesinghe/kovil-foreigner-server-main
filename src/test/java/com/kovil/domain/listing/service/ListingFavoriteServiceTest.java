package com.kovil.domain.listing.service;

import com.kovil.domain.listing.repository.ListingFavoriteRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;

import java.util.Optional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 탈퇴 시 찜 정리. **순서가 틀리면 조용히 깨진다.**
 */
class ListingFavoriteServiceTest {

    private ListingFavoriteRepository favoriteRepository;
    private ListingRepository listingRepository;
    private UserQueryService userQueryService;
    private ListingFavoriteService service;

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(ListingFavoriteRepository.class);
        listingRepository = mock(ListingRepository.class);
        userQueryService = mock(UserQueryService.class);
        service = new ListingFavoriteService(favoriteRepository, listingRepository,
                userQueryService,
                Clock.fixed(LocalDateTime.of(2026, 10, 5, 12, 0).toInstant(ZoneOffset.UTC),
                        ZoneOffset.UTC));
    }

    /*
     * ★★ 실측 2026-10-05: 찜 **30회 동시 요청에서 5건이 500** 이었다.
     *    exists 검사와 insert 사이의 창을 둘이 통과해 PK(user_id, listing_id) 위반이 터진다.
     *    (10·20회 측정에서 못 본 이유는 **상태 코드를 안 보고 DB 최종 상태만 봤기** 때문이다 —
     *     행과 카운터는 1 로 맞아 있어 "버텼다"로 보였다. **측정의 결함이었다.**)
     *    찜은 멱등해야 한다 — 연타가 에러면 유저는 자기가 뭘 잘못했는지 알 수 없다.
     */
    /*
     * ★★ **반환값으로 판정하지 않는다.** MySQL JDBC 는 기본값에서 ON DUPLICATE KEY UPDATE 에
     *    matched rows 를 돌려줘 **중복에도 1** 이 온다 — 그걸 믿고 +1 했더니 40회 동시 찜에
     *    카운터가 **40** 이 됐다(실측 2026-10-06). 그래서 넣은 뒤 **행 수로 다시 센다.**
     */
    @Test
    @DisplayName("찜 추가는 insert 반환값을 믿지 않고 행 수로 다시 센다")
    void add_syncs_the_counter_from_rows() {
        Listing listing = publicListing();
        given(userQueryService.getActiveUser("u-1")).willReturn(
                User.builder().id(10L).uuid("u-1").build());
        given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.of(listing));
        given(favoriteRepository.insertIfAbsent(eq(10L), eq(1L), any())).willReturn(1);

        service.add("u-1", "l-1");

        verify(listingRepository).syncFavoriteCount(1L);
    }

    /*
     * ★★ **exists 로 먼저 묻지 않는다.** 검사와 insert 사이의 창을 통과한 PK 위반은
     *    같은 트랜잭션 안에서 잡아도 커밋에서 UnexpectedRollbackException 으로 터진다
     *    (실측 2026-10-06: try/catch 로 고친 줄 알았는데 40회 동시에서 여전히 5건이 500 이었다).
     *    그래서 **예외가 나지 않는 한 문장**(ON DUPLICATE KEY UPDATE)으로 바꿨다.
     */
    @Test
    @DisplayName("찜 추가는 exists 를 묻지 않는다 — 검사-후-삽입 창을 아예 없앤다")
    void add_does_not_check_before_inserting() {
        Listing listing = publicListing();
        given(userQueryService.getActiveUser("u-1")).willReturn(
                User.builder().id(10L).uuid("u-1").build());
        given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.of(listing));

        service.add("u-1", "l-1");

        verify(favoriteRepository, never()).existsByUserIdAndListingId(any(), any());
        verify(favoriteRepository).insertIfAbsent(eq(10L), eq(1L), any());
    }

    /*
     * ★ 해제는 **지운 행 수**로 판정한다. exists 로 먼저 묻고 지우면 동시 해제 둘이 다 통과해
     *   카운터가 두 번 내려간다.
     */
    @Test
    @DisplayName("해제도 벌크 삭제 뒤 행 수로 다시 센다 — 엔티티 삭제의 409 를 피한다")
    void remove_uses_bulk_delete_then_syncs() {
        Listing listing = publicListing();
        given(userQueryService.getActiveUser("u-1")).willReturn(
                User.builder().id(10L).uuid("u-1").build());
        given(listingRepository.findByUuid("l-1")).willReturn(Optional.of(listing));

        service.remove("u-1", "l-1");

        verify(favoriteRepository).deleteOne(10L, 1L);
        verify(listingRepository).syncFavoriteCount(1L);
        verify(listingRepository, never()).decrementFavoriteCount(any());
    }

    private Listing publicListing() {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(70L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동").status(ListingStatus.ACTIVE).build();
    }

    @Test
    @DisplayName("탈퇴하면 찜을 지우고 매물의 찜 수도 되돌린다")
    void purges_and_restores_counters() {
        given(favoriteRepository.findListingIdsByUserIdForUpdate(10L)).willReturn(List.of(1L, 2L, 3L));

        service.purgeOnWithdrawal(10L);

        verify(listingRepository).decrementFavoriteCount(List.of(1L, 2L, 3L));
        verify(favoriteRepository).deleteAllByUserId(10L);
    }

    /*
     * ★★ 지우기 **전에** 읽어야 한다. 먼저 지우면 되돌릴 대상을 영영 알 수 없고,
     *    아무도 찜하지 않은 매물에 숫자만 남는다. 순서가 곧 정확성이다.
     */
    @Test
    @DisplayName("카운터를 되돌린 뒤에 지운다 — 순서가 뒤집히면 대상을 잃는다")
    void decrements_before_deleting() {
        given(favoriteRepository.findListingIdsByUserIdForUpdate(10L)).willReturn(List.of(1L));

        service.purgeOnWithdrawal(10L);

        InOrder order = inOrder(favoriteRepository, listingRepository);
        order.verify(favoriteRepository).findListingIdsByUserIdForUpdate(10L);
        order.verify(listingRepository).decrementFavoriteCount(any());
        order.verify(favoriteRepository).deleteAllByUserId(10L);
    }

    // 찜한 적 없는 사람도 탈퇴한다 — 빈 IN 절로 UPDATE 를 쏘면 안 된다.
    @Test
    @DisplayName("찜이 없으면 아무것도 건드리지 않는다")
    void does_nothing_when_no_favorites() {
        given(favoriteRepository.findListingIdsByUserIdForUpdate(10L)).willReturn(List.of());

        service.purgeOnWithdrawal(10L);

        verify(listingRepository, never()).decrementFavoriteCount(any());
        verify(favoriteRepository, never()).deleteAllByUserId(any());
    }
}
