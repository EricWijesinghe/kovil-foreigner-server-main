package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/*
 * 광고 기한 — 게시 후 1개월 → 만료 → 7일 안에 연장 없으면 자동 종료.
 *
 * ★★ 이 배치가 없으면 **모든 광고가 영원히 떠 있는다.** 그건 기능 누락이 아니라
 *    허위매물이다 — 자율규약이 거짓매물로 규정하는 "가격이 사실과 다른 매물"은
 *    대개 **방치된 옛 광고**다.
 *
 * ★ 별도 빈이다. 스케줄러 안에 두면 자기호출이 프록시를 우회해 트랜잭션이 걸리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListingExpiryProcessor {

    /*
     * ★ 예약 중인 매물도 만료시킨다. 광고 기한은 "이 광고가 지금도 사실인가"를 보증하는
     *   장치라, 거래가 걸려 있어도 한 달이 지나면 다시 확인받아야 한다.
     *   거래가 살아 있으면 중개사가 7일 안에 연장하면 된다.
     */
    private static final List<ListingStatus> EXPIRABLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ListingRepository listingRepository;
    private final NotificationPublisher notificationPublisher;
    private final ListingAlertRecipients alertRecipients;
    private final Clock clock;

    public List<Listing> findExpirable() {
        return listingRepository.findByStatusInAndExpiresAtBefore(EXPIRABLE, LocalDateTime.now(clock));
    }

    /*
     * ★ 유예 기간을 **여기서 빼서** 넘긴다. 리포지토리가 더하게 두면 상수가 두 군데에 생기고,
     *   하나만 고치는 날 연장 창과 종료 시점이 어긋난다.
     */
    public List<Listing> findArchivable() {
        LocalDateTime graceDeadline = LocalDateTime.now(clock)
                .minusDays(BrokerListingService.RENEWAL_GRACE_DAYS);
        return listingRepository.findByStatusAndExpiresAtBefore(ListingStatus.EXPIRED, graceDeadline);
    }

    // 건마다 트랜잭션을 끊는다 — 한 건이 실패해도 나머지가 멈추면 안 된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long listingId) {
        Listing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null || !EXPIRABLE.contains(listing.getStatus())) {
            return;
        }
        listing.expire();

        /*
         * ★★ 연장 창(만료 + 7일)은 **이 알림이 없으면 아무도 모르는 창**이다.
         *    PO 가 연장을 중개사 자가 처리로 정한 이상, 알리지 않으면
         *    매물이 조용히 사라지고 중개사는 이유를 모른다.
         */
        // 받는 사람이 여럿일 수 있어 중복 키에 사용자를 넣는다 — 빼면 두 번째 사람부터 "이미 보낸 알림"으로 버려진다.
        for (Long recipient : alertRecipients.of(listing)) {
            notificationPublisher.publish(recipient, NotiType.LISTING_EXPIRED,
                    "LISTING_EXPIRED:listing:" + listing.getUuid() + ":" + listing.getExpiresAt() + ":user:" + recipient,
                    Map.of("0", listing.getListingNo(),
                            "1", listing.getExpiresAt()
                                    .plusDays(BrokerListingService.RENEWAL_GRACE_DAYS).format(DEADLINE)),
                    "/broker/listings/" + listing.getUuid());
        }

        log.info("[listing] 만료 no={}", listing.getListingNo());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void archive(Long listingId) {
        Listing listing = listingRepository.findById(listingId).orElse(null);
        /*
         * ★ 상태를 다시 본다. 배치가 목록을 뽑은 뒤 중개사가 연장했으면 ACTIVE 다 —
         *   그걸 ARCHIVED 로 덮으면 **방금 연장한 매물을 되살릴 수 없게 만든다.**
         */
        if (listing == null || listing.getStatus() != ListingStatus.EXPIRED) {
            return;
        }
        listing.archive();

        // 알림을 보내지 않는다 — 만료 시점에 이미 "언제까지 연장 가능"을 말했다.
        log.info("[listing] 자동 종료 no={}", listing.getListingNo());
    }
}
