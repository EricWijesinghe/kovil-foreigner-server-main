package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.UserBlock;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.UserBlockRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.SELF_BLOCK;

// FR-713 — 차단하는 쪽은 상대가 누군지 끝까지 모른다. 글·댓글을 지목하면
// 서버가 내부에서 계정으로 변환한다. 효과는 목록 필터(단방향)다.
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityBlockService {

    private final UserQueryService userQueryService;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final UserBlockRepository blockRepository;

    @Transactional
    public void blockPostAuthor(String userUuid, String postUuid) {
        User blocker = userQueryService.getActiveUser(userUuid);
        CommunityPost post = postRepository.findByUuid(postUuid)
                .filter(CommunityPost::isReadable)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
        block(blocker, post.getUserId());
        log.info("[community] 글 작성자 차단 post={}", postUuid);
    }

    @Transactional
    public void blockCommentAuthor(String userUuid, String commentUuid) {
        User blocker = userQueryService.getActiveUser(userUuid);
        CommunityComment comment = commentRepository.findByUuid(commentUuid)
                .filter(CommunityComment::isVisible)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
        block(blocker, comment.getUserId());
        log.info("[community] 댓글 작성자 차단 comment={}", commentUuid);
    }

    // 멱등 — 이미 차단한 상대를 다른 글에서 다시 차단해도 조용히 지나간다.
    private void block(User blocker, Long targetUserId) {
        if (blocker.getId().equals(targetUserId)) {
            throw new CommunityException(SELF_BLOCK);
        }
        // 동시 이중 차단은 INSERT IGNORE가 0행으로 흡수한다 — 유니크 충돌 catch는
        // rollback-only 트랩이라 커밋이 500으로 죽는다(CommunityLikeService와 같은 수리).
        blockRepository.insertIgnore(blocker.getId(), targetUserId);
    }
}
