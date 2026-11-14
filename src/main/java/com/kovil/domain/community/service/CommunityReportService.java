package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.ReportCreateRequest;
import com.kovil.domain.community.entity.CommunityComment;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.CommunityReport;
import com.kovil.domain.community.enums.ReportStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCommentRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.CommunityReportRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.REPORT_DUPLICATE;

// FR-712 — 스토어 UGC 요건. 신고는 글·댓글 중 하나만 지목하고(경로가 가른다),
// 같은 대상 중복 신고는 서비스 검사 + DB 유니크 이중 방어다.
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityReportService {

    private final UserQueryService userQueryService;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityReportRepository reportRepository;

    @Transactional
    public void reportPost(String userUuid, String postUuid, ReportCreateRequest request) {
        User reporter = userQueryService.getActiveUser(userUuid);
        CommunityPost post = postRepository.findByUuid(postUuid)
                .filter(CommunityPost::isReadable)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
        if (reportRepository.existsByReporterIdAndPostId(reporter.getId(), post.getId())) {
            throw new CommunityException(REPORT_DUPLICATE);
        }
        save(reporter, post.getId(), null, request);
        log.info("[community] 글 신고 post={} reason={}", postUuid, request.reasonCode());
    }

    @Transactional
    public void reportComment(String userUuid, String commentUuid, ReportCreateRequest request) {
        User reporter = userQueryService.getActiveUser(userUuid);
        CommunityComment comment = commentRepository.findByUuid(commentUuid)
                .filter(CommunityComment::isVisible)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
        if (reportRepository.existsByReporterIdAndCommentId(reporter.getId(), comment.getId())) {
            throw new CommunityException(REPORT_DUPLICATE);
        }
        save(reporter, null, comment.getId(), request);
        log.info("[community] 댓글 신고 comment={} reason={}", commentUuid, request.reasonCode());
    }

    private void save(User reporter, Long postId, Long commentId, ReportCreateRequest request) {
        try {
            reportRepository.saveAndFlush(CommunityReport.builder()
                    .reporterId(reporter.getId())
                    .postId(postId)
                    .commentId(commentId)
                    .reasonCode(request.reasonCode())
                    .detail(request.detail() == null || request.detail().isBlank()
                            ? null : request.detail().strip())
                    .status(ReportStatus.PENDING)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 동시 이중 제출 — 서비스 검사를 스쳐 지나간 쪽은 DB 유니크가 잡는다.
            throw new CommunityException(REPORT_DUPLICATE);
        }
    }
}
