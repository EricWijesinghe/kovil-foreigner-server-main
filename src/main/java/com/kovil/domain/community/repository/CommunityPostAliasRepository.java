package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityPostAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommunityPostAliasRepository extends JpaRepository<CommunityPostAlias, Long> {

    Optional<CommunityPostAlias> findByPostIdAndUserId(Long postId, Long userId);

    @Query("SELECT MAX(a.anonymousNo) FROM CommunityPostAlias a WHERE a.postId = :postId")
    Optional<Short> findMaxAnonymousNo(@Param("postId") Long postId);
}
