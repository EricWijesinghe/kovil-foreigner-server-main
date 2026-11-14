package com.kovil.domain.community.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.community.enums.CommunityContentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@SuperBuilder
@Table(name = "community_comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityComment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    // NULL = 최상위. 깊이 2 고정 — 부모가 이미 자식이면 서비스가 거부한다.
    @Column(name = "parent_comment_id", updatable = false)
    private Long parentCommentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // 작성 시점 별칭 스냅샷(0 = 글쓴이). 목록에서 별칭 테이블 조인을 피한다.
    @Column(name = "anonymous_no", updatable = false)
    private Short anonymousNo;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityContentStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isVisible() {
        return status == CommunityContentStatus.ACTIVE && deletedAt == null;
    }

    public boolean isOwnedBy(Long viewerId) {
        return userId.equals(viewerId);
    }

    public boolean isTopLevel() {
        return parentCommentId == null;
    }

    public void blind() {
        this.status = CommunityContentStatus.HIDDEN;
    }

    public void unblind() {
        this.status = CommunityContentStatus.ACTIVE;
    }

    public void increaseLikeCount() {
        this.likeCount = this.likeCount + 1;
    }

    public void decreaseLikeCount() {
        this.likeCount = Math.max(0, this.likeCount - 1);
    }

    public void edit(String body) {
        this.body = body;
    }

    public void softDelete(LocalDateTime at) {
        this.deletedAt = at;
    }

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }
}
