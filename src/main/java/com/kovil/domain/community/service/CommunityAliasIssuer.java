package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityPostAlias;
import com.kovil.domain.community.repository.CommunityPostAliasRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// FR-704 — 한 글에서 한 사람은 항상 같은 번호. 동시 첫 댓글 경합은 DB 유니크가 막고
// 여기서 재발급해 재시도한다.
//
// ★ 재시도 루프는 트랜잭션 **밖**이고, 시도 1회마다 새 트랜잭션(REQUIRES_NEW)을 연다.
//   이전 판(메서드 전체 REQUIRES_NEW + 안에서 catch-재시도)은 유니크 위반이 그 트랜잭션을
//   rollback-only 로 만들어 재시도가 수렴 불가였다 — 패자는 재발급이 아니라 500 을 받았다
//   (적대적 리뷰 2026-09-02, 8라운드). 충돌은 트랜잭션이 닫힌 뒤 밖에서 잡고, 다음 시도의
//   새 트랜잭션(새 스냅샷)이 승자의 커밋을 보고 번호를 다시 계산한다.
//
// 별도 빈인 이유는 그대로다 — 댓글 트랜잭션의 오염을 막는 격리이고, 댓글이 나중에 실패해
// 별칭 행만 남아도 같은 유저가 다시 쓰면 재사용되므로 무해하다.
@Slf4j
@Component
public class CommunityAliasIssuer {

    private static final int MAX_ATTEMPTS = 3;

    private final CommunityPostAliasRepository aliasRepository;
    private final TransactionTemplate newTransaction;

    public CommunityAliasIssuer(CommunityPostAliasRepository aliasRepository,
                                PlatformTransactionManager transactionManager) {
        this.aliasRepository = aliasRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public short issue(Long postId, Long userId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Short issued = newTransaction.execute(status -> {
                    var existing = aliasRepository.findByPostIdAndUserId(postId, userId);
                    if (existing.isPresent()) {
                        return existing.get().getAnonymousNo();
                    }
                    short next = (short) (aliasRepository.findMaxAnonymousNo(postId).orElse((short) -1) + 1);
                    aliasRepository.saveAndFlush(CommunityPostAlias.builder()
                            .postId(postId).userId(userId).anonymousNo(next).build());
                    return next;
                });
                if (issued != null) {
                    return issued;
                }
            } catch (DataIntegrityViolationException e) {
                log.debug("[community] 별칭 경합 post={} attempt={}", postId, attempt);
            }
        }
        throw new IllegalStateException("별칭 발급이 연속 충돌했다 — post=" + postId);
    }
}
