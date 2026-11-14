package com.kovil.domain.notification.enums;

// SKIPPED는 실패가 아니다 — 기기가 없거나 카테고리를 껐다는 뜻이며 재시도하지 않는다.
// FAILED와 갈라 두지 않으면 재시도 대상이 계속 부풀어 오른다(FR-606).
//
// FAILED는 **재시도 대기**다. 정해진 횟수를 넘기면 GIVEN_UP으로 내려앉고 자동 재시도를 멈춘다 —
// 무한 재시도는 벤더 장애 때 큐를 태우고, 조용한 포기는 알림을 잃는다. 둘 다 피하려면
// **포기했다는 사실이 남고 사람이 볼 수 있어야** 한다.
public enum OutboxStatus {

    PENDING,
    SENT,
    FAILED,
    SKIPPED,
    GIVEN_UP;

    // 워커가 다시 집는 상태. SKIPPED·SENT·GIVEN_UP은 끝난 것이다.
    public boolean isRetryable() {
        return this == PENDING || this == FAILED;
    }
}
