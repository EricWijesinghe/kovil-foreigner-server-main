package com.kovil.domain.user.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.user.enums.AuthType;
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

@Getter
@Entity
@SuperBuilder
// 유니크는 **살아 있는 행에만** 걸린다(V19 의 생성 컬럼 active_identifier). 생성 컬럼은 H2 의
// ddl-auto 로 재현되지 않으므로 엔티티에 선언하지 않는다 — 여기에 (auth_type, identifier) 유니크를
// 적으면 테스트에만 운영에 없는 제약이 생겨, 해제된 행이 쌓이는 정상 동작을 테스트가 거짓으로 막는다.
@Table(name = "user_auth")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuth extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    private AuthType authType;

    @Column(name = "identifier", nullable = false, length = 255)
    private String identifier;

    // LOCAL만 값이 있다. 소셜은 NULL이며 비밀번호 검사 자체를 하지 않는다.
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // Apple 만 값이 있다. refresh_token 의 암호문(Encryptor)이고 탈퇴 때 Apple 철회에 쓴다.
    @Column(name = "provider_refresh_token", length = 512)
    private String providerRefreshToken;

    // 그 토큰을 낸 client_id(번들 ID 또는 Services ID). 철회는 같은 client_id 로 보내야 받아들여진다.
    @Column(name = "provider_client_id", length = 100)
    private String providerClientId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 재설정의 유일한 경로 — 새 해시로 교체한다. 평문은 이 엔티티에 절대 닿지 않는다.
    /**
     * 탈퇴 시 이 수단을 해제한다. **행도 식별자도 지우지 않는다**(PO 확정 2026-09-01).
     *
     * 식별자를 남기는 것이 핵심이다 — 재가입 때 "이 신원에 정지 이력이 있는가"를
     * 조회하는 키가 바로 이 값이다. 지우면 그 판정을 할 수 없다.
     *
     * 슬롯은 저절로 풀린다 — 유니크가 **살아 있는 행에만** 걸려 있어서(V19 의 active_identifier),
     * deleted_at 이 찍히는 순간 이 행은 슬롯을 놓는다. 재가입은 그냥 새 행을 넣으면 된다.
     */
    public void unlink(LocalDateTime at) {
        this.deletedAt = at;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    // 로그인할 때마다 code 를 다시 교환하므로 가장 최근 값으로 갈아 끼운다. 앱 · 웹 중 마지막에 들어온 쪽 것이 남는다.
    public void replaceProviderRefreshToken(String encryptedToken, String clientId) {
        this.providerRefreshToken = encryptedToken;
        this.providerClientId = clientId;
    }

    public void clearProviderRefreshToken() {
        this.providerRefreshToken = null;
        this.providerClientId = null;
    }
}
