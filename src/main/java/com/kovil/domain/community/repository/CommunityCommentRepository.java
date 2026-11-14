package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface CommunityCommentRepository extends JpaRepository<CommunityComment, Long> {

    // CommunityPostRepository의 좋아요 카운터와 같은 이유의 원자 UPDATE.
    @Modifying
    @Query("UPDATE CommunityComment c SET c.likeCount = c.likeCount + 1 WHERE c.id = :commentId")
    int increaseLikeCount(@Param("commentId") Long commentId);

    @Modifying
    @Query("UPDATE CommunityComment c SET c.likeCount = c.likeCount - 1 WHERE c.id = :commentId AND c.likeCount > 0")
    int decreaseLikeCount(@Param("commentId") Long commentId);

    Optional<CommunityComment> findByUuid(String uuid);

    List<CommunityComment> findByPostIdOrderByIdAsc(Long postId);

    /**
     * 조건부 소프트 삭제 — 영향 행수로 "이번 요청이 실제로 지웠는가"를 판정한다.
     *
     * ★ 더티체킹 삭제는 동시 더블탭·블라인드 경합에서 양쪽 다 성공으로 보여 comment_count 가
     *   이중 감소했다(적대적 리뷰 2026-09-02). 감산은 영향 행수가 1일 때만 한다.
     */
    @Modifying
    @Query(
            "update CommunityComment c set c.deletedAt = :now where c.id = :id and c.deletedAt is null")
    int softDeleteIfVisible(@Param("id") Long id,
                            @Param("now") LocalDateTime now);

    // 블라인드 두 단계: 미삭제 ACTIVE(표시 중 → 카운트 감산 대상)와 이미 삭제된 ACTIVE(감산 없음).
    @Modifying
    @Query("""
            update CommunityComment c
            set c.status = com.kovil.domain.community.enums.CommunityContentStatus.HIDDEN
            where c.id = :id
              and c.status = com.kovil.domain.community.enums.CommunityContentStatus.ACTIVE
              and c.deletedAt is null
            """)
    int blindIfVisible(@Param("id") Long id);

    @Modifying
    @Query("""
            update CommunityComment c
            set c.status = com.kovil.domain.community.enums.CommunityContentStatus.HIDDEN
            where c.id = :id
              and c.status = com.kovil.domain.community.enums.CommunityContentStatus.ACTIVE
            """)
    int blindRegardless(@Param("id") Long id);
}
