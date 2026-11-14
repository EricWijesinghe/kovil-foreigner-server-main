package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.CommunityReport;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.enums.ReportReasonCode;
import com.kovil.domain.community.enums.ReportStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.CommunityReportRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// FR-714 — 노출 판정은 대상이 소유하고 신고 행은 이력으로 불변이다.
@ExtendWith(MockitoExtension.class)
class CommunityModerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");
    private static final String USER_UUID = "admin-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock UserRepository userRepository;
    @Mock CommunityPostRepository postRepository;
    @Mock CommunityCommentRepository commentRepository;
    @Mock CommunityReportRepository reportRepository;
    @Mock CommunityPostAssembler postAssembler;

    private CommunityModerationService service;

    @BeforeEach
    void setUp() {
        service = new CommunityModerationService(userQueryService, userRepository, postRepository,
                commentRepository, reportRepository, postAssembler,
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

    private static CommunityReport report(Long id, Long postId, Long commentId, ReportStatus status) {
        return withId(CommunityReport.builder()
                .uuid("rp-" + id).reporterId(50L).postId(postId).commentId(commentId)
                .reasonCode(ReportReasonCode.SPAM).status(status).build(), id);
    }

    @Nested
    @DisplayName("신고 단건·처리 기록")
    class ReportDetail {

        // 블라인드는 한 사람의 판단으로 남의 글을 가리는 일 — 누가 언제가 응답에 실려야 한다.
        @Test
        @DisplayName("처리된 신고는 처리자 이름과 시각을 싣는다")
        void handled_report_carries_handler() {
            // resolve()는 PENDING에서만 가능하다 — PENDING으로 만들고 전이시켜야 실제 경로다.
            CommunityReport handled = report(1L, 100L, null, ReportStatus.PENDING);
            handled.resolve(2L, LocalDateTime.of(2026, 8, 16, 10, 0));
            BDDMockito.given(reportRepository.findByUuid("rp-1"))
                    .willReturn(Optional.of(handled));
            BDDMockito.given(userRepository.findAllById(List.of(2L)))
                    .willReturn(List.of(withId(
                            User.builder()
                                    .uuid("admin-2").name("김도현")
                                    .status(UserStatus.ACTIVE)
                                    .role(UserRole.ADMIN)
                                    .build(), 2L)));
            BDDMockito.given(postRepository.findAllById(List.of(100L)))
                    .willReturn(List.of());
            BDDMockito.given(reportRepository.countByStatusAndPostIds(
                            ReportStatus.PENDING, List.of(100L)))
                    .willReturn(List.of());

            var response = service.getReportDetail("rp-1");

            assertThat(response.handledByName()).isEqualTo("김도현");
            assertThat(response.handledAt()).isEqualTo(LocalDateTime.of(2026, 8, 16, 10, 0));
        }

        @Test
        @DisplayName("처리한 계정이 지워졌으면 이름은 null이다 — 지어내지 않는다")
        void deleted_handler_name_is_null() {
            CommunityReport handled = report(1L, 100L, null, ReportStatus.PENDING);
            handled.resolve(2L, LocalDateTime.of(2026, 8, 16, 10, 0));
            BDDMockito.given(reportRepository.findByUuid("rp-1"))
                    .willReturn(Optional.of(handled));
            BDDMockito.given(userRepository.findAllById(List.of(2L)))
                    .willReturn(List.of());
            BDDMockito.given(postRepository.findAllById(List.of(100L)))
                    .willReturn(List.of());
            BDDMockito.given(reportRepository.countByStatusAndPostIds(
                            ReportStatus.PENDING, List.of(100L)))
                    .willReturn(List.of());

            assertThat(service.getReportDetail("rp-1").handledByName()).isNull();
        }

        @Test
        @DisplayName("없는 신고는 404다")
        void missing_report_is_404() {
            BDDMockito.given(reportRepository.findByUuid("gone"))
                    .willReturn(Optional.empty());

            Assertions.assertThatThrownBy(() -> service.getReportDetail("gone"))
                    .isInstanceOf(CommunityException.class)
                    .extracting(e -> ((CommunityException) e).getErrorCode().getCode())
                    .isEqualTo("REPORT_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("신고 처리")
    class Reports {

        @Test
        @DisplayName("승인은 대상을 숨기고 같은 대상의 대기 신고를 전부 닫는다")
        void resolve_blinds_and_closes_all_pending() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(2L));
            CommunityReport target = report(1L, 100L, null, ReportStatus.PENDING);
            CommunityReport sibling = report(2L, 100L, null, ReportStatus.PENDING);
            given(reportRepository.findByUuid("rp-1")).willReturn(Optional.of(target));
            CommunityPost postRow = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findById(100L)).willReturn(Optional.of(postRow));
            given(reportRepository.findByPostIdAndStatus(100L, ReportStatus.PENDING))
                    .willReturn(List.of(sibling));

            service.resolve(USER_UUID, "rp-1");

            assertThat(postRow.getStatus()).isEqualTo(CommunityContentStatus.HIDDEN);
            assertThat(target.getStatus()).isEqualTo(ReportStatus.RESOLVED);
            assertThat(sibling.getStatus()).isEqualTo(ReportStatus.RESOLVED);
            assertThat(target.getResolvedBy()).isEqualTo(2L);
        }

        @Test
        @DisplayName("이미 처리된 신고는 다시 처리할 수 없다 — 처리 후 불변")
        void handled_report_is_immutable() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(2L));
            given(reportRepository.findByUuid("rp-1"))
                    .willReturn(Optional.of(report(1L, 100L, null, ReportStatus.RESOLVED)));

            assertThatThrownBy(() -> service.resolve(USER_UUID, "rp-1"))
                    .extracting(CommunityModerationServiceTest::codeOf).isEqualTo("REPORT_ALREADY_HANDLED");
        }

        @Test
        @DisplayName("기각은 그 신고 한 건만 닫는다 — 대상은 그대로다")
        void dismiss_closes_only_that_report() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(2L));
            CommunityReport target = report(1L, 100L, null, ReportStatus.PENDING);
            given(reportRepository.findByUuid("rp-1")).willReturn(Optional.of(target));

            service.dismiss(USER_UUID, "rp-1");

            assertThat(target.getStatus()).isEqualTo(ReportStatus.DISMISSED);
            BDDMockito.then(postRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("블라인드 — 댓글은 카운트도 움직인다")
    class Blind {

        /*
         * ★ 판정은 스냅샷 읽기가 아니라 **조건부 벌크의 영향 행수**다 — 스냅샷 판정은 동시
         *   삭제와 겹치면 이중 감산이었다(적대적 리뷰 2026-09-02). 첫 호출(영향 1)만 내리고,
         *   두 번째(영향 0)는 blindRegardless 로 상태만 정리한다.
         */
        @Test
        @DisplayName("댓글 블라인드는 글의 댓글 수를 내리고, 이미 숨긴 댓글은 다시 내리지 않는다")
        void comment_blind_moves_count_once() {
            CommunityComment target = comment(5L, 30L, CommunityContentStatus.ACTIVE);
            given(commentRepository.findByUuid("cm-5")).willReturn(Optional.of(target));
            given(commentRepository.findById(5L)).willReturn(Optional.of(target));
            given(commentRepository.blindIfVisible(5L)).willReturn(1, 0);

            service.blindComment("cm-5");
            service.blindComment("cm-5");

            // 실제로 숨긴 첫 호출만 내린다 — 이미 숨긴 댓글은 0회.
            then(postRepository).should().decreaseCommentCount(100L);
            then(commentRepository).should().blindRegardless(5L);
        }

        @Test
        @DisplayName("해제는 상태와 카운트를 되돌린다")
        void unblind_restores_state_and_count() {
            CommunityComment target = comment(5L, 30L, CommunityContentStatus.HIDDEN);
            given(commentRepository.findByUuid("cm-5")).willReturn(Optional.of(target));

            service.unblindComment("cm-5");

            assertThat(target.getStatus()).isEqualTo(CommunityContentStatus.ACTIVE);
            then(postRepository).should().increaseCommentCount(100L);
        }

        // 유저가 삭제한 댓글은 삭제 시점에 카운트가 이미 내려갔다 — blind/unblind가
        // 그 위에서 또 움직이면 이중 감소·유령 증가 드리프트가 쌓인다.
        @Test
        @DisplayName("유저가 삭제한 댓글의 블라인드·해제는 카운트를 움직이지 않는다")
        void deleted_comment_does_not_move_count() {
            CommunityComment target = comment(5L, 30L, CommunityContentStatus.ACTIVE);
            target.softDelete(LocalDateTime.of(2026, 8, 21, 10, 0));
            given(commentRepository.findByUuid("cm-5")).willReturn(Optional.of(target));
            given(commentRepository.findById(5L)).willReturn(Optional.of(target));

            service.blindComment("cm-5");
            service.unblindComment("cm-5");

            then(postRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("글 블라인드는 조회 불가로, 해제는 다시 보이게 만든다")
        void post_blind_and_unblind() {
            CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(postRepository.findById(100L)).willReturn(Optional.of(target));

            service.blindPost(POST_UUID);
            assertThat(target.isReadable()).isFalse();

            service.unblindPost(POST_UUID);
            assertThat(target.isReadable()).isTrue();
        }
    }

    // 유저 조회는 블라인드된 글을 감춘다 — 관리자까지 못 보면 내린 판단을 되돌릴 수 없다.
    // 반대로 작성자 식별자는 관리자 화면에서도 새지 않는다(INV-10).
    @Nested
    @DisplayName("신고 대상 열람")
    class ContentLookup {

        @Test
        @DisplayName("블라인드된 글도 관리자에게는 원문이 그대로 내려간다")
        void blinded_post_is_still_readable_by_admin() {
            CommunityPost blinded = post(9L, CommunityContentStatus.HIDDEN);
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(1L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(blinded));
            given(postAssembler.toDetail(blinded, 1L)).willReturn(null);

            var response = service.getPost(USER_UUID, POST_UUID);

            assertThat(response.status()).isEqualTo("HIDDEN");
            assertThat(blinded.isReadable()).isFalse();
        }

        // 반복 스팸을 잡되 익명은 유지한다 — 서버가 "같은 작성자"라는 관계만 풀어 준다.
        @Test
        @DisplayName("같은 작성자의 다른 글을 주되 작성자 식별자는 응답에 없다")
        void sibling_posts_keep_anonymity() {
            CommunityPost target = post(9L, CommunityContentStatus.ACTIVE);
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(1L));
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(postRepository.findByUserIdAndDeletedAtIsNullOrderByIdDesc(
                    ArgumentMatchers.eq(9L), ArgumentMatchers.any()))
                    .willReturn(new PageImpl<>(List.of(target)));
            given(postAssembler.toSummaries(List.of(target), 1L)).willReturn(List.of());

            var page = service.findSiblingPosts(USER_UUID, POST_UUID,
                    PageRequest.of(0, 20));

            String json = JsonMapper.builder().build()
                    .writeValueAsString(page);
            assertThat(json).doesNotContain("authorUuid").doesNotContain("userId");
        }
    }
}
