package com.kovil.domain.user.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.user.enums.ConsentType;
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

// append-only 이력이다(FR-111). 철회도 UPDATE가 아니라 agreed=0인 새 행이며,
// (user_id, type)의 최신 행이 현재 상태다 — 개보법 보존 의무 때문에 지우지 않는다.
@Getter
@Entity
@SuperBuilder
@Table(name = "user_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30)
    private ConsentType consentType;

    @Column(name = "agreed", nullable = false)
    private Boolean agreed;

    // 약관 개정 시 재동의 판정 축. 본문이 아직 없어 지금은 클라이언트가 보내는 값을 그대로 받는다.
    @Column(name = "version", length = 20)
    private String version;
}
