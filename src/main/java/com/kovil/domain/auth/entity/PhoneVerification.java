package com.kovil.domain.auth.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.auth.enums.VerificationPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

// 인증 "성공" 이력만 남긴다. 인증번호도, 실패 시도도 DB에 오지 않는다(FR-102) —
// 실패를 적재하면 대량 발송 공격이 그대로 테이블 증식이 된다.
@Getter
@Entity
@SuperBuilder
@Table(name = "phone_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneVerification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "phone", nullable = false, length = 255)
    private String phone;

    @Column(name = "phone_hash", nullable = false, length = 255)
    private String phoneHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private VerificationPurpose purpose;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    // NULL = 아직 이 인증으로 가입·변경이 완료되지 않았다. 재사용 차단의 판정 축(FR-105).
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(Long userId, LocalDateTime at) {
        this.userId = userId;
        this.consumedAt = at;
    }
}
