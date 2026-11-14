package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.UserBlock;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.UserBlockRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// FR-713 — 차단하는 쪽은 상대가 누군지 끝까지 모른다. 서버가 글·댓글을 계정으로 변환한다.
@ExtendWith(MockitoExtension.class)
class CommunityBlockServiceTest {

    private static final String USER_UUID = "user-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock CommunityPostRepository postRepository;
    @Mock CommunityCommentRepository commentRepository;
    @Mock UserBlockRepository blockRepository;

    private CommunityBlockService service;

    @BeforeEach
    void setUp() {
        service = new CommunityBlockService(userQueryService, postRepository,
                commentRepository, blockRepository);
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
    @DisplayName("글을 지목하면 서버가 작성자 계정으로 변환해 저장한다")
    void block_translates_post_to_author() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));
        service.blockPostAuthor(USER_UUID, POST_UUID);

        // 서버가 글 → 작성자 계정으로 변환해 멱등 삽입한다.
        then(blockRepository).should().insertIgnore(20L, 10L);
    }

    @Test
    @DisplayName("자기 글을 지목하면 400이다")
    void self_block_is_rejected() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));

        assertThatThrownBy(() -> service.blockPostAuthor(USER_UUID, POST_UUID))
                .extracting(CommunityBlockServiceTest::codeOf).isEqualTo("SELF_BLOCK");
        then(blockRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 차단한 상대를 다른 글에서 다시 차단해도 조용히 지나간다 — 0행 삽입")
    void repeated_block_is_idempotent() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));
        given(blockRepository.insertIgnore(20L, 10L)).willReturn(0);

        service.blockPostAuthor(USER_UUID, POST_UUID);

        // 예외 없이 지나가면 된다 — 유니크 충돌은 INSERT IGNORE가 0행으로 흡수한다.
        then(blockRepository).should().insertIgnore(20L, 10L);
    }

    @Test
    @DisplayName("댓글을 지목해도 작성자 계정으로 변환된다")
    void block_translates_comment_to_author() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(commentRepository.findByUuid("cm-5"))
                .willReturn(Optional.of(comment(5L, 30L, CommunityContentStatus.ACTIVE)));
        service.blockCommentAuthor(USER_UUID, "cm-5");

        then(blockRepository).should().insertIgnore(20L, 30L);
    }
}
