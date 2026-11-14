package com.kovil.domain.verification.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.exception.VerificationException;
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

import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_NOT_APPROVED;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_NOT_PENDING;

// soft delete가 없다 — 심사 감사 기록이라 삭제 개념이 없고, 반려 후에는 새 행만 생긴다.
@Getter
@Entity
@SuperBuilder
@Table(name = "foreigner_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForeignerVerification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resident_status_id", nullable = false)
    private Long residentStatusId;

    // 제출 시점 스냅샷. 마스터가 바뀌어도 "그때 무엇을 요구했나"가 보존된다(FR-203).
    @Column(name = "resident_status_code", nullable = false, length = 30)
    private String residentStatusCode;

    @Column(name = "required_document_codes", nullable = false, columnDefinition = "json")
    private String requiredDocumentCodes;

    @Column(name = "nationality_country_id", nullable = false)
    private Long nationalityCountryId;

    // 여권 영문 성명 — 계약 명의의 정본이다. users.name은 표시용일 뿐이다.
    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VerificationStatus status = VerificationStatus.PENDING_REVIEW;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;

    // 사용자 재제출과 관리자 심사가 동시에 일어나는 것을 막는다.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    public boolean isPending() {
        return status == VerificationStatus.PENDING_REVIEW;
    }

    // FR-206 — PENDING_REVIEW에서만 나갈 수 있고 되돌아오는 전이는 없다.
    // 이미 심사된 건을 다시 심사하면 감사 기록이 덮어씌워진다.
    public void approve(Long reviewerId, LocalDate expiresAt, LocalDateTime at) {
        requirePending();
        this.status = VerificationStatus.APPROVED;
        this.expiresAt = expiresAt;
        this.rejectReason = null;
        this.reviewedBy = reviewerId;
        this.reviewedAt = at;
    }

    // 사유는 유저에게 그대로 노출된다 — 비워 두면 반려 이유를 알 길이 없다.
    public void reject(Long reviewerId, String reason, LocalDateTime at) {
        requirePending();
        this.status = VerificationStatus.REJECTED;
        this.rejectReason = reason;
        this.expiresAt = null;
        this.reviewedBy = reviewerId;
        this.reviewedAt = at;
    }

    // 오승인 무효화. **심사 기록(reviewed_by/at)은 건드리지 않는다** —
    // 덮어쓰면 원래 누가 승인했는지가 사라져 무효화 자체를 감사할 수 없다.
    public void revoke(Long adminId, String reason, LocalDateTime at) {
        if (status != VerificationStatus.APPROVED) {
            throw new VerificationException(VERIFICATION_NOT_APPROVED);
        }
        this.status = VerificationStatus.REVOKED;
        this.revokedBy = adminId;
        this.revokedAt = at;
        this.revokeReason = reason;
    }

    private void requirePending() {
        if (status != VerificationStatus.PENDING_REVIEW) {
            throw new VerificationException(VERIFICATION_NOT_PENDING);
        }
    }

    // FR-208 — "이 유저가 인증되었는가"의 판정. 만료일이 없으면 무기한이다.
    public boolean isEffectiveOn(LocalDate date) {
        return status == VerificationStatus.APPROVED
                && (expiresAt == null || !expiresAt.isBefore(date));
    }
}
