package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.BlockedIdentity;
import com.kovil.domain.user.enums.BlockedIdentityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BlockedIdentityRepository extends JpaRepository<BlockedIdentity, Long> {

    /**
     * 활성 차단이 하나라도 있으면 가입을 거부한다.
     *
     * ★ 판정 조건은 **DB 의 active_guard 생성 컬럼과 글자 그대로 같아야 한다** — 둘 다 released_at 만 본다.
     *   어긋나면 앱은 "차단 없음"이라 보고 insert 하는데 DB 는 슬롯이 찼다며 거부해 500 이 난다.
     *   차단은 전부 영구다(PO 확정 2026-09-01). 만료 조건을 여기 더하려면 active_guard 도 함께 고칠 것.
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM BlockedIdentity b
            WHERE b.identityType = :type AND b.identityValue = :value
              AND b.releasedAt IS NULL
            """)
    boolean existsActive(@Param("type") BlockedIdentityType type,
                         @Param("value") String value);

    /**
     * 멱등 삽입 — 동시 이중 차단의 유니크 충돌(uq_bi_active)을 예외 없이 0행으로 받는다.
     *
     * ★ catch 로 잡으면 안 된다. 유니크 위반은 트랜잭션을 rollback-only 로 만들어서, 삼키고
     *   진행해도 커밋이 UnexpectedRollbackException 으로 죽는다 — 정지 자체가 함께 롤백된다.
     *   CommunityBlockService·CommunityLikeService 가 같은 수리를 이미 거쳤다.
     */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO blocked_identities
                (identity_type, identity_value, reason, source_user_id, blocked_by, blocked_at)
            VALUES (:type, :value, :reason, :sourceUserId, :blockedBy, :blockedAt)
            """, nativeQuery = true)
    int insertIgnore(@Param("type") String type,
                     @Param("value") String value,
                     @Param("reason") String reason,
                     @Param("sourceUserId") Long sourceUserId,
                     @Param("blockedBy") Long blockedBy,
                     @Param("blockedAt") LocalDateTime blockedAt);

    /** 정지 해제 시 그 계정에서 비롯된 활성 차단을 함께 푼다. */
    @Query("""
            SELECT b FROM BlockedIdentity b
            WHERE b.sourceUserId = :userId AND b.releasedAt IS NULL
            """)
    List<BlockedIdentity> findActiveBySourceUserId(@Param("userId") Long userId);
}
