package com.kovil.domain.user.entity;

import com.kovil.domain.user.enums.UserStatus;
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
 * 유저 상태 전이 이력 (append-only).
 *
 * users.status 는 한 칸뿐이라 덮어쓰면 과거가 사라진다. 정지 이력이 사라지면
 * "이 사람을 왜 정지했었나"를 아무도 답할 수 없다 — 재심사도 차단 판단도 근거를 잃는다.
 * **수정·삭제하지 않는다.** 정정이 필요하면 새 행을 쌓는다.
 */
@Getter
@Entity
@SuperBuilder
@Table(name = "user_status_histories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStatusHistory extends BaseTimeEntity {

    /** changed_by 만으로는 자발적 탈퇴와 관리자 조치가 구분되지 않는다. */
    public static final String ACTOR_SELF = "SELF";
    public static final String ACTOR_ADMIN = "ADMIN";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private UserStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private UserStatus toStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "actor_note", length = 100)
    private String actorNote;

    @Column(name = "reason", length = 255)
    private String reason;
}
