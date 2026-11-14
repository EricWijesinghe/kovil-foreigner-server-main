package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 광고 기한. 판별 축은 셋이다 —
 * **무엇이 만료 대상인가**(예약 중도 포함) · **연장 창을 알리는가** · **연장한 건을 덮지 않는가**.
 */
class ListingExpiryProcessorTest {

    // 고정 시계. "지금"이 흔들리면 유예 7일 경계를 테스트할 수 없다.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 3, 20);
    private static final Clock CLOCK =
            Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    private ListingRepository listingRepository;
    private NotificationPublisher notificationPublisher;
    private ListingAlertRecipients alertRecipients;
    private ListingExpiryProcessor processor;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        notificationPublisher = mock(NotificationPublisher.class);
        alertRecipients = mock(ListingAlertRecipients.class);
        // 기본은 올린 사람 한 명 — 여러 명은 아래 테스트가 따로 세운다.
        lenient().when(alertRecipients.of(any())).thenReturn(List.of(70L));
        processor = new ListingExpiryProcessor(listingRepository, notificationPublisher, alertRecipients, CLOCK);
    }

    /*
     * ★ 예약 중인 매물을 빼면 거래가 틀어진 방이 **영원히 예약중으로 떠 있는다.**
     *   광고 기한은 "이 광고가 지금도 사실인가"를 보증하는 장치다.
     */
    @Test
    @DisplayName("만료 대상은 게시중과 예약중 둘 다다")
    void expirable_includes_reserved() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ListingStatus>> captor = ArgumentCaptor.forClass(List.class);
        given(listingRepository.findByStatusInAndExpiresAtBefore(any(), any())).willReturn(List.of());

        processor.findExpirable();

        verify(listingRepository).findByStatusInAndExpiresAtBefore(captor.capture(), eq(NOW));
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.RESERVED);
    }

    /*
     * ★★ 연장 창(만료 + 7일)은 **이 알림이 없으면 아무도 모르는 창**이다.
     *    PO 가 연장을 중개사 자가 처리로 정했으므로, 알리지 않으면 매물이
     *    조용히 사라지고 중개사는 이유를 모른다.
     */
    @Test
    @DisplayName("만료하면 중개사에게 알리고, 본문에 연장 마감일(만료+7일)이 들어간다")
    void expiry_notifies_broker_with_renewal_deadline() {
        Listing listing = listing(ListingStatus.ACTIVE, NOW.minusHours(2));
        given(listingRepository.findById(1L)).willReturn(Optional.of(listing));

        processor.expire(1L);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.EXPIRED);

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(notificationPublisher).publish(eq(70L), eq(NotiType.LISTING_EXPIRED),
                anyString(), params.capture(), anyString());
        assertThat(params.getValue().get("0")).isEqualTo("LS-261005-ABC123");
        // 만료가 10-05 01:20 이었으니 연장 마감은 10-12 다.
        assertThat(params.getValue().get("1")).isEqualTo("2026-10-12");
    }

    /*
     * ★ 올린 사람이 떠나 동료 여럿이 받으면 **사람마다 따로** 간다. 중복 키에 사용자가 없으면
     *   두 번째 사람부터 "이미 보낸 알림"으로 조용히 버려진다(발송큐의 dedupe_key 는 UNIQUE).
     */
    @Test
    @DisplayName("받는 사람이 여럿이면 사람마다 다른 중복 키로 한 건씩 보낸다")
    void expiry_notifies_each_recipient_with_its_own_key() {
        Listing listing = listing(ListingStatus.ACTIVE, NOW.minusHours(2));
        given(listingRepository.findById(1L)).willReturn(Optional.of(listing));
        given(alertRecipients.of(listing)).willReturn(List.of(80L, 81L));

        processor.expire(1L);

        String base = "LISTING_EXPIRED:listing:l-1:" + NOW.minusHours(2);
        verify(notificationPublisher).publish(eq(80L), eq(NotiType.LISTING_EXPIRED), eq(base + ":user:80"),
                any(), anyString());
        verify(notificationPublisher).publish(eq(81L), eq(NotiType.LISTING_EXPIRED), eq(base + ":user:81"),
                any(), anyString());
        verify(notificationPublisher, never()).publish(eq(70L), any(), anyString(), any(), anyString());
    }

    // 배치가 두 번 돌아도 알림이 두 번 가면 안 된다. 1차 방어는 여기, 최종 방어는 dedupe_key 다.
    @Test
    @DisplayName("이미 만료된 건은 다시 만료시키지 않는다")
    void expiry_is_idempotent() {
        given(listingRepository.findById(1L))
                .willReturn(Optional.of(listing(ListingStatus.EXPIRED, NOW.minusDays(1))));

        processor.expire(1L);

        verify(notificationPublisher, never()).publish(any(), any(), anyString(), any(), anyString());
    }

    // 유예 상수가 리포지토리와 서비스 양쪽에 생기면 하나만 고치는 날이 온다.
    @Test
    @DisplayName("자동 종료 스캔 기준은 '지금 - 7일'이다 — 유예 상수는 한 군데뿐이다")
    void archive_scan_subtracts_the_grace_period() {
        given(listingRepository.findByStatusAndExpiresAtBefore(any(), any())).willReturn(List.of());

        processor.findArchivable();

        verify(listingRepository).findByStatusAndExpiresAtBefore(
                eq(ListingStatus.EXPIRED),
                eq(NOW.minusDays(BrokerListingService.RENEWAL_GRACE_DAYS)));
    }

    /*
     * ★★ 배치가 목록을 뽑은 뒤 중개사가 연장하면 그 매물은 ACTIVE 다.
     *    상태를 다시 보지 않고 덮으면 **방금 연장한 매물이 되살릴 수 없는 상태로 떨어진다.**
     */
    @Test
    @DisplayName("목록을 뽑은 뒤 연장된 매물은 자동 종료하지 않는다")
    void archive_skips_a_listing_renewed_in_the_meantime() {
        Listing renewed = listing(ListingStatus.ACTIVE, NOW.plusMonths(1));
        given(listingRepository.findById(1L)).willReturn(Optional.of(renewed));

        processor.archive(1L);

        assertThat(renewed.getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    @DisplayName("연장 창이 닫히면 되살릴 수 없는 ARCHIVED 가 된다")
    void archive_moves_to_archived() {
        Listing listing = listing(ListingStatus.EXPIRED, NOW.minusDays(8));
        given(listingRepository.findById(1L)).willReturn(Optional.of(listing));

        processor.archive(1L);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ARCHIVED);
    }

    // 만료 시점에 "언제까지 연장 가능"을 이미 말했다. 두 번째 푸시는 알릴 것이 없다.
    @Test
    @DisplayName("자동 종료는 알림을 보내지 않는다")
    void archive_does_not_notify() {
        given(listingRepository.findById(1L))
                .willReturn(Optional.of(listing(ListingStatus.EXPIRED, NOW.minusDays(8))));

        processor.archive(1L);

        verify(notificationPublisher, never()).publish(any(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("사라진 매물을 집어도 터지지 않는다")
    void missing_listing_is_ignored() {
        given(listingRepository.findById(1L)).willReturn(Optional.empty());

        processor.expire(1L);
        processor.archive(1L);

        verify(notificationPublisher, never()).publish(any(), any(), anyString(), any(), anyString());
    }

    private Listing listing(ListingStatus status, LocalDateTime expiresAt) {
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
                .addressJibun("서울특별시 관악구 신림동")
                .status(status).postedAt(NOW.minusMonths(1)).expiresAt(expiresAt)
                .build();
    }
}
