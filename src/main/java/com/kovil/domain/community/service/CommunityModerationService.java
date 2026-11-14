package com.kovil.domain.community.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.community.dto.response.*;
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
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.kovil.domain.community.exception.CommunityErrorCode.COMMENT_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.REPORT_NOT_FOUND;

// FR-714 — 블라인드는 대상의 status가 소유한다. ADMIN 게이트는 컨트롤러의 @PreAuthorize.
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityModerationService {

    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityReportRepository reportRepository;
    private final CommunityPostAssembler postAssembler;
    private final Clock clock;

    // FR-905 — 처리 대기열. 대상 미리보기와 같은 대상 신고 수까지 실어 화면 왕복을 줄인다.
    // targetUuid를 주면 **그 대상의 신고만** 모아 본다 — 신고 3건 각각의 사유를 읽어야 판단이 선다.
    // 대기열(PENDING)은 오래 기다린 순, 이력(RESOLVED·DISMISSED)은 **최신부터**다 —
    // 잘못 블라인드한 글은 최근 처리분에서 찾는다.
    @Transactional(readOnly = true)
    public PageResponse<AdminReportResponse> findQueue(
            ReportStatus status,
            ReportReasonCode reasonCode,
            String targetType, String targetUuid,
            Pageable pageable) {
        boolean comment = "COMMENT".equalsIgnoreCase(targetType);
        Long targetPostId = targetUuid == null || comment ? null : resolvePostId(targetUuid);
        Long targetCommentId = targetUuid == null || !comment ? null : resolveCommentId(targetUuid);

        var page = status == ReportStatus.PENDING
                ? reportRepository.searchQueue(status, reasonCode, targetPostId, targetCommentId, pageable)
                : reportRepository.searchHistory(status, reasonCode, targetPostId, targetCommentId, pageable);
        List<   AdminReportResponse> body =
                toResponses(page.getContent());

        return PageResponse.from(
                new PageImpl<>(body, pageable, page.getTotalElements()));
    }

    // M2 딥링크의 축 — 처리된 건(RESOLVED·DISMISSED)도 같은 경로로 다시 본다.
    // 이게 없으면 화면이 대기열을 통째로 받아 와 reportUuid를 찾아야 한다(100건 넘으면 실패).
    @Transactional(readOnly = true)
    public AdminReportResponse getReportDetail(String reportUuid) {
        CommunityReport report = reportRepository.findByUuid(reportUuid)
                .orElseThrow(() -> new CommunityException(REPORT_NOT_FOUND));
        return toResponses(List.of(report)).get(0);
    }

    // M1 목록의 실제 작업 단위 — 같은 대상의 신고 N건을 1행으로 묶는다. 많이 신고된 대상이 먼저,
    // 같으면 오래 기다린 대상이 먼저다(시안 M1 "같은 대상 신고 많은 순").
    @Transactional(readOnly = true)
    public PageResponse<AdminReportTargetResponse> findTargetQueue(
            ReportReasonCode reasonCode,
            Pageable pageable) {
        List<Object[]> rows = reportRepository.groupQueueByTarget(ReportStatus.PENDING, reasonCode, pageable);
        long total = reportRepository.countDistinctPostTargets(ReportStatus.PENDING, reasonCode)
                + reportRepository.countDistinctCommentTargets(ReportStatus.PENDING, reasonCode);

        List<Long> postIds = rows.stream().map(row -> (Long) row[0])
                .filter(Objects::nonNull).toList();
        List<Long> commentIds = rows.stream().map(row -> (Long) row[1])
                .filter(Objects::nonNull).toList();
        Map<Long, CommunityPost> posts = postIds.isEmpty() ? Map.of()
                : postRepository.findAllById(postIds).stream()
                        .collect(Collectors.toMap(CommunityPost::getId, p -> p));
        Map<Long, CommunityComment> comments = commentIds.isEmpty() ? Map.of()
                : commentRepository.findAllById(commentIds).stream()
                        .collect(Collectors.toMap(CommunityComment::getId, c -> c));

        List<AdminReportTargetResponse> body =
                rows.stream().map(row -> {
                    Long postId = (Long) row[0];
                    long count = (Long) row[2];
                    LocalDateTime oldest = (LocalDateTime) row[3];
                    LocalDateTime latest = (LocalDateTime) row[4];
                    if (postId != null) {
                        CommunityPost target = posts.get(postId);
                        return new AdminReportTargetResponse(
                                "POST",
                                target == null ? null : target.getUuid(),
                                target == null ? null : target.getTitle(),
                                target == null || !target.isReadable(),
                                count, oldest, latest);
                    }
                    CommunityComment target = comments.get((Long) row[1]);
                    return new AdminReportTargetResponse(
                            "COMMENT",
                            target == null ? null : target.getUuid(),
                            target == null ? null : preview(target),
                            target == null || !target.isVisible(),
                            count, oldest, latest);
                }).toList();

        return PageResponse.from(
                new PageImpl<>(body, pageable, total));
    }

    // 신고 대상 글의 원문 — **블라인드·삭제분도 그대로** 내려온다(유저 조회는 감춘다).
    // 조회수를 올리지 않는다: 관리자의 열람이 인기 순위를 흔들면 안 된다. 작성자 식별자 없음(INV-10).
    @Transactional(readOnly = true)
    public AdminContentResponse getPost(
            String adminUuid, String postUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        CommunityPost post = postRepository.findByUuid(postUuid)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
        return new AdminContentResponse(
                post.getStatus().name(), post.getDeletedAt(),
                postAssembler.toDetail(post, admin.getId()), null);
    }

    // 댓글이 대상이면 **달린 글**을 맥락으로 함께 준다 — 댓글만 봐서는 판단이 안 선다.
    @Transactional(readOnly = true)
    public AdminContentResponse getComment(
            String adminUuid, String commentUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        CommunityComment comment = commentRepository.findByUuid(commentUuid)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
        CommunityPost post = postRepository.findById(comment.getPostId())
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
        return new AdminContentResponse(
                comment.getStatus().name(), comment.getDeletedAt(),
                postAssembler.toDetail(post, admin.getId()),
                new CommentResponse(
                        comment.getUuid(), comment.getAnonymousNo(),
                        comment.getAnonymousNo() != null && comment.getAnonymousNo() == 0,
                        false,
                        comment.getBody(), comment.getLikeCount(), false,
                        comment.getDeletedAt() != null, comment.getCreatedAt(), List.of()));
    }

    // 같은 작성자의 다른 글 — 관계만 보여주고 **작성자 uuid는 싣지 않는다**(전면 익명 유지).
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> findSiblingPosts(
            String adminUuid, String postUuid, Pageable pageable) {
        User admin = userQueryService.getActiveUser(adminUuid);
        CommunityPost post = postRepository.findByUuid(postUuid)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
        var page = postRepository.findByUserIdAndDeletedAtIsNullOrderByIdDesc(post.getUserId(), pageable);
        return PageResponse.from(
                new PageImpl<>(
                        postAssembler.toSummaries(page.getContent(), admin.getId()),
                        pageable, page.getTotalElements()));
    }

    private Long resolvePostId(String postUuid) {
        return postRepository.findByUuid(postUuid)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND)).getId();
    }

    private Long resolveCommentId(String commentUuid) {
        return commentRepository.findByUuid(commentUuid)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND)).getId();
    }

    // 대기열·단건이 같은 모양을 내보내는 유일한 지점 — 대상 미리보기·동일 대상 수·처리자 이름을
    // 전부 페이지 단위 일괄 조회로 채운다(행마다 왕복하지 않는다).
    private List<AdminReportResponse> toResponses(
            List<CommunityReport> rows) {
        List<Long> postIds = rows.stream().map(CommunityReport::getPostId)
                .filter(Objects::nonNull).distinct().toList();
        List<Long> commentIds = rows.stream().map(CommunityReport::getCommentId)
                .filter(Objects::nonNull).distinct().toList();

        Map<Long, CommunityPost> posts = postIds.isEmpty() ? Map.of()
                : postRepository.findAllById(postIds).stream()
                        .collect(Collectors.toMap(CommunityPost::getId, p -> p));
        Map<Long, CommunityComment> comments = commentIds.isEmpty() ? Map.of()
                : commentRepository.findAllById(commentIds).stream()
                        .collect(Collectors.toMap(CommunityComment::getId, c -> c));
        Map<Long, Long> postCounts = postIds.isEmpty() ? Map.of()
                : reportRepository.countByStatusAndPostIds(ReportStatus.PENDING, postIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        Map<Long, Long> commentCounts = commentIds.isEmpty() ? Map.of()
                : reportRepository.countByStatusAndCommentIds(ReportStatus.PENDING, commentIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        // 처리자 이름 — 계정이 지워졌으면 null(이름을 지어내지 않는다).
        List<Long> handlerIds = rows.stream().map(CommunityReport::getResolvedBy)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> handlerNames = handlerIds.isEmpty() ? Map.of()
                : userRepository.findAllById(handlerIds).stream()
                        .filter(user -> user.getName() != null)
                        .collect(Collectors.toMap(User::getId, User::getName));

        return rows.stream().map(report -> {
            String handledByName = report.getResolvedBy() == null ? null
                    : handlerNames.get(report.getResolvedBy());
            if (report.getPostId() != null) {
                CommunityPost target = posts.get(report.getPostId());
                return new AdminReportResponse(
                        report.getUuid(), "POST",
                        target == null ? null : target.getUuid(),
                        target == null ? null : target.getTitle(),
                        target == null || !target.isReadable(),
                        report.getReasonCode(), report.getDetail(),
                        postCounts.getOrDefault(report.getPostId(), 0L),
                        report.getStatus(), report.getCreatedAt(),
                        handledByName, report.getResolvedAt());
            }
            CommunityComment target = comments.get(report.getCommentId());
            return new AdminReportResponse(
                    report.getUuid(), "COMMENT",
                    target == null ? null : target.getUuid(),
                    target == null ? null : preview(target),
                    target == null || !target.isVisible(),
                    report.getReasonCode(), report.getDetail(),
                    commentCounts.getOrDefault(report.getCommentId(), 0L),
                    report.getStatus(), report.getCreatedAt(),
                    handledByName, report.getResolvedAt());
        }).toList();
    }

    private String preview(CommunityComment comment) {
        return comment.getBody().length() <= 80 ? comment.getBody() : comment.getBody().substring(0, 80);
    }

    // 승인 = 대상 블라인드 + **같은 대상의 PENDING 신고 전부 RESOLVED** —
    // 신고 10건짜리 글을 10번 처리하게 하지 않는다.
    @Transactional
    public void resolve(String adminUuid, String reportUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        CommunityReport report = getReport(reportUuid);
        LocalDateTime now = LocalDateTime.now(clock);

        // 지목된 신고를 먼저 처리한다 — 이미 처리된 신고면 여기서 409로 끊긴다.
        report.resolve(admin.getId(), now);

        List<CommunityReport> siblings;
        if (report.getPostId() != null) {
            blindPost(report.getPostId());
            siblings = reportRepository.findByPostIdAndStatus(report.getPostId(), ReportStatus.PENDING);
        } else {
            blindComment(report.getCommentId());
            siblings = reportRepository.findByCommentIdAndStatus(report.getCommentId(), ReportStatus.PENDING);
        }
        siblings.forEach(sibling -> sibling.resolve(admin.getId(), now));

        log.info("[community] 신고 승인·블라인드 report={} 동시처리={}건", reportUuid, 1 + siblings.size());
    }

    // 기각은 그 신고 한 건만 닫는다 — 다른 신고자의 눈은 다른 판단일 수 있다.
    @Transactional
    public void dismiss(String adminUuid, String reportUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        CommunityReport report = getReport(reportUuid);
        report.dismiss(admin.getId(), LocalDateTime.now(clock));
        log.info("[community] 신고 기각 report={}", reportUuid);
    }

    // 신고 없이도 순찰로 잡은 콘텐츠를 내릴 수 있어야 한다. 멱등 — 이미 그 상태면 조용히 지나간다.
    @Transactional
    public void blindPost(String postUuid) {
        blindPost(postRepository.findByUuid(postUuid)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND)).getId());
    }

    @Transactional
    public void unblindPost(String postUuid) {
        CommunityPost post = postRepository.findByUuid(postUuid)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));
        post.unblind();
    }

    @Transactional
    public void blindComment(String commentUuid) {
        CommunityComment comment = commentRepository.findByUuid(commentUuid)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
        blindComment(comment.getId());
    }

    @Transactional
    public void unblindComment(String commentUuid) {
        CommunityComment comment = commentRepository.findByUuid(commentUuid)
                .orElseThrow(() -> new CommunityException(COMMENT_NOT_FOUND));
        if (comment.getStatus() != CommunityContentStatus.ACTIVE) {
            comment.unblind();
            // 유저가 삭제한 댓글은 표시 개수에 없다(삭제 시점에 이미 내렸다) — 다시 올리면
            // 유령 1이 생긴다. 카운트는 "보이게 되는" 해제에만 움직인다.
            if (comment.getDeletedAt() == null) {
                postRepository.increaseCommentCount(comment.getPostId());
            }
        }
    }

    private void blindPost(Long postId) {
        postRepository.findById(postId).ifPresent(CommunityPost::blind);
    }

    // 댓글 블라인드는 표시 개수에서 빠지므로 카운트도 내린다(스키마의 갱신 경로 명세).
    private void blindComment(Long commentId) {
        commentRepository.findById(commentId).ifPresent(comment -> {
            // 조건부 벌크 2단 — 스냅샷 판정은 동시 삭제와 겹치면 이중 감소였다(적대적 리뷰
            // 2026-09-02). ① 표시 중(ACTIVE·미삭제)이었으면 숨기고 감산, ② 이미 삭제된
            // ACTIVE 는 숨기기만 한다(삭제 시점에 이미 감산됨). 영향 행수가 판정이다.
            if (commentRepository.blindIfVisible(comment.getId()) == 1) {
                postRepository.decreaseCommentCount(comment.getPostId());
            } else {
                commentRepository.blindRegardless(comment.getId());
            }
        });
    }

    private CommunityReport getReport(String reportUuid) {
        return reportRepository.findByUuid(reportUuid)
                .orElseThrow(() -> new CommunityException(REPORT_NOT_FOUND));
    }
}
