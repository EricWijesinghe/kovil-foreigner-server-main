package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.PostCreateRequest;
import com.kovil.domain.community.dto.request.PostUpdateRequest;
import com.kovil.domain.community.dto.response.PostDetailResponse;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.CommunityPostAlias;
import com.kovil.domain.community.entity.CommunityPostImage;
import com.kovil.domain.community.entity.CommunityPostTag;
import com.kovil.domain.community.entity.CommunityTag;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityPostAliasRepository;
import com.kovil.domain.community.repository.CommunityPostImageRepository;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.CommunityPostTagRepository;
import com.kovil.domain.community.repository.CommunityTagRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.kovil.domain.community.exception.CommunityErrorCode.CATEGORY_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.IMAGE_LIMIT_EXCEEDED;
import static com.kovil.domain.community.exception.CommunityErrorCode.NOT_CONTENT_OWNER;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityPostService {

    private static final int MAX_IMAGES = 5;

    private final UserQueryService userQueryService;
    private final CommunityCategoryRepository categoryRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityPostAliasRepository aliasRepository;
    private final CommunityTagResolver tagResolver;
    private final CommunityTagRepository tagRepository;
    private final CommunityPostTagRepository postTagRepository;
    private final CommunityPostImageRepository imageRepository;
    private final FileDeletionService fileDeletionService;
    private final CommunityPostAssembler assembler;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional
    public PostDetailResponse create(String userUuid, PostCreateRequest request) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityCategory category = categoryRepository.findByCodeAndActiveTrue(request.categoryCode().trim())
                .orElseThrow(() -> new CommunityException(CATEGORY_NOT_FOUND));
        validateImages(request.imageUrls());

        // FR-707 — 언어는 요청이 아니라 작성 시점의 유저 설정에서 스냅샷한다.
        CommunityPost post = postRepository.save(CommunityPost.builder()
                .categoryId(category.getId())
                .userId(user.getId())
                .languageCode(user.getPreferredLanguage())
                .title(request.title().strip())
                .body(request.body())
                .status(CommunityContentStatus.ACTIVE)
                .build());

        // FR-704 — 글쓴이 별칭 0은 글 생성 시 미리 발급한다.
        aliasRepository.save(CommunityPostAlias.builder()
                .postId(post.getId()).userId(user.getId()).anonymousNo((short) 0).build());

        attachTags(post.getId(), tagResolver.resolve(request.tags()));
        attachImages(post.getId(), request.imageUrls());

        log.info("[community] 글 작성 post={} category={}", post.getUuid(), category.getCode());
        return assembler.toDetail(post, user.getId());
    }

    @Transactional
    public PostDetailResponse update(String userUuid, String postUuid, PostUpdateRequest request) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityPost post = getReadable(postUuid);
        if (!post.isOwnedBy(user.getId())) {
            throw new CommunityException(NOT_CONTENT_OWNER);
        }
        validateImages(request.imageUrls());

        post.edit(request.title().strip(), request.body());
        replaceTags(post.getId(), tagResolver.resolve(request.tags()));
        replaceImages(post.getId(), user.getId(), request.imageUrls());
        return assembler.toDetail(post, user.getId());
    }

    @Transactional
    public void delete(String userUuid, String postUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityPost post = getReadable(postUuid);
        if (!post.isOwnedBy(user.getId())) {
            throw new CommunityException(NOT_CONTENT_OWNER);
        }

        // 조건부 벌크 삭제 — 더블탭 동시 요청에서 태그 집계가 두 번 내려가면 안 된다.
        // 영향 행수가 1일 때만 집계를 되돌린다(적대적 리뷰 2026-09-02). 행은 남는다.
        if (postRepository.softDeleteIfVisible(post.getId(), LocalDateTime.now(clock)) == 1) {
            detachUsage(post.getId());
            // 글에 붙어 있던 사진도 버린다 — 표시만 하고 실제 이동·파기는 정리 배치가 한다.
            // 여기서 스토리지를 건드리면 이 트랜잭션이 롤백돼도 사진은 이미 사라진다.
            //
            // ★ **업로더 축을 반드시 실어야 한다.** image_url 은 사용자가 요청에 담아 보낸 값이고
            //   검증은 개수뿐이다(validateImages) — 남의 글에서 공개 URL 을 베껴 자기 글에 붙인 뒤
            //   그 글을 지우면 **원래 주인의 사진이 폐기된다**(적대적 리뷰 2026-09-23).
            discardOwnImages(post.getId(), user.getId());
            log.info("[community] 글 삭제 post={}", postUuid);
        }
    }

    private void validateImages(List<String> imageUrls) {
        if (imageUrls != null && imageUrls.size() > MAX_IMAGES) {
            throw new CommunityException(IMAGE_LIMIT_EXCEEDED);
        }
    }

    // 집계 증감은 벌크 UPDATE 다 — 더티체킹 +1 은 동시 글에서 카운트를 유실하고(lost update),
    // 발급기(REQUIRES_NEW)가 준 detached 태그에서는 조용히 증발한다(적대적 리뷰 2026-09-02).
    private void attachTags(Long postId, List<CommunityTag> tags) {
        tags.forEach(tag -> {
            postTagRepository.save(CommunityPostTag.builder().postId(postId).tagId(tag.getId()).build());
            tagRepository.increaseUsage(tag.getId());
        });
    }

    private void attachImages(Long postId, List<String> imageUrls) {
        if (imageUrls == null) {
            return;
        }
        for (int order = 0; order < imageUrls.size(); order++) {
            imageRepository.save(CommunityPostImage.builder()
                    .postId(postId).imageUrl(imageUrls.get(order)).sortOrder(order).build());
        }
    }

    // 같은 태그를 그대로 두는 수정이 가장 흔하다 — Hibernate는 INSERT를 DELETE보다 먼저
    // 플러시하므로 유니크(post_id, tag_id)가 터진다. 삭제를 먼저 밀어내고 다시 단다.
    private void replaceTags(Long postId, List<CommunityTag> nextTags) {
        List<CommunityPostTag> current = postTagRepository.findByPostId(postId);
        Set<Long> currentTagIds = current.stream().map(CommunityPostTag::getTagId).collect(Collectors.toSet());
        Set<Long> nextTagIds = nextTags.stream().map(CommunityTag::getId).collect(Collectors.toSet());

        // 떨어져 나가는 태그만 집계를 내린다. 그대로 남는 태그는 건드리지 않는다.
        currentTagIds.stream()
                .filter(tagId -> !nextTagIds.contains(tagId))
                .forEach(tagRepository::decreaseUsage);
        postTagRepository.deleteAll(current);
        entityManager.flush();

        nextTags.forEach(tag -> {
            postTagRepository.save(CommunityPostTag.builder().postId(postId).tagId(tag.getId()).build());
            if (!currentTagIds.contains(tag.getId())) {
                tagRepository.increaseUsage(tag.getId());
            }
        });
    }

    /*
     * ★ 떨어져 나간 사진은 **버려야 한다.** 행만 지우면 files 원장은 ACTIVE 로 남고 정리 배치가
     *   집지 않아 오브젝트가 영원히 본 디렉터리에 산다 — 글에서 뺐는데 URL 을 아는 사람은
     *   계속 볼 수 있다(공개 URL 이라 서명도 필요 없다).
     *
     *   남는 사진은 건드리지 않는다 — 순서만 바꾸거나 한 장만 빼는 수정이 가장 흔한데,
     *   통째로 버리면 살아 있는 글의 사진이 사라진다.
     */
    private void replaceImages(Long postId, Long ownerId, List<String> imageUrls) {
        List<CommunityPostImage> current = imageRepository.findByPostIdOrderBySortOrderAsc(postId);
        Set<String> next = imageUrls == null ? Set.of() : Set.copyOf(imageUrls);
        List<String> dropped = current.stream()
                .map(CommunityPostImage::getImageUrl)
                .filter(url -> !next.contains(url))
                .toList();

        imageRepository.deleteAll(current);
        entityManager.flush();
        attachImages(postId, imageUrls);
        // delete 와 같은 이유로 업로더 축이 실린다 — 남의 URL 을 붙였다 빼면 남의 파일이 지워진다.
        fileDeletionService.discardByUrlsOwnedBy(dropped, ownerId);
    }

    // 내가 올린 사진만 버린다. 남의 URL 을 붙여 둔 것은 표시만 사라지고 파일은 주인 곁에 남는다.
    private void discardOwnImages(Long postId, Long ownerId) {
        fileDeletionService.discardByUrlsOwnedBy(
                imageRepository.findByPostIdOrderBySortOrderAsc(postId).stream()
                        .map(CommunityPostImage::getImageUrl).toList(),
                ownerId);
    }

    private void detachUsage(Long postId) {
        postTagRepository.findByPostId(postId).forEach(row -> tagRepository.decreaseUsage(row.getTagId()));
    }

    private CommunityPost getReadable(String postUuid) {
        return postRepository.findByUuid(postUuid)
                .filter(CommunityPost::isReadable)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
    }
}
