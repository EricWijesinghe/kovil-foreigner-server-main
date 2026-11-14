package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityPostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityPostLikeRepository extends JpaRepository<CommunityPostLike, Long> {

    Optional<CommunityPostLike> findByPostIdAndUserId(Long postId, Long userId);

    // 멱등 삽입 — 유니크 충돌을 예외가 아니라 0행으로 받는다. 같은 트랜잭션에서 제약 위반을
    // catch로 삼키면 rollback-only가 걸려 커밋이 500으로 죽는다(AliasIssuer가 박제한 함정).
    // ⚠ MySQL 전용 문법(H2 순정 미지원) + IGNORE는 FK 위반도 경고로 강등한다 — 호출 전에
    // 대상 엔티티를 반드시 로드했을 것. @DataJpaTest(H2)에 이 쿼리를 태우면 터진다.
    @Modifying
    @Query(value = "INSERT IGNORE INTO community_post_likes (post_id, user_id) VALUES (:postId, :userId)",
            nativeQuery = true)
    int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);

    // 벌크 DELETE — 파생 delete는 load-then-remove라 동시 취소의 패자가 0행 삭제에서
    // StaleStateException(500)으로 죽는다. 벌크는 영향 행수만 돌려준다(insertIgnore와 짝).
    @Modifying
    @Query("DELETE FROM CommunityPostLike l WHERE l.postId = :postId AND l.userId = :userId")
    int deleteByPostIdAndUserId(@Param("postId") Long postId, @Param("userId") Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 목록의 likedByMe 축 — 페이지 단위 일괄 조회.
    List<CommunityPostLike> findByUserIdAndPostIdIn(Long userId, Collection<Long> postIds);
}
