package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 서비스 지역이 닫힐 때 — PO 확정 2026-10-06: 닫는 순간 그 지역의 공개 매물을 내리고, 연장을 기다리던 매물도
 * 내림으로 보존하고, 중개사별로 한 건씩 알린다.
 * 판별 축은 다섯이다 — **어느 지역까지**(시도면 시군구 포함) · **어느 상태까지**(공개 + 연장 창 안의 만료) ·
 * **누구에게**(ListingAlertRecipients 가 정한 사람) · **몇 건**(사람마다 1건, 건수를 싣는다) · **중복 키**(닫힘 사건 · 사람마다).
 */
class ListingRegionClosureServiceTest {

    // 지역 id · 사용자 id 를 서로 다른 수로 둔다 — 바꿔 끼우면 스텁이 빗나간다.
    private static final Long SEOUL = 1L;
    private static final Long GWANAK = 38L;
    private static final Long JONGNO = 18L;
    private static final Long UPLOADER = 70L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 13, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private RegionRepository regionRepository;
    private ListingRepository listingRepository;
    private NotificationPublisher notificationPublisher;
    private ListingAlertRecipients alertRecipients;
    private ListingRegionClosureService service;

    @BeforeEach
    void setUp() {
        regionRepository = mock(RegionRepository.class);
        listingRepository = mock(ListingRepository.class);
        notificationPublisher = mock(NotificationPublisher.class);
        alertRecipients = mock(ListingAlertRecipients.class);
        // 기본은 올린 사람 한 명 — 수신자 규칙 자체는 ListingAlertRecipientsTest 가 고정한다.
        lenient().when(alertRecipients.of(any())).thenReturn(List.of(UPLOADER));
        service = new ListingRegionClosureService(regionRepository, listingRepository, notificationPublisher,
                alertRecipients, CLOCK);
    }

    @Test
    @DisplayName("시군구를 닫으면 그 지역의 공개 매물을 HIDDEN 으로 내린다 — 예약 중인 것도")
    void closing_a_district_hides_its_public_listings() {
        Listing active = listing(1L, "l-1", ListingStatus.ACTIVE);
        Listing reserved = listing(2L, "l-2", ListingStatus.RESERVED);
        givenPublic(active, reserved);

        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        assertThat(active.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        assertThat(reserved.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        assertThat(publicRegionIdsQueried()).containsExactly(GWANAK);
        verify(regionRepository, never()).findByParentId(any());
    }

    /*
     * ★★ 시도를 닫으면 그 아래 시군구는 **활성이어도** 게시할 수 없다. 시도 id 하나로만 찾으면 매물은 전부
     *    시군구에 붙어 있어 아무것도 내려가지 않는다.
     */
    @Test
    @DisplayName("시도를 닫으면 그 아래 시군구의 매물까지 함께 내린다")
    void closing_a_province_reaches_its_districts() {
        given(regionRepository.findByParentId(SEOUL)).willReturn(List.of(
                region(GWANAK, SEOUL, "11620"), region(JONGNO, SEOUL, "11110")));

        service.hideListingsIn(region(SEOUL, null, "11"));

        assertThat(publicRegionIdsQueried()).containsExactlyInAnyOrder(SEOUL, GWANAK, JONGNO);
    }

    @Test
    @DisplayName("공개 중인 상태(ACTIVE·RESERVED)를 찾는다")
    void queries_public_statuses() {
        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ListingStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(listingRepository).findAllByRegionIdInAndStatusIn(any(), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.RESERVED);
    }

    /*
     * ★★ 연장을 기다리던(EXPIRED) 매물도 내림으로 보존한다(PO 확정 2026-10-06). 지역이 닫히면 연장이 게시 관문에서
     *    막혀, 그대로 두면 7일 뒤 되살릴 수 없게 끝난다. 연장 창 안(만료 시각 ≥ 지금 − 7일)인 것만 — 창이 닫힌 것은
     *    배치가 정리할 몫이다.
     */
    @Test
    @DisplayName("연장 창 안의 만료 매물도 같은 지역 범위에서 찾아 HIDDEN 으로 보존한다")
    void preserves_expired_listings_within_the_renewal_window() {
        Listing expired = listing(3L, "l-3", ListingStatus.EXPIRED);
        given(listingRepository.findAllByRegionIdInAndStatusAndExpiresAtGreaterThanEqual(
                any(), eq(ListingStatus.EXPIRED), any())).willReturn(List.of(expired));

        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        assertThat(expired.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(listingRepository).findAllByRegionIdInAndStatusAndExpiresAtGreaterThanEqual(
                ids.capture(), eq(ListingStatus.EXPIRED), eq(NOW.minusDays(BrokerListingService.RENEWAL_GRACE_DAYS)));
        assertThat(ids.getValue()).containsExactly(GWANAK);
    }

    /*
     * ★★ 알림은 **중개사별 1건**이다(PO 확정 2026-10-06). 매물마다 보내면 서울을 닫는 순간 매물 200건을 가진 중개사가
     *    푸시 200건을 받는다. 본문에는 내려간 건수를 싣고, 누르면 내 매물 목록으로 간다.
     */
    @Test
    @DisplayName("같은 중개사의 매물이 여럿 내려가도 알림은 1건이고 건수를 싣는다")
    void one_notification_per_broker_with_the_count() {
        givenPublic(listing(1L, "l-1", ListingStatus.ACTIVE), listing(2L, "l-2", ListingStatus.ACTIVE));

        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        verify(notificationPublisher, times(1)).publish(any(), any(), anyString(), any(), any());
        verify(notificationPublisher).publish(eq(UPLOADER), eq(NotiType.LISTING_REGION_CLOSED),
                eq("LISTING_REGION_CLOSED:region:11620:" + NOW + ":user:" + UPLOADER),
                eq(Map.of("0", 2)), eq("/broker/listings"));
    }

    // 보존된 만료 매물도 건수에 든다 — 빠지면 연장 대기 매물만 가진 중개사는 매물이 내려갔는데 알림을 한 건도 못 받는다.
    @Test
    @DisplayName("보존된 만료 매물도 알림 건수에 들어간다")
    void preserved_expired_listings_are_counted() {
        givenPublic(listing(1L, "l-1", ListingStatus.ACTIVE));
        given(listingRepository.findAllByRegionIdInAndStatusAndExpiresAtGreaterThanEqual(
                any(), eq(ListingStatus.EXPIRED), any())).willReturn(List.of(listing(3L, "l-3", ListingStatus.EXPIRED)));

        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        verify(notificationPublisher).publish(eq(UPLOADER), any(), anyString(), eq(Map.of("0", 2)), any());
    }

    // 받는 사람마다 자기 몫의 건수를 받는다 — 키가 같으면 두 번째 사람부터 "이미 보낸 알림"으로 버려진다.
    @Test
    @DisplayName("받는 사람이 여럿이면 사람마다 자기 몫의 건수로 1건씩 보낸다")
    void each_recipient_gets_their_own_count() {
        Listing first = listing(1L, "l-1", ListingStatus.ACTIVE);
        Listing second = listing(2L, "l-2", ListingStatus.ACTIVE);
        givenPublic(first, second);
        given(alertRecipients.of(first)).willReturn(List.of(80L));
        given(alertRecipients.of(second)).willReturn(List.of(80L, 81L));

        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        String base = "LISTING_REGION_CLOSED:region:11620:" + NOW;
        verify(notificationPublisher).publish(eq(80L), any(), eq(base + ":user:80"), eq(Map.of("0", 2)), any());
        verify(notificationPublisher).publish(eq(81L), any(), eq(base + ":user:81"), eq(Map.of("0", 1)), any());
        verify(notificationPublisher, never()).publish(eq(UPLOADER), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("내릴 매물이 없으면 아무에게도 알리지 않는다")
    void nothing_to_hide_nobody_notified() {
        service.hideListingsIn(region(GWANAK, SEOUL, "11620"));

        verify(notificationPublisher, never()).publish(any(), any(), anyString(), any(), any());
    }

    /*
     * ★ 지역 저장 트랜잭션에 **합류**해야 한다. 따로 커밋(REQUIRES_NEW)하면 지역 닫기가 롤백돼도 매물은 내려간 채이고
     *   알림까지 나간다 — 중개사는 일어나지 않은 "지역 닫힘"을 통보받는다.
     */
    @Test
    @DisplayName("닫힘 처리는 지역 저장 트랜잭션에 합류한다")
    void joins_the_region_save_transaction() throws NoSuchMethodException {
        Transactional tx = ListingRegionClosureService.class
                .getMethod("hideListingsIn", Region.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    private void givenPublic(Listing... listings) {
        given(listingRepository.findAllByRegionIdInAndStatusIn(any(), any())).willReturn(List.of(listings));
    }

    @SuppressWarnings("unchecked")
    private Collection<Long> publicRegionIdsQueried() {
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(listingRepository).findAllByRegionIdInAndStatusIn(ids.capture(), any());
        return ids.getValue();
    }

    private static Region region(Long id, Long parentId, String code) {
        return Region.builder().id(id).parentId(parentId).code(code).nameKo("지역").nameEn("Region").build();
    }

    private static Listing listing(Long id, String uuid, ListingStatus status) {
        return Listing.builder()
                .id(id).uuid(uuid).listingNo("LS-261006-AAA00" + id)
                .officeId(3L).createdByUserId(UPLOADER).regionId(GWANAK)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동").status(status)
                .expiresAt(NOW.minusDays(2)).build();
    }
}
