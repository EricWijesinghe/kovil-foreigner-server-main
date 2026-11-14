package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.CommentCreateRequest;
import com.kovil.domain.community.dto.response.CommentResponse;
import com.kovil.domain.community.entity.CommunityComment;
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
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CommunityCommentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");
    private static final String USER_UUID = "user-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock CommunityPostRepository postRepository;
    @Mock CommunityCommentRepository commentRepository;
    @Mock CommunityCommentLikeRepository commentLikeRepository;
    @Mock UserBlockRepository blockRepository;
    @Mock CommunityAliasIssuer aliasIssuer;
    @Mock NotificationPublisher notificationPublisher;

    private CommunityCommentService service;

    @BeforeEach
    void setUp() {
        service = new CommunityCommentService(userQueryService, postRepository, commentRepository,
                commentLikeRepository, blockRepository, aliasIssuer, notificationPublisher,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
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

    private static CommunityPost post(Long ownerId) {
        return withId(CommunityPost.builder()
                .uuid(POST_UUID).categoryId(7L).userId(ownerId).languageCode("vi")
                .title("서울 월세 후기입니다").body("b").commentCount(0)
                .status(CommunityContentStatus.ACTIVE).build(), 100L);
    }

    private static CommunityComment comment(Long id, Long postId, Long parentId, Long ownerId) {
        return withId(CommunityComment.builder()
                .uuid("cm-" + id).postId(postId).parentCommentId(parentId).userId(ownerId)
                .anonymousNo((short) 1).body("댓글").status(CommunityContentStatus.ACTIVE).build(), id);
    }

    private static String codeOf(Throwable e) {
        return ((CommunityException) e).getErrorCode().getCode();
    }

    @Nested
    @DisplayName("작성 (FR-704 · FR-705)")
    class Create {

        @Test
        @DisplayName("댓글이 달리면 별칭을 받고 글의 댓글 수가 오르고 글쓴이에게 알림이 간다")
        void comment_issues_alias_and_notifies_author() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            CommunityPost target = post(10L);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(aliasIssuer.issue(100L, 20L)).willReturn((short) 1);
            given(commentRepository.save(any())).willAnswer(call -> withId(call.getArgument(0), 900L));

            CommentResponse response = service.create(USER_UUID, POST_UUID,
                    new CommentCreateRequest("좋은 정보 감사합니다", null));

            assertThat(response.anonymousNo()).isEqualTo((short) 1);
            assertThat(response.isAuthor()).isFalse();
            assertThat(response.isMine()).isTrue();
            then(postRepository).should().increaseCommentCount(100L);
            then(notificationPublisher).should().publish(eq(10L), eq(NotiType.COMMUNITY_COMMENT),
                    any(), any(), any());
        }

        @Test
        @DisplayName("글쓴이가 자기 글에 달면 별칭 0을 재사용하고 알림은 없다")
        void author_comment_reuses_zero_and_skips_notification() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(post(10L)));
            given(aliasIssuer.issue(100L, 10L)).willReturn((short) 0);
            given(commentRepository.save(any())).willAnswer(call -> withId(call.getArgument(0), 900L));

            CommentResponse response = service.create(USER_UUID, POST_UUID,
                    new CommentCreateRequest("보충 설명입니다", null));

            assertThat(response.anonymousNo()).isEqualTo((short) 0);
            assertThat(response.isAuthor()).isTrue();
            then(notificationPublisher).should(never()).publish(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("대댓글에는 다시 달 수 없다 — 깊이 2 고정")
        void reply_to_reply_is_rejected() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(post(10L)));
            given(commentRepository.findByUuid("cm-2"))
                    .willReturn(Optional.of(comment(2L, 100L, 1L, 30L)));

            assertThatThrownBy(() -> service.create(USER_UUID, POST_UUID,
                    new CommentCreateRequest("답의 답", "cm-2")))
                    .extracting(CommunityCommentServiceTest::codeOf).isEqualTo("COMMENT_DEPTH_EXCEEDED");
            then(commentRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("다른 글의 댓글을 부모로 지목하면 거부한다")
        void parent_from_another_post_is_rejected() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(post(10L)));
            given(commentRepository.findByUuid("cm-2"))
                    .willReturn(Optional.of(comment(2L, 999L, null, 30L)));

            assertThatThrownBy(() -> service.create(USER_UUID, POST_UUID,
                    new CommentCreateRequest("잘못된 부모", "cm-2")))
                    .extracting(CommunityCommentServiceTest::codeOf).isEqualTo("COMMENT_PARENT_MISMATCH");
        }
    }

    @Nested
    @DisplayName("스레드 조립 (FR-706)")
    class Thread {

        private void givenNoBlocksAndLikes() {
            given(blockRepository.findBlockedUserIds(20L)).willReturn(Set.of());
            given(commentLikeRepository.findByUserIdAndCommentIdIn(ArgumentMatchers.eq(20L), any()))
                    .willReturn(List.of());
        }

        @Test
        @DisplayName("대댓글이 있는 삭제 댓글은 자리로 남고, 없는 삭제는 빠진다")
        void deleted_comment_keeps_place_only_with_children() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(post(10L)));
            givenNoBlocksAndLikes();

            CommunityComment deletedWithChild = comment(1L, 100L, null, 30L);
            deletedWithChild.softDelete(LocalDateTime.now());
            CommunityComment child = comment(2L, 100L, 1L, 40L);
            CommunityComment deletedAlone = comment(3L, 100L, null, 50L);
            deletedAlone.softDelete(LocalDateTime.now());
            CommunityComment plain = comment(4L, 100L, null, 60L);
            given(commentRepository.findByPostIdOrderByIdAsc(100L))
                    .willReturn(List.of(deletedWithChild, child, deletedAlone, plain));

            List<CommentResponse> thread = service.findThread(USER_UUID, POST_UUID);

            assertThat(thread).hasSize(2);
            CommentResponse placeholder = thread.get(0);
            assertThat(placeholder.deleted()).isTrue();
            assertThat(placeholder.body()).isNull();
            assertThat(placeholder.anonymousNo()).isNull();
            assertThat(placeholder.children()).hasSize(1);
            assertThat(thread.get(1).deleted()).isFalse();
        }

        @Test
        @DisplayName("삭제된 대댓글은 자리 없이 빠진다 — 그 아래가 없기 때문이다")
        void deleted_child_is_dropped() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(post(10L)));
            givenNoBlocksAndLikes();

            CommunityComment top = comment(1L, 100L, null, 30L);
            CommunityComment deletedChild = comment(2L, 100L, 1L, 40L);
            deletedChild.softDelete(LocalDateTime.now());
            given(commentRepository.findByPostIdOrderByIdAsc(100L)).willReturn(List.of(top, deletedChild));

            List<CommentResponse> thread = service.findThread(USER_UUID, POST_UUID);

            assertThat(thread).hasSize(1);
            assertThat(thread.get(0).children()).isEmpty();
        }
    }

    @Nested
    @DisplayName("수정·삭제 — 본인만")
    class OwnerOnly {

        @Test
        @DisplayName("남의 댓글은 지울 수 없다")
        void others_comment_cannot_be_deleted() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(99L));
            given(commentRepository.findByUuid("cm-1"))
                    .willReturn(Optional.of(comment(1L, 100L, null, 30L)));

            assertThatThrownBy(() -> service.delete(USER_UUID, "cm-1"))
                    .extracting(CommunityCommentServiceTest::codeOf).isEqualTo("NOT_CONTENT_OWNER");
        }

        @Test
        @DisplayName("삭제하면 글의 댓글 수가 내려간다")
        void delete_decreases_comment_count() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(30L));
            CommunityComment target = comment(1L, 100L, null, 30L);
            given(commentRepository.findByUuid("cm-1")).willReturn(Optional.of(target));

            // 조건부 벌크 삭제 — 영향 행수 1이 판정이다(더블탭·블라인드 경합 이중 감산 방지).
            given(commentRepository.softDeleteIfVisible(ArgumentMatchers.eq(1L), any()))
                    .willReturn(1);

            service.delete(USER_UUID, "cm-1");

            then(postRepository).should().decreaseCommentCount(100L);
        }

        // 더블탭 패자(영향 행수 0)는 감산하지 않는다 — 두 번 내리면 카운트가 거짓말한다.
        @Test
        @DisplayName("동시 이중 삭제의 패자는 댓글 수를 내리지 않는다")
        void concurrent_double_delete_decrements_once() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(30L));
            CommunityComment target = comment(1L, 100L, null, 30L);
            given(commentRepository.findByUuid("cm-1")).willReturn(Optional.of(target));
            given(commentRepository.softDeleteIfVisible(ArgumentMatchers.eq(1L), any()))
                    .willReturn(0);

            service.delete(USER_UUID, "cm-1");

            then(postRepository).should(Mockito.never()).decreaseCommentCount(any());
        }
    }
}
