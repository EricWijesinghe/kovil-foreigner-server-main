package com.kovil.domain.partner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/*
 * 정지된 중개등록번호의 이력(append-only). 공인중개사 부문을 정지할 때마다 한 행.
 *
 * ★ 막는 장치가 아니라 **경고**의 근거다(PO 확정 2026-10-07) — 같은 번호로 신청 · 변경 신청이 오면 심사 화면이
 *   "정지 이력이 있는 중개등록번호"라고 보여 주고, 승인 여부는 관리자가 판단한다. 번호 중복 자체는 승인에서 막는다.
 * ★ 번호로도, 그 사무소로도 찾는다(심사 화면이 OR 로 묻는다). 번호만 보면 정지됐던 사무소가 변경 승인으로 번호를 바꿨을 때
 *   자기 경고가 사라지고, 사무소만 보면 대표자가 떠나 번호가 풀린 뒤 다른 사람이 같은 번호로 올 때 이력이 이어지지 않는다.
 * ★ 수정 · 삭제하지 않는다. 복구해도 남는다 — "언제 왜 정지됐었나"가 다음 심사의 근거다.
 */
@Getter
@Entity
@SuperBuilder
@Table(name = "suspended_reg_numbers", indexes = {
        @Index(name = "idx_srn_reg_no", columnList = "reg_no"),
        @Index(name = "idx_srn_office", columnList = "office_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SuspendedRegNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "reg_no", nullable = false, updatable = false, length = 20)
    private String regNo;

    @Column(name = "office_id", nullable = false, updatable = false)
    private Long officeId;

    @Column(name = "division_id", nullable = false, updatable = false)
    private Long divisionId;

    @Column(name = "suspended_by", updatable = false)
    private Long suspendedBy;

    // 관리자 내부 기록 — 파트너 응답 · 알림에 싣지 않는다(부문의 정지 사유와 같다).
    @Column(name = "reason", updatable = false, length = 255)
    private String reason;

    @Column(name = "suspended_at", nullable = false, updatable = false)
    private LocalDateTime suspendedAt;
}
