package com.kovil.domain.user.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 전화번호 평문 열람 기록. append-only.
//
// 화면이 "열람 기록이 남습니다"라고 적으려면 실제로 남아야 한다.
// 그래서 회원 상세 응답에서 평문을 빼고 전용 경로로만 내준다 — 마스킹이 UI 장식이 아니라
// 실제 경계가 되고, 이 표가 곧 열람의 전부가 된다.
@Getter
@Entity
@Table(name = "admin_phone_reveals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminPhoneReveal extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "admin_user_id", nullable = false, updatable = false)
    private Long adminUserId;

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private Long targetUserId;

    @Builder
    private AdminPhoneReveal(Long adminUserId, Long targetUserId) {
        this.adminUserId = adminUserId;
        this.targetUserId = targetUserId;
    }
}
