package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.CommentCreateRequest;
import com.kovil.domain.community.dto.request.CommentUpdateRequest;
import com.kovil.domain.community.dto.response.CommentResponse;
import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityCommentLike;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentLikeRepository;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.UserBlockRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_DEPTH_EXCEEDED;
import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_PARENT_MISMATCH;
import static com.kovil.domain.community.exception.CommunityErrorCode.NOT_CONTENT_OWNER;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityCommentService {

    private static final int TITLE_SNIPPET = 30;

    private final UserQueryService userQueryService;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityCommentLikeRepository commentLikeRepository;
    private final UserBlockRepository blockRepository;
    private final CommunityAliasIssuer aliasIssuer;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional
    public CommentResponse create(String userUuid, String postUuid, CommentCreateRequest request) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityPost post = getReadablePost(postUuid);
        Long parentId = resolveParentId(post, request.parentCommentUuid());

        short anonymousNo = aliasIssuer.issue(post.getId(), user.getId());

        CommunityComment comment = commentRepository.save(CommunityComment.builder()
                .postId(post.getId())
                .parentCommentId(parentId)
                .userId(user.getId())
                .anonymousNo(anonymousNo)
                .body(request.body().strip())
                .status(CommunityContentStatus.ACTIVE)
                .build());
        postRepository.increaseCommentCount(post.getId());

        notifyPostAuthor(post, comment, user);
        return toResponse(comment, post, user.getId(), false, List.of());
    }

    @Transactional
    public CommentResponse update(String userUuid, String commentUuid, CommentUpdateRequest request) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityComment comment = getVisible(commentUuid);
        if (!comment.isOwnedBy(user.getId())) {
            throw new CommunityException(NOT_CONTENT_OWNER);
        }
        comment.edit(request.body().strip());
        CommunityPost post = postRepository.findById(comment.getPostId()).orElseThrow();
        boolean likedByMe = commentLikeRepository
                .findByCommentIdAndUserId(comment.getId(), user.getId()).isPresent();
        return toResponse(comment, post, user.getId(), likedByMe, List.of());
    }

    @Transactional
    public void delete(String userUuid, String commentUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityComment comment = getVisible(commentUuid);
        if (!comment.isOwnedBy(user.getId())) {
            throw new CommunityException(NOT_CONTENT_OWNER);
        }
        // 조건부 벌크 삭제 — 더블탭·블라인드 경합에서 감산이 두 번 돌면 안 된다. 영향 행수가
        // 1일 때만 이번 요청의 삭제로 인정하고 감산한다(적대적 리뷰 2026-09-02).
        if (commentRepository.softDeleteIfVisible(comment.getId(), LocalDateTime.now(clock)) == 1) {
            postRepository.decreaseCommentCount(comment.getPostId());
        }
    }

    // FR-706 — 자식이 있는 삭제 댓글은 자리로 남긴다. 스레드가 끊기면 대화가 안 읽힌다.
    @Transactional(readOnly = true)
    public List<CommentResponse> findThread(String userUuid, String postUuid) {
        User viewer = userQueryService.getActiveUser(userUuid);
        CommunityPost post = getReadablePost(postUuid);
        List<CommunityComment> all = commentRepository.findByPostIdOrderByIdAsc(post.getId());
        // FR-713 — 차단한 사람의 댓글은 삭제와 같은 규칙으로 사라진다(자식 있으면 자리만).
        Set<Long> blocked = blockRepository.findBlockedUserIds(viewer.getId());
        Set<Long> likedCommentIds = commentLikeRepository
                .findByUserIdAndCommentIdIn(viewer.getId(), all.stream().map(CommunityComment::getId).toList())
                .stream().map(CommunityCommentLike::getCommentId)
                .collect(Collectors.toSet());

        Map<Long, List<CommunityComment>> childrenByParent = new LinkedHashMap<>();
        List<CommunityComment> tops = new ArrayList<>();
        for (CommunityComment comment : all) {
            if (comment.isTopLevel()) {
                tops.add(comment);
            } else {
                childrenByParent.computeIfAbsent(comment.getParentCommentId(), key -> new ArrayList<>())
                        .add(comment);
            }
        }

        List<CommentResponse> thread = new ArrayList<>();
        for (CommunityComment top : tops) {
            List<CommentResponse> children = childrenByParent
                    .getOrDefault(top.getId(), List.of()).stream()
                    .filter(CommunityComment::isVisible)
                    .filter(child -> !blocked.contains(child.getUserId()))
                    .map(child -> toResponse(child, post, viewer.getId(),
                            likedCommentIds.contains(child.getId()), List.of()))
                    .toList();
            if (top.isVisible() && !blocked.contains(top.getUserId())) {
                thread.add(toResponse(top, post, viewer.getId(),
                        likedCommentIds.contains(top.getId()), children));
            } else if (!children.isEmpty()) {
                thread.add(placeholder(top, children));
            }
        }
        return thread;
    }

    private CommunityPost getReadablePost(String postUuid) {
        return postRepository.findByUuid(postUuid)
                .filter(CommunityPost::isReadable)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
    }

    private CommunityComment getVisible(String commentUuid) {
        return commentRepository.findByUuid(commentUuid)
                .filter(CommunityComment::isVisible)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
    }

    private Long resolveParentId(CommunityPost post, String parentCommentUuid) {
        if (parentCommentUuid == null || parentCommentUuid.isBlank()) {
            return null;
        }
        CommunityComment parent = commentRepository.findByUuid(parentCommentUuid.strip())
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
        if (!parent.getPostId().equals(post.getId())) {
            throw new CommunityException(COMMENT_PARENT_MISMATCH);
        }
        // FR-705 — 깊이 2 고정. 부모가 이미 누군가의 자식이면 거부한다.
        if (!parent.isTopLevel()) {
            throw new CommunityException(COMMENT_DEPTH_EXCEEDED);
        }
        return parent.getId();
    }

    // 자기 글에 자기가 단 댓글은 알리지 않는다.
    private void notifyPostAuthor(CommunityPost post, CommunityComment comment, User commenter) {
        if (post.isOwnedBy(commenter.getId())) {
            return;
        }
        String snippet = post.getTitle().length() <= TITLE_SNIPPET
                ? post.getTitle() : post.getTitle().substring(0, TITLE_SNIPPET) + "…";
        notificationPublisher.publish(post.getUserId(), NotiType.COMMUNITY_COMMENT,
                "COMMUNITY_COMMENT:comment:" + comment.getUuid(),
                Map.of("0", snippet), "/community/posts/" + post.getUuid());
    }

    private CommentResponse toResponse(CommunityComment comment, CommunityPost post,
                                       Long viewerId, boolean likedByMe, List<CommentResponse> children) {
        return new CommentResponse(
                comment.getUuid(), comment.getAnonymousNo(),
                comment.isOwnedBy(post.getUserId()), comment.isOwnedBy(viewerId),
                comment.getBody(), comment.getLikeCount(), likedByMe, false,
                comment.getCreatedAt(), children);
    }

    // 삭제 자리에는 본문도 별칭도 남기지 않는다.
    private CommentResponse placeholder(CommunityComment comment, List<CommentResponse> children) {
        return new CommentResponse(comment.getUuid(), null, false, false,
                null, 0, false, true, comment.getCreatedAt(), children);
    }
}
