package com.kovil.common.master;

import com.kovil.common.exception.MasterErrorCode;
import com.kovil.common.exception.MasterException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 재정렬 요청의 순열 검증 — 7축이 전부 같은 규칙을 쓴다.
 *
 * <p>ids는 스코프(그 축의 전 행, 트리 축은 같은 부모의 자식 전체)의 **완전한 순열**이어야 한다.
 * 누락·여분·중복 어느 것이든 {@code MASTER_ORDER_SCOPE_MISMATCH}(409)로 전체 거부한다 —
 * 부분 반영은 "관리자가 본 순서"와 "저장된 순서"가 다른 상태를 만든다.
 */
public final class MasterOrderPolicy {

    private MasterOrderPolicy() {
    }

    public static void requireExactPermutation(List<Long> scopeIds, List<Long> requestedIds) {
        // 중복은 Set 크기가 줄어드는 것으로 잡힌다 — 크기 비교만으로는 [1,1] vs [1,2]를 놓친다.
        Set<Long> requested = new HashSet<>(requestedIds);
        if (requested.size() != requestedIds.size()
                || requested.size() != scopeIds.size()
                || !requested.containsAll(scopeIds)) {
            throw new MasterException(MasterErrorCode.MASTER_ORDER_SCOPE_MISMATCH);
        }
    }
}
