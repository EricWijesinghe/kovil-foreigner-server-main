package com.kovil.domain.partner.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.exception.PartnerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_DIVISION_CHANGED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_DIVISION_NOT_APPROVED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE;
import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_DIVISION_NOT_REVIEWABLE;
import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_DIVISION_NOT_SUSPENDED;

/*
 * 파트너 한 사람의 한 부문. 사람 × 부문마다 행 하나다(uq_pd_user_division).
 *
 * ★ 승인 · 정지의 축은 여기 하나다. 매물 공개 조회 · 게시 관문 · 토큰의 divisions 클레임이 전부
 *   이 행의 status 를 본다 — 중개사무소에는 상태가 없다.
 * ★ 탈퇴해도 행을 지우지 않는다. 누가 언제 무엇으로 승인됐는지가 남아야 한다.
 */
@Getter
@Entity
@SuperBuilder
@Table(name = "partner_divisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartnerDivision extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // ★ 상수를 지우면 그 값을 가진 행을 읽는 순간 로딩이 깨진다 — 부문을 접을 때는 지우지 않고 은퇴시킨다.
    @Enumerated(EnumType.STRING)
    @Column(name = "division", nullable = false, updatable = false, length = 30)
    private PartnerDivisionType division;

    /*
     * 이 부문의 중개사무소(공인중개사 부문만). 가입 때 채우고 **탈퇴해도 지우지 않는다** — 대표자 연결(broker_profiles)은
     * 탈퇴가 지우므로, 탈퇴한 파트너의 승인 부문을 정지할 때 정지 이력이 번호를 찾을 곳이 이것뿐이다.
     * updatable = false: 부문과 사무소의 짝은 바뀌지 않는다. 낡은 엔티티의 UPDATE 가 마이그레이션이 채운 값을 덮지도 않는다.
     */
    @Column(name = "office_id", updatable = false)
    private Long officeId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PartnerDivisionStatus status = PartnerDivisionStatus.PENDING;

    // 서류까지 낸 시각. 비어 있으면 가입만 하고 아직 내지 않은 것이라 심사 대기열에 나오지 않는다.
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    // 마지막 반려 사유. 신청자에게 그대로 보여 준다. 고쳐 다시 내도 지우지 않는다 —
    // 심사자가 재제출 건을 볼 때 "지난번에 왜 반려했나"가 근거가 된다.
    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    // 마지막 정지 사유. 관리자 내부 기록이다(파트너 응답에 싣지 않는다). 복구해도 지우지 않는다 —
    // 이력 테이블이 없어 지우면 "왜 정지했었나"가 사라진다.
    @Column(name = "suspend_reason", length = 255)
    private String suspendReason;

    // 마지막으로 상태를 바꾼 관리자와 시각. 탈퇴로 닫힌 건은 심사자가 비어 있다 — 사람이 판단한 반려가 아니다.
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // 마지막 승인 · 복구 시각. 승인된 부문은 대기로 돌아가지 않는다(승인 뒤 변경은 변경 신청이 받는다) — 대기 중인데 값이
    // 있으면 이전 방식의 재심사로 남은 행이다.
    @Column(name = "last_approved_at")
    private LocalDateTime lastApprovedAt;

    // 두 관리자가 같은 건을 동시에 처리하거나, 심사와 재제출이 겹치면 늦은 쪽을 409 로 돌려보낸다.
    // 관리자가 화면에서 본 값과도 대조한다(assertVersion) — @Version 은 한 요청 안만 지키고, 상세를 본 뒤
    // 승인을 누르기까지의 사이(그동안의 재제출)는 지키지 못한다.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isApproved() {
        return status == PartnerDivisionStatus.APPROVED;
    }

    public boolean isPending() {
        return status == PartnerDivisionStatus.PENDING;
    }

    public boolean isSubmitted() {
        return submittedAt != null;
    }

    /*
     * ===== 상태 전이 — 엔티티가 소유한다(이 저장소 관례) =====
     */

    /*
     * 첫 심사의 제출 · 재제출. 반려된 건은 고쳐 내면 다시 대기열로 간다.
     * ★ 승인된 건은 받지 않는다(PO 확정 2026-10-07) — 대기로 돌아가는 순간 영업 중인 사무소의 매물 · 기능이 멈춘다.
     *   승인 뒤의 변경은 부문을 건드리지 않는 변경 신청이 받는다(서비스가 승인 상태를 보고 그쪽으로 보낸다).
     */
    public void submit(LocalDateTime at) {
        assertEditable();
        this.status = PartnerDivisionStatus.PENDING;
        this.submittedAt = at;
    }

    // 서류를 고쳐 낼 수 있는가 — 제출 화면이 서류와 사무소 정보를 바꾸기 전에 묻는다. 대기 · 반려만 된다.
    // 정지: 서류를 바꿔 스스로 정지를 풀 수 없다. 승인: 변경 신청으로 가야 한다 — 여기서 받으면 부문이 대기로 떨어진다.
    public void assertEditable() {
        if (status == PartnerDivisionStatus.SUSPENDED || status == PartnerDivisionStatus.APPROVED) {
            throw new PartnerException(PARTNER_DIVISION_NOT_EDITABLE);
        }
    }

    // 관리자가 화면에서 본 판과 지금 판이 같은가. 다르면 그사이 재제출 · 다른 심사가 있었다 — 본 적 없는 서류를
    // 승인하지 않게 다시 보게 한다. null 은 요청 검증이 먼저 막는다.
    public void assertVersion(Long seenVersion) {
        if (!Objects.equals(version, seenVersion)) {
            throw new PartnerException(PARTNER_DIVISION_CHANGED);
        }
    }

    public void assertReviewable() {
        if (status != PartnerDivisionStatus.PENDING || submittedAt == null) {
            throw new PartnerException(PARTNER_DIVISION_NOT_REVIEWABLE);
        }
    }

    public void approve(Long adminId, LocalDateTime at) {
        assertReviewable();
        this.status = PartnerDivisionStatus.APPROVED;
        this.lastApprovedAt = at;
        markReviewed(adminId, at);
    }

    public void reject(Long adminId, String reason, LocalDateTime at) {
        assertReviewable();
        this.status = PartnerDivisionStatus.REJECTED;
        this.rejectReason = reason;
        markReviewed(adminId, at);
    }

    /*
     * 탈퇴로 닫는다 — 대기 중인 건이면 제출 여부와 관계없이 반려로 닫고 심사자는 비운다.
     * 닫지 않으면 대기열에 승인할 수 없는 익명 행이 남는다(인증 서류를 탈퇴로 닫는 것과 같은 판단).
     * 승인 · 정지 · 반려된 건은 그대로 둔다 — 이미 판정이 난 이력이다.
     */
    public void closeOnWithdrawal(String reason, LocalDateTime at) {
        if (status != PartnerDivisionStatus.PENDING) {
            return;
        }
        this.status = PartnerDivisionStatus.REJECTED;
        this.rejectReason = reason;
        markReviewed(null, at);
    }

    public void assertSuspendable() {
        if (status != PartnerDivisionStatus.APPROVED) {
            throw new PartnerException(PARTNER_DIVISION_NOT_APPROVED);
        }
    }

    // 정지. 그 사무소 공개 매물이 노출에서 빠진다 — 공개 조회가 이 상태를 보므로 매물은 건드리지 않는다
    // (매물까지 내리면 복구할 때 "정지 때문에 내려간 것"과 "중개사가 내린 것"을 가를 수 없다).
    public void suspend(Long adminId, String reason, LocalDateTime at) {
        assertSuspendable();
        this.status = PartnerDivisionStatus.SUSPENDED;
        this.suspendReason = reason;
        markReviewed(adminId, at);
    }

    public void assertReinstatable() {
        if (status != PartnerDivisionStatus.SUSPENDED) {
            throw new PartnerException(PARTNER_DIVISION_NOT_SUSPENDED);
        }
    }

    public void reinstate(Long adminId, LocalDateTime at) {
        assertReinstatable();
        this.status = PartnerDivisionStatus.APPROVED;
        this.lastApprovedAt = at;
        markReviewed(adminId, at);
    }

    private void markReviewed(Long adminId, LocalDateTime at) {
        this.reviewedBy = adminId;
        this.reviewedAt = at;
    }
}
