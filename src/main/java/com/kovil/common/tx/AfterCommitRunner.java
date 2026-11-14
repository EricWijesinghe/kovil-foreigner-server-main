package com.kovil.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 커밋 이후에만 실행해야 하는 정리 작업(주로 Redis 키 삭제)의 단일 통로.
 *
 * ★ 트랜잭션 안에서 Redis 를 지우면 롤백이 되돌려 주지 않는다 — DB 는 되돌아갔는데 키만
 *   사라져 사용자의 재시도가 끊긴다(실측 2026-09-02: 소셜 통합이 토큰을 지운 뒤
 *   OAUTH_EMAIL_REQUIRED 로 실패하면 인증부터 다시 해야 했다).
 *
 * ★ afterCommit 안의 예외는 삼킨다. 커밋은 이미 성공했는데 여기서 던지면 Spring 이 예외를
 *   호출자까지 올려 보내 "가입은 됐는데 응답은 500" 이 된다 — 계정과 토큰이 만들어졌는데
 *   클라이언트는 실패로 처리하고, 재시도는 VERIFICATION_ALREADY_USED·DUPLICATE_PHONE 로
 *   더 꼬인다. 정리 실패의 대가는 TTL 까지 남는 죽은 키뿐이라 그 교환은 성립하지 않는다.
 *
 * 트랜잭션 동기화가 없으면 즉시 실행한다(비트랜잭션 경로). 그때도 삼킨다 —
 * 정리가 본류를 죽여선 안 된다는 계약은 같다.
 */
@Slf4j
public final class AfterCommitRunner {

    private AfterCommitRunner() {
    }

    public static void run(String what, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(what, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runQuietly(what, action);
            }
        });
    }

    private static void runQuietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("[tx] 커밋 후 정리 실패(무시하고 진행): {}", what, e);
        }
    }
}
