package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityPostAlias;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kovil.domain.community.repository.CommunityPostAliasRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// FR-704 — 한 글에서 한 사람은 항상 같은 번호. 경합은 유니크 충돌 후 재시도로 푼다.
@ExtendWith(MockitoExtension.class)
class CommunityAliasIssuerTest {

    @Mock CommunityPostAliasRepository aliasRepository;
    // 목 트랜잭션 매니저 — TransactionTemplate 은 실물로 돌리되 실제 트랜잭션은 없다.
    // 시도-단위 트랜잭션 경계(rollback-only 격리)는 목으로 재현 불가 — 구조가 근거를 진다.
    @Mock PlatformTransactionManager transactionManager;

    private CommunityAliasIssuer issuer() {
        return new CommunityAliasIssuer(aliasRepository, transactionManager);
    }

    private static CommunityPostAlias alias(short no) {
        return CommunityPostAlias.builder().postId(1L).userId(10L).anonymousNo(no).build();
    }

    @Test
    @DisplayName("이미 받은 번호가 있으면 그대로 재사용한다")
    void existing_alias_is_reused() {
        given(aliasRepository.findByPostIdAndUserId(1L, 10L)).willReturn(Optional.of(alias((short) 3)));

        assertThat(issuer().issue(1L, 10L)).isEqualTo((short) 3);
        then(aliasRepository).should(Mockito.never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("처음이면 MAX+1을 받는다 — 글쓴이 0 다음의 첫 댓글러는 1이다")
    void first_commenter_gets_next_number() {
        given(aliasRepository.findByPostIdAndUserId(1L, 10L)).willReturn(Optional.empty());
        given(aliasRepository.findMaxAnonymousNo(1L)).willReturn(Optional.of((short) 0));
        given(aliasRepository.saveAndFlush(any())).willAnswer(call -> call.getArgument(0));

        assertThat(issuer().issue(1L, 10L)).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("동시 경합으로 유니크가 터지면 재조회해서 푼다")
    void collision_is_retried() {
        given(aliasRepository.findByPostIdAndUserId(1L, 10L))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(alias((short) 2)));
        given(aliasRepository.findMaxAnonymousNo(1L)).willReturn(Optional.of((short) 1));
        given(aliasRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uq_cpa_user"));

        assertThat(issuer().issue(1L, 10L)).isEqualTo((short) 2);
    }
}
