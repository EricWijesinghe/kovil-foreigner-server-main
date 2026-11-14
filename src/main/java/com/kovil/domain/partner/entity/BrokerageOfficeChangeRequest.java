package com.kovil.domain.partner.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.exception.PartnerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.kovil.domain.partner.exception.PartnerErrorCode.OFFICE_CHANGE_REQUEST_CHANGED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE;

/*
 * 승인된 중개사무소의 정보 변경 신청. 바꿀 값 한 벌(등록번호 · 상호 · 대표자 · 소재지 · 사업자번호 · 등록증)을 따로 들고 있다가
 * 승인되면 사무소에 적용한다(BrokerageOffice.applyApprovedChange). 반려되면 사무소는 그대로다.
 *
 * ★ 이 행은 부문도 사무소도 건드리지 않는다 — 심사 동안 그 사무소 매물이 공개 그대로이고 기능도 그대로인 이유다
 *   (공개 조회와 게시 관문은 부문 상태만 본다). 부문을 대기로 돌리던 옛 재심사는 영업 중인 사무소를 멈췄다.
 * ★ 대기 건은 사무소당 하나다 — 제출 쪽이 사무소 행을 잠그고 대기 건을 잠금 읽기로 찾아 있으면 덮는다(DB 제약이 없다).
 * ★ 대표번호는 여기 없다 — 심사 없이 바로 바뀌는 칸이라 변경 신청과 갈린다.
 * ★ 판정이 난 건은 지우지 않는다 — 무엇을 언제 바꿨는지가 남아야 한다.
 */
@Getter
@Entity
@SuperBuilder
@Table(name = "brokerage_office_change_requests",
        uniqueConstraints = @UniqueConstraint(name = "uq_bocr_uuid", columnNames = "uuid"),
        indexes = {
                @Index(name = "idx_bocr_office", columnList = "office_id, status, id"),
                @Index(name = "idx_bocr_queue", columnList = "status, submitted_at, id"),
                @Index(name = "idx_bocr_reg_no", columnList = "reg_no, status")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerageOfficeChangeRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "office_id", nullable = false, updatable = false)
    private Long officeId;

    // 신청한 사람 = 그 사무소의 대표자(사무소당 계정 하나). 승인 · 반려 알림을 받는다.
    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "reg_no", nullable = false, length = 20)
    private String regNo;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "ceo_name", nullable = false, length = 50)
    private String ceoName;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "biz_no", length = 12)
    private String bizNo;

    // 지금 사무소의 등록증을 그대로 낼 수도 있다 — 그래서 반려 · 덮어쓰기 때 "사무소가 쓰는 파일인가"를 보고 버린다.
    @Column(name = "license_file_id", nullable = false)
    private Long licenseFileId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OfficeChangeRequestStatus status = OfficeChangeRequestStatus.PENDING;

    // 마지막으로 낸 시각. 대기 중에 다시 내면 새로 찍혀 대기열 뒤로 간다 — 심사자가 보던 값이 바뀌었으니 처음부터 본다.
    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    // 탈퇴로 닫힌 건은 심사자가 비어 있다 — 사람이 판단한 반려가 아니다.
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // 관리자가 화면에서 본 값과 대조한다(assertVersion) — @Version 은 한 요청 안만 지키고, 상세를 본 뒤 승인을 누르기까지의
    // 사이(그동안의 덮어쓰기)는 지키지 못한다. 겹친 처리의 늦은 쪽은 커밋 때 이 칸이 409 로 돌려보낸다.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isPending() {
        return status == OfficeChangeRequestStatus.PENDING;
    }

    /*
     * 대기 중에 다시 냈다 — 바꿀 값을 통째로 덮고 제출 시각을 새로 찍는다. 값이 바뀌므로 version 도 오르고,
     * 그 전에 상세를 열어 둔 관리자의 승인 · 반려는 409 로 돌아간다(본 적 없는 값을 승인하지 않게).
     */
    public void overwrite(String regNo, String name, String ceoName, String address, String bizNo,
                          Long licenseFileId, LocalDateTime at) {
        assertReviewable();
        this.regNo = regNo;
        this.name = name;
        this.ceoName = ceoName;
        this.address = address;
        this.bizNo = bizNo;
        this.licenseFileId = licenseFileId;
        this.submittedAt = at;
    }

    // 관리자가 화면에서 본 판과 지금 판이 같은가. null 은 요청 검증이 먼저 막는다.
    public void assertVersion(Long seenVersion) {
        if (!Objects.equals(version, seenVersion)) {
            throw new PartnerException(OFFICE_CHANGE_REQUEST_CHANGED);
        }
    }

    public void assertReviewable() {
        if (status != OfficeChangeRequestStatus.PENDING) {
            throw new PartnerException(OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE);
        }
    }

    // 승인 — 사무소에 값을 적용하는 것은 서비스가 같은 트랜잭션에서 한다(번호 이동의 경합을 거기서 막는다).
    public void approve(Long adminId, LocalDateTime at) {
        assertReviewable();
        this.status = OfficeChangeRequestStatus.APPROVED;
        markReviewed(adminId, at);
    }

    public void reject(Long adminId, String reason, LocalDateTime at) {
        assertReviewable();
        this.status = OfficeChangeRequestStatus.REJECTED;
        this.rejectReason = reason;
        markReviewed(adminId, at);
    }

    public void cancel() {
        assertReviewable();
        this.status = OfficeChangeRequestStatus.CANCELED;
    }

    // 탈퇴로 닫는다 — 대기 중인 건만, 심사자는 비운다. 닫지 않으면 대기열에 처리할 수 없는 익명 행이 남는다.
    public void closeOnWithdrawal(String reason, LocalDateTime at) {
        if (status != OfficeChangeRequestStatus.PENDING) {
            return;
        }
        this.status = OfficeChangeRequestStatus.REJECTED;
        this.rejectReason = reason;
        markReviewed(null, at);
    }

    private void markReviewed(Long adminId, LocalDateTime at) {
        this.reviewedBy = adminId;
        this.reviewedAt = at;
    }
}
