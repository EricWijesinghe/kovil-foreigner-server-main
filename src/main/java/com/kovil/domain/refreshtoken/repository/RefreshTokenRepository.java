package com.kovil.domain.refreshtoken.repository;

import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * rotate 의 만료 사전 검사 전용 — **엔티티를 영속성 컨텍스트에 올리지 않는** 프로젝션이다.
     *
     * ★ 엔티티로 미리 읽으면 안 된다. 그 인스턴스가 영속성 컨텍스트에 남아, 뒤의 잠금 조회가
     *   DB 를 current read 로 다시 읽고도 **캐시된 낡은 인스턴스**를 돌려준다 — 그 사이 다른
     *   rotate 가 커밋한 REVOKED 가 안 보여 재사용 탐지가 조용히 무력화된다.
     */
    @Query("""
            select t.id as id, t.status as status, t.expiresAt as expiresAt
            from RefreshToken t where t.tokenHash = :tokenHash
            """)
    Optional<RotationPeek> peekByTokenHash(@Param("tokenHash") String tokenHash);

    interface RotationPeek {
        Long getId();
        RefreshTokenStatus getStatus();
        LocalDateTime getExpiresAt();
    }

    /**
     * EXPIRED 마킹 — **ACTIVE→EXPIRED 전이만 허용하는 조건부 벌크 UPDATE 다.**
     *
     * ★ 읽고-쓰면(findById → entity.expire) 안 된다. 그 읽기와 커밋 사이 밀리초 창에서
     *   revokeAllFor(재설정·탈퇴·정지)나 로그아웃이 같은 행을 REVOKED 로 커밋하면, 무조건
     *   대입이 REVOKED 를 EXPIRED 로 덮어써 재사용 탐지(도난 대응) 신호가 지워진다
     *   (적대적 리뷰 2026-09-02). WHERE status=ACTIVE 는 그 어떤 인터리빙에서도 안전하다.
     */
    @Modifying
    @Query("""
            update RefreshToken t
            set t.status = com.kovil.domain.refreshtoken.enums.RefreshTokenStatus.EXPIRED,
                t.revokedAt = :now
            where t.id = :id
              and t.status = com.kovil.domain.refreshtoken.enums.RefreshTokenStatus.ACTIVE
            """)
    int expireIfActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    // rotate 전용 — 같은 토큰의 동시 회전을 행 락으로 직렬화한다. 무락이면 둘 다 ACTIVE
    // 스냅숏을 읽어 세션이 2개로 분열하고, "폐기 토큰 재사용 감지"가 영영 안 울린다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 회전 계보의 자식 — 유예 창 판정에서 "자식이 아직 안 쓰였나"를 잠근 채 본다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.parentToken.id = :parentId")
    List<RefreshToken> findChildrenForUpdate(@Param("parentId") Long parentId);

    // 세션 상한을 줄일 때 동시 발급과 경합한다 — 잠그지 않으면 상한을 넘겨 발급된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt "
            + "WHERE rt.user.id = :userId AND rt.status = :status "
            + "ORDER BY rt.issuedAt ASC")
    List<RefreshToken> findByUserIdAndStatusForUpdate(
            @Param("userId") Long userId, @Param("status") RefreshTokenStatus status);
}
