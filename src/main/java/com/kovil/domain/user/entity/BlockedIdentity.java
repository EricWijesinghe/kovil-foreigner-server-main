package com.kovil.domain.user.entity;

import com.kovil.domain.user.enums.BlockedIdentityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.kovil.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 재가입 차단 목록의 한 행.
 *
 * ★ 존재 이유 — 탈퇴는 신원을 풀어 준다(phone_hash 해제, user_auth 해제). 그래야 재가입이 된다.
 *   그런데 그 해제가 정지된 사람에게도 열리면 **정지 → 탈퇴 → 재가입** 이 그대로 세탁 경로가 된다.
 *   신원을 계속 붙잡아 두는 방식으로 막으면 탈퇴자가 식별자를 계속 들고 있게 된다 —
 *   그래서 탈퇴는 그대로 허용하고, 막아야 할 신원만 이 목록으로 옮긴다.
 *
 * 차단은 전부 **영구**다(PO 확정 2026-09-01). 기간제(N일 정지)는 두지 않는다 —
 * 만료 개념을 넣으면 앱 판정과 DB 의 active_guard 가 어긋나 재차단이 유니크 위반으로 터진다.
 *
 * 해제는 **행을 지우지 않고** released_at 으로 남긴다. 지우면 왜 풀렸는지가 사라진다.
 * DB 의 active_guard 생성 컬럼(유니크)이 같은 신원에 활성 차단이 둘 생기는 것을 막는다 —
 * 애플리케이션은 그 컬럼을 매핑하지 않는다(읽을 일이 없고, 판정은 아래 조건으로 한다).
 */
@Getter
@Entity
@SuperBuilder
@Table(name = "blocked_identities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlockedIdentity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 20)
    private BlockedIdentityType identityType;

    @Column(name = "identity_value", nullable = false, length = 255)
    private String identityValue;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "source_user_id")
    private Long sourceUserId;

    @Column(name = "blocked_by")
    private Long blockedBy;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "released_by")
    private Long releasedBy;

    /** 오탐 구제·정지 해제. 행은 남는다. */
    public void release(Long releasedBy, LocalDateTime at) {
        this.releasedAt = at;
        this.releasedBy = releasedBy;
    }
}
