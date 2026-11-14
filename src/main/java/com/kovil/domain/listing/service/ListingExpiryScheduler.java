package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.LongConsumer;

/*
 * 하루 한 번이면 충분하다 — 광고 기한은 날짜 단위이고, 몇 시간 더 떠 있는다고 해가 없다.
 *
 * ⚠ 다중 인스턴스에서는 같은 매물을 두 인스턴스가 집을 수 있다. 전이가 멱등이라(상태를
 *   다시 확인한다) 피해는 없고, 알림 중복은 dedupe_key 가 막는다. ShedLock 배선은
 *   다른 배치들과 같은 숙제로 남아 있다.
 *
 * ★ 만료를 먼저, 자동 종료를 나중에 돈다. 순서를 바꿔도 결과는 같지만 — 오늘 만료된 건이
 *   같은 회차에 종료되는 일은 없다(유예가 7일이다) — 로그를 읽을 때 생애 순서대로 나온다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "listing.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ListingExpiryScheduler {

    private final ListingExpiryProcessor processor;

    // zone 명시 — cron 트리거는 서버 TZ 를 탄다. 컨테이너 TZ 가 UTC 여도 새벽 실행을 보장한다.
    @Scheduled(cron = "${listing.expiry.cron:0 20 3 * * *}", zone = "Asia/Seoul")
    public void run() {
        runEach("만료", processor.findExpirable(), processor::expire);
        runEach("자동 종료", processor.findArchivable(), processor::archive);
    }

    private void runEach(String what, List<Listing> targets, LongConsumer action) {
        if (targets.isEmpty()) {
            return;
        }
        for (Listing listing : targets) {
            try {
                action.accept(listing.getId());
            } catch (RuntimeException e) {
                // 한 건의 실패가 나머지를 멈추면 안 된다 — 다음 회차에 다시 집힌다.
                log.warn("[listing] {} 실패 listing={}", what, listing.getId(), e);
            }
        }
        log.info("[listing] {} {}건", what, targets.size());
    }
}
