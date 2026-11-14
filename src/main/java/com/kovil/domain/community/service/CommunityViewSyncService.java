package com.kovil.domain.community.service;

import com.kovil.domain.community.repository.CommunityPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommunityViewSyncService {

    private final CommunityPostRepository postRepository;

    @Transactional
    public void apply(Long postId, long delta) {
        postRepository.increaseViewCount(postId, delta);
    }
}
