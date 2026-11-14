package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// 하루 한 번이면 충분하다 — 파기는 날짜 단위 약속이고, 대기실은 며칠을 더 머물러도 된다.
//
// ⚠ 다중 인스턴스에서는 같은 행을 둘이 집을 수 있다. 이동 자체가 멱등이라(is_moved 검사 +
// 이미 격리 접두사면 마킹만) 피해는 없다. ShedLock 배선은 HUMAN_TODO 28이다.
//
// ★ 회차마다 200건씩만 집는다(리포지토리). 한 번에 다 비우려 하면 스토리지 왕복이 쌓여
//   새벽 한 시간을 통째로 먹는다. 쌓인 것이 많으면 며칠에 걸쳐 빠진다 — 급한 일이 아니다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class FileCleanupScheduler {

    private final FileCleanupProcessor processor;

    // zone 명시 — cron 트리거는 서버 TZ를 탄다. 컨테이너 TZ가 UTC여도 새벽 실행을 보장한다.
    @Scheduled(cron = "${file.cleanup.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        List<StoredFile> movable = processor.findMovable();
        if (movable.isEmpty()) {
            return;
        }

        int moved = 0;
        int failed = 0;
        for (StoredFile file : movable) {
            try {
                processor.move(file.getId());
                moved++;
            } catch (RuntimeException e) {
                // 한 건이 막혀도 나머지는 옮긴다. 다음 회차가 다시 집으므로 유실이 아니다.
                failed++;
                log.warn("[file] 격리 이동 실패 file={} key={}", file.getId(), file.getStorageKey(), e);
            }
        }
        log.info("[file] 격리 이동 {}건 성공 · {}건 실패 (대상 {}건)", moved, failed, movable.size());
    }
}
