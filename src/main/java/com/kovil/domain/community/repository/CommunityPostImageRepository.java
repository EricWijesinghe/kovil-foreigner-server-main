package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityPostImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CommunityPostImageRepository extends JpaRepository<CommunityPostImage, Long> {

    List<CommunityPostImage> findByPostIdOrderBySortOrderAsc(Long postId);

    // 목록 썸네일 축 — sort_order = 0 한 장씩만 집는다.
    List<CommunityPostImage> findByPostIdInAndSortOrder(Collection<Long> postIds, int sortOrder);
}
