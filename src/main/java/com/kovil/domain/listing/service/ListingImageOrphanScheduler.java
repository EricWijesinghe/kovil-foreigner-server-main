package com.kovil.domain.listing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// 파일 정리 배치(04:30)보다 앞 — 같은 밤에 격리 이동까지 끝남. 회차당 200건, 많이 쌓였으면 며칠에 걸쳐 빠짐.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.orphan.listing-image.enabled", havingValue = "true", matchIfMissing = true)
public class ListingImageOrphanScheduler {

    private final ListingImageOrphanProcessor processor;

    @Scheduled(cron = "${file.orphan.listing-image.cron:0 10 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        List<Long> candidates = processor.findCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        int discarded = 0;
        for (Long fileId : candidates) {
            try {
                if (processor.discardIfUnattached(fileId)) {
                    discarded++;
                }
            } catch (RuntimeException e) {
                // 한 건이 막혀도 나머지는 계속. 다음 회차가 다시 집음.
                log.warn("[listing-image] 미첨부 사진 폐기 실패 file={}", fileId, e);
            }
        }
        log.info("[listing-image] 미첨부 사진 폐기 {}건 (대상 {}건)", discarded, candidates.size());
    }
}
