package com.kovil.domain.community.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.community.entity.CommunityReport;
import com.kovil.domain.community.enums.ReportReasonCode;
import com.kovil.domain.community.enums.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// GROUP BY 정렬은 서비스 테스트(리포지토리 목킹)가 못 잡는다 — 진짜 DB에 붙여 고정한다.
// sameTargetPendingCount가 "페이지를 받은 뒤의 계산값"이라 정렬 키가 될 수 없었던 것이
// 이 집계 쿼리를 만든 이유다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class CommunityReportQueueRepositoryTest {

    @Autowired
    CommunityReportRepository reportRepository;

    // 대상 구성: 글100=신고3, 글200=신고1, 댓글300=신고2. 글900은 처리 완료(집계 제외).
    @BeforeEach
    void seed() {
        save(100L, null, ReportReasonCode.SPAM, ReportStatus.PENDING);
        save(100L, null, ReportReasonCode.ABUSE, ReportStatus.PENDING);
        save(100L, null, ReportReasonCode.SPAM, ReportStatus.PENDING);
        save(200L, null, ReportReasonCode.SPAM, ReportStatus.PENDING);
        save(null, 300L, ReportReasonCode.ABUSE, ReportStatus.PENDING);
        save(null, 300L, ReportReasonCode.ABUSE, ReportStatus.PENDING);
        save(900L, null, ReportReasonCode.SPAM, ReportStatus.RESOLVED);
    }

    private void save(Long postId, Long commentId, ReportReasonCode reason, ReportStatus status) {
        reportRepository.save(CommunityReport.builder()
                .reporterId(50L).postId(postId).commentId(commentId)
                .reasonCode(reason).status(status).build());
    }

    @Test
    @DisplayName("많이 신고된 대상이 먼저다 — 같으면 오래 기다린 대상이 먼저")
    void grouped_queue_orders_by_count_then_age() {
        List<Object[]> rows = reportRepository.groupQueueByTarget(
                ReportStatus.PENDING, null, PageRequest.of(0, 10));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)[0]).isEqualTo(100L); // 신고 3건
        assertThat((Long) rows.get(0)[2]).isEqualTo(3L);
        assertThat(rows.get(1)[1]).isEqualTo(300L); // 댓글, 신고 2건
        assertThat((Long) rows.get(1)[2]).isEqualTo(2L);
        assertThat(rows.get(2)[0]).isEqualTo(200L); // 신고 1건
    }

    @Test
    @DisplayName("처리 완료된 대상은 대기열 집계에 나오지 않는다")
    void grouped_queue_excludes_handled() {
        List<Object[]> rows = reportRepository.groupQueueByTarget(
                ReportStatus.PENDING, null, PageRequest.of(0, 10));
        assertThat(rows).extracting(row -> row[0]).doesNotContain(900L);
    }

    @Test
    @DisplayName("사유 필터가 집계에도 적용된다")
    void grouped_queue_respects_reason_filter() {
        List<Object[]> rows = reportRepository.groupQueueByTarget(
                ReportStatus.PENDING, ReportReasonCode.ABUSE, PageRequest.of(0, 10));
        // ABUSE: 글100 1건 · 댓글300 2건 → 댓글이 먼저
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[1]).isEqualTo(300L);
        assertThat((Long) rows.get(0)[2]).isEqualTo(2L);
    }

    // 페이지 total은 별도 집계로 만든다 — 글 대상 수 + 댓글 대상 수(신고는 정확히 한쪽만 가리킨다).
    @Test
    @DisplayName("전체 대상 수 = 글 대상 수 + 댓글 대상 수")
    void distinct_target_counts_add_up() {
        long posts = reportRepository.countDistinctPostTargets(ReportStatus.PENDING, null);
        long comments = reportRepository.countDistinctCommentTargets(ReportStatus.PENDING, null);
        assertThat(posts).isEqualTo(2);
        assertThat(comments).isEqualTo(1);
    }

    @Test
    @DisplayName("페이지 크기를 넘는 대상은 다음 페이지로 밀린다")
    void grouped_queue_paginates() {
        List<Object[]> first = reportRepository.groupQueueByTarget(
                ReportStatus.PENDING, null, PageRequest.of(0, 2));
        List<Object[]> second = reportRepository.groupQueueByTarget(
                ReportStatus.PENDING, null, PageRequest.of(1, 2));
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(second.get(0)[0]).isEqualTo(200L);
    }

    // 대기열은 오래 기다린 순(id ASC)·이력은 최신부터(id DESC) — 잘못 블라인드한 글은
    // 최근 처리분에서 찾는다. 두 축이 다른 이유가 여기 고정된다.
    @Test
    @DisplayName("이력 조회는 최신부터 내려온다")
    void history_is_newest_first() {
        save(901L, null, ReportReasonCode.SPAM, ReportStatus.RESOLVED);

        var page = reportRepository.searchHistory(
                ReportStatus.RESOLVED, null, null, null, PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getPostId()).isEqualTo(901L);
        assertThat(page.getContent().get(1).getPostId()).isEqualTo(900L);
    }
}
