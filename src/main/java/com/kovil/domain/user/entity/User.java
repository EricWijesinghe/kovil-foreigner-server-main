package com.kovil.domain.user.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
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

@Getter
@Entity
@SuperBuilder
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "nickname", length = 50)
    private String nickname;

    @Column(name = "nickname_normalized", length = 50)
    private String nicknameNormalized;

    @Builder.Default
    @Column(name = "is_nickname_custom", nullable = false)
    private Boolean isNicknameCustom = false;

    @Column(name = "nickname_changed_at")
    private LocalDateTime nicknameChangedAt;

    @Column(name = "phone", length = 255)
    private String phone;

    // 1인 1계정 판정 축. 탈퇴 시 NULL로 풀어 같은 번호의 재가입을 허용한다(FR-110).
    @Column(name = "phone_hash", length = 255)
    private String phoneHash;

    @Column(name = "phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "nationality_country_id")
    private Long nationalityCountryId;

    @Builder.Default
    @Column(name = "preferred_language", nullable = false, length = 10)
    private String preferredLanguage = "en";

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    // ★ 내부용. 유저 응답에 실리지 않는다 — 정지 화면은 "고객센터 문의" 하나로 응대한다.
    @Column(name = "suspend_reason", length = 255)
    private String suspendReason;

    // FR-907 — 정지는 로그인과 모든 API를 막는다(관문은 getActiveUser 한 곳).
    public void suspend(String reason, LocalDateTime at) {
        this.status = UserStatus.SUSPENDED;
        this.suspendedAt = at;
        this.suspendReason = reason;
    }

    // 해제하면 시각·사유를 비운다 — 남겨 두면 지금 정지 중인 것처럼 읽힌다.
    public void unsuspend() {
        this.status = UserStatus.ACTIVE;
        this.suspendedAt = null;
        this.suspendReason = null;
    }

    // 낙관적 락. 없으면 **탈퇴가 되살아난다** — JPA 는 @DynamicUpdate 가 없으면 UPDATE 에 전
    // 컬럼을 싣기 때문에, 관리자가 이 행을 읽은 뒤 커밋하기 전에 사용자가 탈퇴하면 관리자 커밋이
    // 낡은 스냅샷으로 전부 덮어써 deleted_at 이 풀리고 NULL 로 지웠던 이름·전화·이메일이 돌아온다.
    // 지운 개인정보가 부활하는 사고라 상태 한 칸의 문제가 아니다(적대적 리뷰 2026-09-02).
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isActive() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }

    // 탈퇴 계정의 번호 슬롯을 비운다 — 유니크 제약이 재가입을 막고 있기 때문이다.
    public void releasePhoneHash() {
        this.phoneHash = null;
    }

    // 역할 변경은 관리자만 한다(Permission.ROLE_ASSIGN). 승인·승급이 모두 여기를 지난다 —
    // 두 경로가 각자 필드를 건드리면 한쪽만 이력을 남기는 일이 생긴다.
    public void changeRole(UserRole nextRole) {
        this.role = nextRole;
    }

    public void changePreferredLanguage(String language) {
        this.preferredLanguage = language;
    }

    public void changeName(String name) {
        this.name = name;
    }

    public void changeNationality(Long countryId) {
        this.nationalityCountryId = countryId;
    }

    public void changeEmailIfAbsent(String email) {
        if (this.email == null && email != null && !email.isBlank()) {
            this.email = email;
        }
    }

    public void changeNickname(String nickname, String normalized, boolean custom, LocalDateTime at) {
        this.nickname = nickname;
        this.nicknameNormalized = normalized;
        this.isNicknameCustom = custom;
        this.nicknameChangedAt = at;
    }

    // FR-110 — PII를 지우고 번호 슬롯을 풀어 같은 번호의 재가입을 허용한다.
    // 계약·수납·커뮤니티 이력은 FK가 잡고 있어 그대로 남는다(하드 삭제 금지).
    // 닉네임은 지우지 않는다 — 커뮤니티 글의 표시명 축이고, 자동 생성값은 PII가 아니다.
    // 다만 사용자가 직접 넣은 닉네임은 실명일 수 있어 호출부가 자동 생성값으로 갈아준다.
    public void withdraw(LocalDateTime at) {
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = at;
        this.name = null;
        this.phone = null;
        this.phoneHash = null;
        this.email = null;
        this.profileImageUrl = null;
    }
}
