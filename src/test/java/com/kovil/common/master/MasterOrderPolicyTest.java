package com.kovil.common.master;

import com.kovil.common.exception.MasterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 7축이 전부 이 규칙 하나를 쓴다 — 여기가 뚫리면 7축이 같이 뚫린다.
// 부분 반영 없이 전체 거부(409)여야 "관리자가 본 순서 = 저장된 순서"가 유지된다.
class MasterOrderPolicyTest {

    private static void expectMismatch(List<Long> scope, List<Long> requested) {
        assertThatThrownBy(() -> MasterOrderPolicy.requireExactPermutation(scope, requested))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_ORDER_SCOPE_MISMATCH");
    }

    @Test
    @DisplayName("완전한 순열이면 통과한다 — 순서만 다른 것은 정확히 의도된 입력이다")
    void exact_permutation_passes() {
        assertThatCode(() -> MasterOrderPolicy.requireExactPermutation(
                List.of(1L, 2L, 3L), List.of(3L, 1L, 2L))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("누락 — 다른 관리자가 방금 행을 추가한 경합이다")
    void missing_id_is_rejected() {
        expectMismatch(List.of(1L, 2L, 3L), List.of(3L, 1L));
    }

    @Test
    @DisplayName("여분 — 목록에 없는 id")
    void extra_id_is_rejected() {
        expectMismatch(List.of(1L, 2L), List.of(1L, 2L, 9L));
    }

    // 크기 비교만으로는 [1,1] vs [1,2]를 놓친다 — Set 크기 축소로 잡는 경로.
    @Test
    @DisplayName("중복 — 같은 id 두 번이면 크기가 맞아도 거부한다")
    void duplicate_id_is_rejected() {
        expectMismatch(List.of(1L, 2L), List.of(1L, 1L));
    }

    @Test
    @DisplayName("중복+여분 조합 — 크기까지 같아도 거부한다")
    void duplicate_masking_missing_is_rejected() {
        expectMismatch(List.of(1L, 2L, 3L), List.of(1L, 1L, 2L));
    }
}
