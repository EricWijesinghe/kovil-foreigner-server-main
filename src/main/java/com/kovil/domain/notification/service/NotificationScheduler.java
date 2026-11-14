package com.kovil.domain.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 워커를 주기적으로 깨운다. 스케줄러와 발송 로직을 갈라 둔 이유는
// **테스트가 시간에 기대지 않고 발송을 검증할 수 있어야** 하기 때문이다.
//
// ⚠ 다중 인스턴스에서는 같은 행을 두 인스턴스가 집을 수 있다.
// 스키마에 shedlock 테이블이 있지만 아직 배선하지 않았다 — 단일 인스턴스 전제다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {

    private final NotificationWorker worker;

    @Scheduled(fixedDelayString = "${notification.worker.fixed-delay:30000}")
    public void run() {
        int processed = worker.drain();
        if (processed > 0) {
            log.info("[notification] {}건 발송 처리", processed);
        }
    }
}
