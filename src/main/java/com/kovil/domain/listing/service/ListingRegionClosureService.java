package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * 서비스 지역이 닫힐 때. **PO 확정 2026-10-06: 닫는 순간 그 지역의 공개 매물을 내리고 중개사에게 알린다**
 * (받는 사람은 ListingAlertRecipients 가 정한다).
 *
 * ★★ 닫힌 지역의 매물은 게시 관문을 통과할 수 없어 **수정 뒤 재게시·연장이 전부 막힌다.** 그대로 두면
 *    틀린 줄 알아도 못 고치는 광고가 만료(최대 1개월)까지 떠 있다.
 * ★ 시도를 닫으면 그 아래 시군구는 활성이어도 게시할 수 없다(게시 판정이 부모 활성까지 본다) — 함께 내린다.
 * ★ 연장을 기다리던(EXPIRED, 연장 창 안) 매물도 내림으로 보존한다 — 그대로 두면 연장이 막힌 채 7일 뒤 되살릴 수 없게 끝난다.
 * ★ 내리는 상태는 HIDDEN 이다. 지역이 다시 열리면 중개사가 그대로 다시 올릴 수 있다.
 *   **자동으로 되살리지 않는다** — 닫혀 있던 사이에 사정이 바뀐 매물이 확인 없이 다시 나간다.
 * ★ 알림은 **중개사별 1건**이다(PO 확정 2026-10-06) — 매물마다 보내면 서울을 닫는 순간 매물 수만큼 푸시가 쏟아진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingRegionClosureService {

    private static final List<ListingStatus> PUBLIC = List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final RegionRepository regionRepository;
    private final ListingRepository listingRepository;
    private final NotificationPublisher notificationPublisher;
    private final ListingAlertRecipients alertRecipients;
    private final Clock clock;

    // 호출자(지역 수정)의 트랜잭션에 합류한다 — 지역 닫기가 롤백되면 내린 매물과 알림도 함께 되돌아가야 한다.
    @Transactional
    public void hideListingsIn(Region closed) {
        List<Long> regionIds = new ArrayList<>();
        regionIds.add(closed.getId());
        if (closed.isRoot()) {
            regionRepository.findByParentId(closed.getId()).forEach(child -> regionIds.add(child.getId()));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<Listing> targets = new ArrayList<>(listingRepository.findAllByRegionIdInAndStatusIn(regionIds, PUBLIC));
        // 연장 창 안(만료 + 7일 이내)의 만료 매물만 — 창이 이미 닫힌 것은 배치가 정리할 몫이다.
        targets.addAll(listingRepository.findAllByRegionIdInAndStatusAndExpiresAtGreaterThanEqual(
                regionIds, ListingStatus.EXPIRED, now.minusDays(BrokerListingService.RENEWAL_GRACE_DAYS)));

        Map<Long, Integer> hiddenPerRecipient = new LinkedHashMap<>();
        for (Listing listing : targets) {
            listing.hideForRegionClosure();
            alertRecipients.of(listing).forEach(recipient -> hiddenPerRecipient.merge(recipient, 1, Integer::sum));
        }
        // 중복 키는 닫힘 사건(지역 · 시각)과 사람마다 하나 — 같은 지역을 다시 열었다 닫으면 그때도 알린다.
        hiddenPerRecipient.forEach((recipient, count) -> notificationPublisher.publish(recipient,
                NotiType.LISTING_REGION_CLOSED,
                "LISTING_REGION_CLOSED:region:" + closed.getCode() + ":" + now + ":user:" + recipient,
                Map.of("0", count),
                "/broker/listings"));
        if (!targets.isEmpty()) {
            log.info("[listing] 서비스 지역이 닫혀 매물 {}건을 내렸다 region={} 알림={}명",
                    targets.size(), closed.getCode(), hiddenPerRecipient.size());
        }
    }
}
