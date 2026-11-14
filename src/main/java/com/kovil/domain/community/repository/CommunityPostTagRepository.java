package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityPostTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommunityPostTagRepository extends JpaRepository<CommunityPostTag, Long> {

    List<CommunityPostTag> findByPostId(Long postId);
}
