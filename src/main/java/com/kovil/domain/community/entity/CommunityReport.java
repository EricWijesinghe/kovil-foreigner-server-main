package com.kovil.domain.community.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.community.enums.ReportReasonCode;
import com.kovil.domain.community.enums.ReportStatus;
import com.kovil.domain.community.exception.CommunityException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.kovil.domain.community.exception.CommunityErrorCode.REPORT_ALREADY_HANDLED;

// FR-714 — 신고는 이력이다. 노출 판정은 대상(status)이 소유하고, 이 행은 처리 후 불변이다.
@Getter
@Entity
@SuperBuilder
@Table(name = "community_reports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    // 글·댓글 중 하나만 채운다 — 경로가 갈라 준다.
    @Column(name = "post_id", updatable = false)
    private Long postId;

    @Column(name = "comment_id", updatable = false)
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, updatable = false, length = 30)
    private ReportReasonCode reasonCode;

    @Column(name = "detail", updatable = false, length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public void resolve(Long adminId, LocalDateTime at) {
        requirePending();
        this.status = ReportStatus.RESOLVED;
        this.resolvedBy = adminId;
        this.resolvedAt = at;
    }

    public void dismiss(Long adminId, LocalDateTime at) {
        requirePending();
        this.status = ReportStatus.DISMISSED;
        this.resolvedBy = adminId;
        this.resolvedAt = at;
    }

    private void requirePending() {
        if (this.status != ReportStatus.PENDING) {
            throw new CommunityException(REPORT_ALREADY_HANDLED);
        }
    }

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }
}
