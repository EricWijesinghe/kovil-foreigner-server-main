package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.response.PostDetailResponse;
import com.kovil.domain.community.dto.response.PostSummaryResponse;
import com.kovil.domain.community.entity.*;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityPostImageRepository;
import com.kovil.domain.community.repository.CommunityPostLikeRepository;
import com.kovil.domain.community.repository.CommunityPostTagRepository;
import com.kovil.domain.community.repository.CommunityTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.Set;

// INV-10 — 여기서 만드는 응답 어디에도 작성자 계정 정보가 없다. DTO에 필드 자체가 없다.
@Component
@RequiredArgsConstructor
public class CommunityPostAssembler {

    private static final int PREVIEW_LENGTH = 120;

    private final CommunityCategoryRepository categoryRepository;
    private final CommunityPostImageRepository imageRepository;
    private final CommunityPostLikeRepository postLikeRepository;
    private final CommunityPostTagRepository postTagRepository;
    private final CommunityTagRepository tagRepository;

    public PostDetailResponse toDetail(CommunityPost post, Long viewerId) {
        List<String> images = imageRepository.findByPostIdOrderBySortOrderAsc(post.getId()).stream()
                .map(CommunityPostImage::getImageUrl)
                .toList();
        List<String> tags = postTagRepository.findByPostId(post.getId()).stream()
                .map(CommunityPostTag::getTagId)
                .map(tagRepository::findById)
                .flatMap(Optional::stream)
                .map(CommunityTag::getName)
                .toList();
        return new PostDetailResponse(
                post.getUuid(), categoryCode(post.getCategoryId()), post.getTitle(), post.getBody(),
                images, tags, post.getViewCount(), post.getLikeCount(), post.getCommentCount(),
                post.getLanguageCode(), (short) 0, anonymousTag(post.getUuid()), post.isOwnedBy(viewerId),
                postLikeRepository.existsByPostIdAndUserId(post.getId(), viewerId),
                post.getCreatedAt(), post.getUpdatedAt());
    }

    // 작성자 표시 번호 — 글 uuid에서 파생한다. 계정 축 번호를 만들면 글 사이가 이어져
    // 전면 익명이 무너진다. 글마다 다른 무작위 번호가 "표시"와 "추적 불가"를 동시에 만족한다.
    // String.hashCode는 명세 고정이라 재기동·인스턴스 간에도 같은 값이 나온다.
    static int anonymousTag(String postUuid) {
        return Math.floorMod(postUuid.hashCode(), 900) + 100;
    }

    public List<PostSummaryResponse> toSummaries(List<CommunityPost> posts, Long viewerId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = posts.stream().map(CommunityPost::getId).toList();
        // 병합 함수는 보험이다 — 이미지 교체(delete→insert)가 경합하면 sortOrder=0 행이 잠깐
        // 2개일 수 있는데, 병합이 없으면 그 글이 포함된 모든 목록이 Duplicate key로 지속 500이
        // 된다(V9 유니크가 근본 방어, 여기는 마지막 안전망).
        Map<Long, String> thumbnails = imageRepository.findByPostIdInAndSortOrder(postIds, 0).stream()
                .collect(Collectors.toMap(CommunityPostImage::getPostId, CommunityPostImage::getImageUrl,
                        (first, second) -> first));
        Map<Long, String> categoryCodes = posts.stream().map(CommunityPost::getCategoryId).distinct()
                .collect(Collectors.toMap(Function.identity(), this::categoryCode));
        Set<Long> likedPostIds = postLikeRepository.findByUserIdAndPostIdIn(viewerId, postIds).stream()
                .map(CommunityPostLike::getPostId)
                .collect(Collectors.toSet());

        return posts.stream()
                .map(post -> new PostSummaryResponse(
                        post.getUuid(), categoryCodes.get(post.getCategoryId()), post.getTitle(),
                        preview(post.getBody()), thumbnails.get(post.getId()),
                        post.getViewCount(), post.getLikeCount(), post.getCommentCount(),
                        post.getLanguageCode(), anonymousTag(post.getUuid()), post.isOwnedBy(viewerId),
                        likedPostIds.contains(post.getId()), post.getCreatedAt()))
                .toList();
    }

    private String categoryCode(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .map(CommunityCategory::getCode)
                .orElse("UNKNOWN");
    }

    private String preview(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH);
    }
}
