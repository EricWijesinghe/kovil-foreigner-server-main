package com.kovil.domain.community.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "community.view-sync.enabled", havingValue = "true", matchIfMissing = true)
public class CommunityViewSyncScheduler {

    private final CommunityViewCountBuffer buffer;
    private final CommunityViewSyncService syncService;

    @Scheduled(fixedDelayString = "${community.view-sync.delay-ms:60000}")
    public void run() {
        Map<Long, Long> deltas = buffer.drain();
        if (deltas.isEmpty()) {
            return;
        }
        deltas.forEach((postId, delta) -> {
            try {
                syncService.apply(postId, delta);
            } catch (RuntimeException e) {
                buffer.restore(postId, delta);
                log.warn("[community] 조회수 가산 실패 post={} delta={}", postId, delta, e);
            }
        });
        log.debug("[community] 조회수 가산 {}건", deltas.size());
    }
}
