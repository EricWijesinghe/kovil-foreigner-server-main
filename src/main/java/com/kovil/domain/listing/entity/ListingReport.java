package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.listing.enums.ListingReportReason;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.exception.ListingException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_REPORT_ALREADY_HANDLED;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_reports", uniqueConstraints = @UniqueConstraint(
        name = "uq_lrep_listing_reporter", columnNames = {"listing_id", "reporter_user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, updatable = false, length = 30)
    private ListingReportReason reasonCode;

    @Column(name = "detail", updatable = false, length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ListingReportStatus status;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    public void resolve(Long adminId, LocalDateTime at) {
        requirePending();
        this.status = ListingReportStatus.RESOLVED;
        this.handledBy = adminId;
        this.handledAt = at;
    }

    public void dismiss(Long adminId, LocalDateTime at) {
        requirePending();
        this.status = ListingReportStatus.DISMISSED;
        this.handledBy = adminId;
        this.handledAt = at;
    }

    private void requirePending() {
        if (this.status != ListingReportStatus.PENDING) {
            throw new ListingException(LISTING_REPORT_ALREADY_HANDLED);
        }
    }

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }
}