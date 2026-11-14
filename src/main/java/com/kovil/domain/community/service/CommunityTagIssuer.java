package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityTag;
import com.kovil.domain.community.repository.CommunityTagRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 신규 태그 발급 — 동시 두 글이 같은 태그를 처음 달면 유니크(uq_ctag_normalized)가 막고
// 여기서 재조회로 수렴한다. find-then-save 를 글 트랜잭션 안에서 하면 패자의 충돌이
// 글 작성 전체를 500 으로 죽였다(적대적 리뷰 2026-09-02).
//
// ★ 재시도 루프는 트랜잭션 **밖**이고, 시도 1회마다 새 트랜잭션(REQUIRES_NEW)을 연다.
//   메서드 전체를 REQUIRES_NEW 로 감싸고 안에서 catch-재시도하면 안 된다 — 유니크 위반이
//   그 트랜잭션을 rollback-only 로 만들어, 2차 시도는 (RR 스냅샷이 낡아) 승자의 행을 못 보고
//   같은 충돌을 반복하다 소진되거나 커밋에서 죽는다(8라운드가 7라운드 수정에서 실증).
//   충돌 예외는 트랜잭션이 닫힌 뒤 밖에서 잡고, 다음 시도의 새 트랜잭션(새 스냅샷)이 재조회한다.
//
// ★ 반환 엔티티는 호출자 트랜잭션 기준 detached 다 — 집계 증감은 벌크 UPDATE 로만 할 것.
@Slf4j
@Component
public class CommunityTagIssuer {

    private static final int MAX_ATTEMPTS = 3;

    private final CommunityTagRepository tagRepository;
    private final TransactionTemplate newTransaction;

    public CommunityTagIssuer(CommunityTagRepository tagRepository,
                              PlatformTransactionManager transactionManager) {
        this.tagRepository = tagRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CommunityTag issue(String display, String normalized) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return newTransaction.execute(status ->
                        tagRepository.findByNameNormalized(normalized).orElseGet(() ->
                                tagRepository.saveAndFlush(CommunityTag.builder()
                                        .name(display).nameNormalized(normalized).usageCount(0L).build())));
            } catch (DataIntegrityViolationException e) {
                log.debug("[community] 태그 경합 tag={} attempt={}", normalized, attempt);
            }
        }
        throw new IllegalStateException("태그 발급이 연속 충돌했다 — " + normalized);
    }
}
