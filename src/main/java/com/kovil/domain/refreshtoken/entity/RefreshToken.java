package com.kovil.domain.refreshtoken.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

// 원문은 어디에도 저장하지 않는다 — 조회도 HMAC 해시로 한다(FR-113).
// 회전에 쓰인 토큰(ROTATED)이 다시 제시되면 탈취로 판정한다. REVOKED 는 정상 종료(로그아웃 · 세션 상한 · 전 세션 끊기)라 탈취가 아니다.
// parent_token_id 는 감사용 회전 계보다.
@Getter
@Entity
@SuperBuilder
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefreshTokenStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_token_id")
    private RefreshToken parentToken;

    public boolean isActive() {
        return status == RefreshTokenStatus.ACTIVE;
    }

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    public void rotate(LocalDateTime at) {
        this.status = RefreshTokenStatus.ROTATED;
        this.revokedAt = at;
    }

    public void revoke(LocalDateTime at) {
        this.status = RefreshTokenStatus.REVOKED;
        this.revokedAt = at;
    }

    public void expire(LocalDateTime at) {
        this.status = RefreshTokenStatus.EXPIRED;
        this.revokedAt = at;
    }
}
