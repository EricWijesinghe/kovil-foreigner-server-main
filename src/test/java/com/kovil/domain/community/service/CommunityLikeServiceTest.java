package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.CommunityPostLike;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentLikeRepository;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostLikeRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// FR-709 — 멱등. 두 번 눌러도 카운트는 한 번이다.
@ExtendWith(MockitoExtension.class)
class CommunityLikeServiceTest {

    private static final String USER_UUID = "user-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock CommunityPostRepository postRepository;
    @Mock CommunityCommentRepository commentRepository;
    @Mock CommunityPostLikeRepository postLikeRepository;
    @Mock CommunityCommentLikeRepository commentLikeRepository;

    private CommunityLikeService service;

    @BeforeEach
    void setUp() {
        service = new CommunityLikeService(userQueryService, postRepository, commentRepository,
                postLikeRepository, commentLikeRepository);
    }

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static User user(Long id) {
        return withId(User.builder().uuid(USER_UUID).name("n").preferredLanguage("vi")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build(), id);
    }

    private static CommunityPost post(Long ownerId, CommunityContentStatus status) {
        return withId(CommunityPost.builder()
                .uuid(POST_UUID).categoryId(7L).userId(ownerId).languageCode("vi")
                .title("t").body("b").status(status).build(), 100L);
    }

    private static CommunityComment comment(Long id, Long ownerId, CommunityContentStatus status) {
        return withId(CommunityComment.builder()
                .uuid("cm-" + id).postId(100L).userId(ownerId).anonymousNo((short) 1)
                .body("c").status(status).build(), id);
    }

    private static String codeOf(Throwable e) {
        return ((CommunityException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("좋아요는 행을 남기고 카운트를 원자 UPDATE로 올린다")
    void like_saves_row_and_count() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
        given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
        given(postLikeRepository.insertIgnore(100L, 20L)).willReturn(1);

        service.likePost(USER_UUID, POST_UUID);

        then(postRepository).should().increaseLikeCount(100L);
    }

    // 이미 눌린 좋아요(순차든 동시든)는 INSERT IGNORE가 0행으로 알려 준다 — 예외가 아니라
    // 반환값이라 rollback-only에 걸리지 않고, 카운트도 오르지 않는다.
    @Test
    @DisplayName("두 번 눌러도 한 번이다 — 0행 삽입이면 카운트가 오르지 않는다")
    void double_like_is_idempotent() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
        given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
        given(postLikeRepository.insertIgnore(100L, 20L)).willReturn(0);

        service.likePost(USER_UUID, POST_UUID);

        then(postRepository).should(never()).increaseLikeCount(any());
    }

    @Test
    @DisplayName("취소는 행을 지우고 카운트를 내린다 — 안 눌렀으면 조용히 지나간다")
    void unlike_removes_row_or_noops() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
        given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
        given(postLikeRepository.deleteByPostIdAndUserId(100L, 20L))
                .willReturn(1)
                .willReturn(0);

        service.unlikePost(USER_UUID, POST_UUID);
        service.unlikePost(USER_UUID, POST_UUID);

        // 실제로 지운 첫 호출만 카운트를 내린다 — 두 번째는 0행 삭제라 그대로다.
        then(postRepository).should().decreaseLikeCount(100L);
    }

    @Test
    @DisplayName("숨겨진 글에는 좋아요를 누를 수 없다")
    void hidden_post_cannot_be_liked() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.HIDDEN)));

        assertThatThrownBy(() -> service.likePost(USER_UUID, POST_UUID))
                .extracting(CommunityLikeServiceTest::codeOf).isEqualTo("POST_NOT_FOUND");
    }

    @Test
    @DisplayName("댓글 좋아요·취소도 같은 규칙이다")
    void comment_like_follows_same_rules() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        CommunityComment target = comment(5L, 30L, CommunityContentStatus.ACTIVE);
        given(commentRepository.findByUuid("cm-5")).willReturn(Optional.of(target));
        given(commentLikeRepository.insertIgnore(5L, 20L)).willReturn(1);

        service.likeComment(USER_UUID, "cm-5");

        then(commentRepository).should().increaseLikeCount(5L);
    }
}
