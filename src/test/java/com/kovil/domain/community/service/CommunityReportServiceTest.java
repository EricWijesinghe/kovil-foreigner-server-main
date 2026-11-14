package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.ReportCreateRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// FR-712 — 스토어 UGC 요건. 같은 대상 중복 신고는 서비스 + DB 이중 방어다.
@ExtendWith(MockitoExtension.class)
class CommunityReportServiceTest {

    private static final String USER_UUID = "user-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock CommunityPostRepository postRepository;
    @Mock CommunityCommentRepository commentRepository;
    @Mock CommunityReportRepository reportRepository;

    private CommunityReportService service;

    @BeforeEach
    void setUp() {
        service = new CommunityReportService(userQueryService, postRepository,
                commentRepository, reportRepository);
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
    @DisplayName("신고는 사유·상세와 함께 PENDING으로 쌓인다")
    void report_is_saved_as_pending() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));
        given(reportRepository.existsByReporterIdAndPostId(20L, 100L)).willReturn(false);
        given(reportRepository.saveAndFlush(any())).willAnswer(call -> call.getArgument(0));

        service.reportPost(USER_UUID, POST_UUID,
                new ReportCreateRequest(ReportReasonCode.SCAM, "  보증금 선입금을 요구합니다  "));

        ArgumentCaptor<CommunityReport> captor = ArgumentCaptor.forClass(CommunityReport.class);
        then(reportRepository).should().saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ReportReasonCode.SCAM);
        assertThat(captor.getValue().getDetail()).isEqualTo("보증금 선입금을 요구합니다");
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(captor.getValue().getPostId()).isEqualTo(100L);
        assertThat(captor.getValue().getCommentId()).isNull();
    }

    @Test
    @DisplayName("같은 글을 두 번 신고하면 409다")
    void duplicate_report_is_rejected() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));
        given(reportRepository.existsByReporterIdAndPostId(20L, 100L)).willReturn(true);

        assertThatThrownBy(() -> service.reportPost(USER_UUID, POST_UUID,
                new ReportCreateRequest(ReportReasonCode.SPAM, null)))
                .extracting(CommunityReportServiceTest::codeOf).isEqualTo("REPORT_DUPLICATE");
        then(reportRepository).should(never()).saveAndFlush(any());
    }

    // 동시 이중 제출 — 존재 검사를 스쳐 지나간 쪽은 DB 유니크가 잡는다.
    @Test
    @DisplayName("동시 이중 제출도 409로 수렴한다")
    void concurrent_duplicate_converges_to_409() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));
        given(reportRepository.existsByReporterIdAndPostId(20L, 100L)).willReturn(false);
        given(reportRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uq_crep_reporter_post"));

        assertThatThrownBy(() -> service.reportPost(USER_UUID, POST_UUID,
                new ReportCreateRequest(ReportReasonCode.SPAM, null)))
                .extracting(CommunityReportServiceTest::codeOf).isEqualTo("REPORT_DUPLICATE");
    }

    @Test
    @DisplayName("댓글 신고는 comment_id만 채운다")
    void comment_report_fills_comment_axis() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(commentRepository.findByUuid("cm-5"))
                .willReturn(Optional.of(comment(5L, 30L, CommunityContentStatus.ACTIVE)));
        given(reportRepository.existsByReporterIdAndCommentId(20L, 5L)).willReturn(false);
        given(reportRepository.saveAndFlush(any())).willAnswer(call -> call.getArgument(0));

        service.reportComment(USER_UUID, "cm-5",
                new ReportCreateRequest(ReportReasonCode.ABUSE, null));

        ArgumentCaptor<CommunityReport> captor = ArgumentCaptor.forClass(CommunityReport.class);
        then(reportRepository).should().saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCommentId()).isEqualTo(5L);
        assertThat(captor.getValue().getPostId()).isNull();
    }
}
