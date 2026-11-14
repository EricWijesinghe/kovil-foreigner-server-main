package com.kovil.domain.rentalrequest.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_ALREADY_CLAIMED;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_NOT_IN_PROGRESS;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_OUTCOME_REQUIRED;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REQUEST_NOT_EDITABLE;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REQUEST_NOT_OPEN;

@Getter
@Entity
@SuperBuilder
@Table(name = "rental_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "request_number", nullable = false, updatable = false, length = 20)
    private String requestNumber;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "verification_id", nullable = false, updatable = false)
    private Long verificationId;

    @Column(name = "office_id")
    private Long officeId;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RentalRequestStatus status = RentalRequestStatus.SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private RentalRequestOutcome outcome;

    @Column(name = "move_in_date")
    private LocalDate moveInDate;

    @Builder.Default
    @Column(name = "move_in_flexible", nullable = false)
    private Boolean moveInFlexible = false;

    @Column(name = "lease_months")
    private Short leaseMonths;

    @Column(name = "deposit_min")
    private Long depositMin;

    @Column(name = "deposit_max")
    private Long depositMax;

    @Column(name = "monthly_rent_min")
    private Long monthlyRentMin;

    @Column(name = "monthly_rent_max")
    private Long monthlyRentMax;

    @Column(name = "household_size")
    private Byte householdSize;

    @Column(name = "preferred_area_note", length = 255)
    private String preferredAreaNote;

    @Column(name = "request_note", columnDefinition = "text")
    private String requestNote;

    @Column(name = "closed_reason", length = 255)
    private String closedReason;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    // 종착 상태(취소 · 종료)에서는 유학생이 손댈 수 없다. 사무소가 잡은 뒤(진행 중)에는 아직 고칠 수 있다.
    public void requireUserEditable() {
        if (!status.isUserEditable()) {
            throw new RentalRequestException(REQUEST_NOT_EDITABLE);
        }
    }

    public void updateConditions(LocalDate moveInDate, boolean moveInFlexible, Short leaseMonths,
                                 Long depositMin, Long depositMax,
                                 Long monthlyRentMin, Long monthlyRentMax,
                                 Byte householdSize, String preferredAreaNote, String requestNote) {
        requireUserEditable();
        this.moveInDate = moveInDate;
        this.moveInFlexible = moveInFlexible;
        this.leaseMonths = leaseMonths;
        this.depositMin = depositMin;
        this.depositMax = depositMax;
        this.monthlyRentMin = monthlyRentMin;
        this.monthlyRentMax = monthlyRentMax;
        this.householdSize = householdSize;
        this.preferredAreaNote = preferredAreaNote;
        this.requestNote = requestNote;
    }

    public void cancel(String reason, LocalDateTime at) {
        requireUserEditable();
        this.status = RentalRequestStatus.CANCELED;
        this.closedReason = reason;
        this.closedAt = at;
    }

    public void claim(Long officeId, LocalDateTime at) {
        if (status == RentalRequestStatus.IN_PROGRESS) {
            throw new RentalRequestException(RENTAL_REQUEST_ALREADY_CLAIMED);
        }
        if (status != RentalRequestStatus.SUBMITTED) {
            throw new RentalRequestException(REQUEST_NOT_OPEN);
        }
        this.status = RentalRequestStatus.IN_PROGRESS;
        this.officeId = officeId;
        this.claimedAt = at;
    }

    public void release(Long officeId) {
        requireHeldBy(officeId);
        returnToPool();
    }

    public void returnToPool() {
        requireInProgress();
        this.status = RentalRequestStatus.SUBMITTED;
        this.officeId = null;
        this.claimedAt = null;
    }

    public void close(Long officeId, RentalRequestOutcome outcome, LocalDateTime at) {
        if (outcome == null) {
            throw new RentalRequestException(RENTAL_REQUEST_OUTCOME_REQUIRED);
        }
        requireHeldBy(officeId);
        requireInProgress();
        this.status = RentalRequestStatus.CLOSED;
        this.outcome = outcome;
        this.closedAt = at;
    }

    private void requireHeldBy(Long officeId) {
        if (this.officeId == null || !this.officeId.equals(officeId)) {
            throw new RentalRequestException(RENTAL_REQUEST_NOT_FOUND);
        }
    }

    private void requireInProgress() {
        if (status != RentalRequestStatus.IN_PROGRESS) {
            throw new RentalRequestException(RENTAL_REQUEST_NOT_IN_PROGRESS);
        }
    }
}
