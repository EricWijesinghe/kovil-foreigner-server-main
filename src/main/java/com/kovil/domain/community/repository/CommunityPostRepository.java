package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.enums.CommunityContentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    Optional<CommunityPost> findByUuid(String uuid);

    boolean existsByCategoryId(Long categoryId);

    long countByUserIdAndDeletedAtIsNull(Long userId);

    @Query("SELECT p FROM CommunityPost p "
            + "WHERE p.status = :status AND p.deletedAt IS NULL "
            + "AND (:categoryId IS NULL OR p.categoryId = :categoryId) "
            + "AND (:languageCode IS NULL OR p.languageCode = :languageCode) "
            + "AND (:q IS NULL OR p.title LIKE CONCAT('%', :q, '%') OR p.body LIKE CONCAT('%', :q, '%')) "
            + "AND p.userId NOT IN (SELECT b.blockedUserId FROM UserBlock b WHERE b.blockerId = :viewerId) "
            + "ORDER BY p.id DESC")
    Page<CommunityPost> findFeed(@Param("status") CommunityContentStatus status,
                                 @Param("categoryId") Long categoryId,
                                 @Param("languageCode") String languageCode,
                                 @Param("q") String q,
                                 @Param("viewerId") Long viewerId,
                                 Pageable pageable);

    @Query("SELECT p FROM CommunityPost p "
            + "JOIN CommunityPostTag pt ON pt.postId = p.id "
            + "JOIN CommunityTag t ON t.id = pt.tagId "
            + "WHERE t.nameNormalized = :tag "
            + "AND p.status = :status AND p.deletedAt IS NULL "
            + "AND (:categoryId IS NULL OR p.categoryId = :categoryId) "
            + "AND (:languageCode IS NULL OR p.languageCode = :languageCode) "
            + "AND (:q IS NULL OR p.title LIKE CONCAT('%', :q, '%') OR p.body LIKE CONCAT('%', :q, '%')) "
            + "AND p.userId NOT IN (SELECT b.blockedUserId FROM UserBlock b WHERE b.blockerId = :viewerId) "
            + "ORDER BY p.id DESC")
    Page<CommunityPost> findFeedByTag(@Param("status") CommunityContentStatus status,
                                      @Param("tag") String tagNormalized,
                                      @Param("categoryId") Long categoryId,
                                      @Param("languageCode") String languageCode,
                                      @Param("q") String q,
                                      @Param("viewerId") Long viewerId,
                                      Pageable pageable);

    Page<CommunityPost> findByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId, Pageable pageable);

    Page<CommunityPost> findByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
            Long userId, CommunityContentStatus status,
            Pageable pageable);

    @Modifying
    @Query("UPDATE CommunityPost p SET p.viewCount = p.viewCount + :delta WHERE p.id = :postId")
    int increaseViewCount(@Param("postId") Long postId, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE CommunityPost p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
    int increaseLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE CommunityPost p SET p.likeCount = p.likeCount - 1 WHERE p.id = :postId AND p.likeCount > 0")
    int decreaseLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE CommunityPost p SET p.commentCount = p.commentCount + 1 WHERE p.id = :postId")
    int increaseCommentCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE CommunityPost p SET p.commentCount = p.commentCount - 1 WHERE p.id = :postId AND p.commentCount > 0")
    int decreaseCommentCount(@Param("postId") Long postId);

    @Query(value = """
            SELECT p.id FROM community_posts p
            WHERE p.deleted_at IS NULL
              AND p.status = :status
              AND p.created_at >= :since
              AND (:categoryId IS NULL OR p.category_id = :categoryId)
              AND (:languageCode IS NULL OR p.language_code = :languageCode)
            ORDER BY (p.view_count + p.like_count * :likeWeight + p.comment_count * :commentWeight)
                     / POW(TIMESTAMPDIFF(MINUTE, p.created_at, NOW()) / 60.0 + 2, :decayExponent) DESC,
                     p.id DESC
            LIMIT :poolSize
            """, nativeQuery = true)
    List<Long> findPopularIds(@Param("status") String status,
                              @Param("since") LocalDateTime since,
                              @Param("categoryId") Long categoryId,
                              @Param("languageCode") String languageCode,
                              @Param("likeWeight") double likeWeight,
                              @Param("commentWeight") double commentWeight,
                              @Param("decayExponent") double decayExponent,
                              @Param("poolSize") int poolSize);

    @Modifying
    @Query(
            "update CommunityPost p set p.deletedAt = :now where p.id = :id and p.deletedAt is null")
    int softDeleteIfVisible(@Param("id") Long id,
                            @Param("now") LocalDateTime now);
}
