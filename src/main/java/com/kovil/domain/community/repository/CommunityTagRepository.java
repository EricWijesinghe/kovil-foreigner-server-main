package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommunityTagRepository extends JpaRepository<CommunityTag, Long> {

    Optional<CommunityTag> findByNameNormalized(String nameNormalized);

    /**
     * 집계 증감은 **벌크 UPDATE 로만** 한다. 더티체킹 증감(managed 엔티티 +1)은 두 함정이 있다:
     * ① 동시 두 글이 같은 값을 읽고 같은 값을 써 카운트가 유실된다(lost update).
     * ② 태그가 REQUIRES_NEW 발급기(CommunityTagIssuer)에서 온 detached 엔티티면 증감이
     *   조용히 증발한다. 벌크 UPDATE 는 둘 다 원자적으로 처리한다(적대적 리뷰 2026-09-02).
     */
    @Modifying
    @Query(
            "update CommunityTag t set t.usageCount = t.usageCount + 1 where t.id = :id")
    int increaseUsage(@Param("id") Long id);

    @Modifying
    @Query(
            "update CommunityTag t set t.usageCount = case when t.usageCount > 0 then t.usageCount - 1 else 0 end where t.id = :id")
    int decreaseUsage(@Param("id") Long id);
}
