package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityReport;
import com.kovil.domain.community.enums.ReportStatus;
import com.kovil.domain.community.enums.ReportReasonCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface CommunityReportRepository extends JpaRepository<CommunityReport, Long> {

    Optional<CommunityReport> findByUuid(String uuid);

    boolean existsByReporterIdAndPostId(Long reporterId, Long postId);

    boolean existsByReporterIdAndCommentId(Long reporterId, Long commentId);

    // FR-905 — 관리자 처리 대기열. 오래 기다린 신고가 먼저다.
    Page<CommunityReport> findByStatusOrderByIdAsc(
            ReportStatus status, Pageable pageable);

    // 대기열 필터. postId·commentId를 함께 주면 **한 대상의 신고를 모아 본다** —
    // 신고 3건 각각의 사유 설명을 읽어야 판단이 선다.
    @Query(
            "SELECT r FROM CommunityReport r "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode) "
            + "AND (:postId IS NULL OR r.postId = :postId) "
            + "AND (:commentId IS NULL OR r.commentId = :commentId) "
            + "ORDER BY r.id ASC")
    Page<CommunityReport> searchQueue(
            @Param("status") ReportStatus status,
            @Param("reasonCode")
            ReportReasonCode reasonCode,
            @Param("postId") Long postId,
            @Param("commentId") Long commentId,
            Pageable pageable);

    // 이력(RESOLVED·DISMISSED) 조회 — **최신부터**. 잘못 블라인드한 글은 최근 처리분에서 찾는다.
    // 대기열(id ASC, 오래 기다린 순)과 축이 다른 이유다.
    @Query(
            "SELECT r FROM CommunityReport r "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode) "
            + "AND (:postId IS NULL OR r.postId = :postId) "
            + "AND (:commentId IS NULL OR r.commentId = :commentId) "
            + "ORDER BY r.id DESC")
    Page<CommunityReport> searchHistory(
            @Param("status") ReportStatus status,
            @Param("reasonCode")
            ReportReasonCode reasonCode,
            @Param("postId") Long postId,
            @Param("commentId") Long commentId,
            Pageable pageable);

    // 대상 단위 대기열 — 많이 신고된 대상이 먼저, 같으면 오래 기다린 대상이 먼저.
    // sameTargetPendingCount는 페이지를 받은 뒤의 계산값이라 정렬 키가 될 수 없다 — 집계로 정렬한다.
    @Query(
            "SELECT r.postId, r.commentId, COUNT(r), MIN(r.createdAt), MAX(r.createdAt) "
            + "FROM CommunityReport r "
            + "WHERE r.status = :status "
            + "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode) "
            + "GROUP BY r.postId, r.commentId "
            + "ORDER BY COUNT(r) DESC, MIN(r.id) ASC")
    List<Object[]> groupQueueByTarget(
            @Param("status") ReportStatus status,
            @Param("reasonCode")
            ReportReasonCode reasonCode,
            Pageable pageable);

    // 대상 수 집계 — 신고 한 건은 글·댓글 중 정확히 하나를 가리키므로 둘을 더하면 전체 대상 수다.
    @Query(
            "SELECT COUNT(DISTINCT r.postId) FROM CommunityReport r "
            + "WHERE r.status = :status AND r.postId IS NOT NULL "
            + "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode)")
    long countDistinctPostTargets(
            @Param("status") ReportStatus status,
            @Param("reasonCode")
            ReportReasonCode reasonCode);

    @Query(
            "SELECT COUNT(DISTINCT r.commentId) FROM CommunityReport r "
            + "WHERE r.status = :status AND r.commentId IS NOT NULL "
            + "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode)")
    long countDistinctCommentTargets(
            @Param("status") ReportStatus status,
            @Param("reasonCode")
            ReportReasonCode reasonCode);

    // 대기열 행의 "같은 대상 신고 수" — 페이지 단위 일괄 집계.
    @Query(
            "SELECT r.postId, COUNT(r) FROM CommunityReport r "
            + "WHERE r.status = :status AND r.postId IN :postIds GROUP BY r.postId")
    List<Object[]> countByStatusAndPostIds(
            @Param("status") ReportStatus status,
            @Param("postIds") Collection<Long> postIds);

    @Query(
            "SELECT r.commentId, COUNT(r) FROM CommunityReport r "
            + "WHERE r.status = :status AND r.commentId IN :commentIds GROUP BY r.commentId")
    List<Object[]> countByStatusAndCommentIds(
            @Param("status") ReportStatus status,
            @Param("commentIds") Collection<Long> commentIds);

    // 회원 상세의 "받은 신고" 집계. 신고 행은 대상만 가리키므로 작성자로 되짚어야 한다.
    @Query(
            "SELECT COUNT(r) FROM CommunityReport r WHERE "
            + "r.postId IN (SELECT p.id FROM CommunityPost p WHERE p.userId = :userId) "
            + "OR r.commentId IN (SELECT c.id FROM CommunityComment c WHERE c.userId = :userId)")
    long countReceivedBy(@Param("userId") Long userId);

    // 한 번의 집행이 같은 대상의 대기열을 비운다.
    List<CommunityReport> findByPostIdAndStatus(Long postId, ReportStatus status);

    List<CommunityReport> findByCommentIdAndStatus(Long commentId, ReportStatus status);
}
