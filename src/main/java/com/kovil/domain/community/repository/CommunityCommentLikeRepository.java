package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityCommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityCommentLikeRepository extends JpaRepository<CommunityCommentLike, Long> {

    Optional<CommunityCommentLike> findByCommentIdAndUserId(Long commentId, Long userId);

    // CommunityPostLikeRepository.insertIgnore와 같은 이유.
    @Modifying
    @Query(value = "INSERT IGNORE INTO community_comment_likes (comment_id, user_id) VALUES (:commentId, :userId)",
            nativeQuery = true)
    int insertIgnore(@Param("commentId") Long commentId, @Param("userId") Long userId);

    // CommunityPostLikeRepository.deleteByPostIdAndUserId와 같은 이유의 벌크 DELETE.
    @Modifying
    @Query("DELETE FROM CommunityCommentLike l WHERE l.commentId = :commentId AND l.userId = :userId")
    int deleteByCommentIdAndUserId(@Param("commentId") Long commentId, @Param("userId") Long userId);

    List<CommunityCommentLike> findByUserIdAndCommentIdIn(Long userId, Collection<Long> commentIds);
}
