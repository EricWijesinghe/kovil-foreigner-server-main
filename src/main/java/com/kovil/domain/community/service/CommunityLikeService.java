package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentLikeRepository;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostLikeRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;

// FR-709 — 멱등. 두 번 눌러도 1행·카운트 1이고, 없는 좋아요 취소는 조용히 지나간다.
// 동시 더블탭은 INSERT IGNORE가 0행으로 흡수한다 — 유니크 충돌을 같은 트랜잭션에서 catch로
// 삼키면 rollback-only 때문에 커밋이 500으로 죽는다. 카운트는 삽입/삭제가 실제로 일어난
// 요청만, 원자 UPDATE로 올린다(read-modify-write는 동시 요청에서 증가분을 잃는다).
@Service
@RequiredArgsConstructor
public class CommunityLikeService {

    private final UserQueryService userQueryService;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostLikeRepository postLikeRepository;
    private final CommunityCommentLikeRepository commentLikeRepository;

    @Transactional
    public void likePost(String userUuid, String postUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityPost post = getReadablePost(postUuid);
        if (postLikeRepository.insertIgnore(post.getId(), user.getId()) == 1) {
            postRepository.increaseLikeCount(post.getId());
        }
    }

    @Transactional
    public void unlikePost(String userUuid, String postUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityPost post = getReadablePost(postUuid);
        if (postLikeRepository.deleteByPostIdAndUserId(post.getId(), user.getId()) > 0) {
            postRepository.decreaseLikeCount(post.getId());
        }
    }

    @Transactional
    public void likeComment(String userUuid, String commentUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityComment comment = getVisibleComment(commentUuid);
        if (commentLikeRepository.insertIgnore(comment.getId(), user.getId()) == 1) {
            commentRepository.increaseLikeCount(comment.getId());
        }
    }

    @Transactional
    public void unlikeComment(String userUuid, String commentUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        CommunityComment comment = getVisibleComment(commentUuid);
        if (commentLikeRepository.deleteByCommentIdAndUserId(comment.getId(), user.getId()) > 0) {
            commentRepository.decreaseLikeCount(comment.getId());
        }
    }

    private CommunityPost getReadablePost(String postUuid) {
        return postRepository.findByUuid(postUuid)
                .filter(CommunityPost::isReadable)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
    }

    private CommunityComment getVisibleComment(String commentUuid) {
        return commentRepository.findByUuid(commentUuid)
                .filter(CommunityComment::isVisible)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
    }
}
