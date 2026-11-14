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
@Table(name = "community_posts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPost extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "category_id", nullable = false, updatable = false)
    private Long categoryId;

    // 익명 게시판이어도 유지한다 — 권한 판정·내 글 조회·차단·신고의 축. 응답에는 절대 싣지 않는다.
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // 작성 시점 유저 언어 스냅샷. "내 언어 글만 보기"의 축.
    @Column(name = "language_code", nullable = false, updatable = false, length = 10)
    private String languageCode;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityContentStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isReadable() {
        return status == CommunityContentStatus.ACTIVE && deletedAt == null;
    }

    public boolean isOwnedBy(Long viewerId) {
        return userId.equals(viewerId);
    }

    public void edit(String title, String body) {
        this.title = title;
        this.body = body;
    }

    public void softDelete(LocalDateTime at) {
        this.deletedAt = at;
    }

    // FR-714 — 노출 판정은 이 상태가 소유한다. 신고 행은 이력일 뿐이다.
    public void blind() {
        this.status = CommunityContentStatus.HIDDEN;
    }

    public void unblind() {
        this.status = CommunityContentStatus.ACTIVE;
    }

    // ⚠ 동시성 안전하지 않다(read-modify-write) — 프로덕션 경로는 레포지토리의 원자 UPDATE를
    // 쓴다. 이 메서드들은 테스트의 상태 조립에만 남겨 둔다.
    public void increaseLikeCount() {
        this.likeCount = this.likeCount + 1;
    }

    public void decreaseLikeCount() {
        this.likeCount = Math.max(0, this.likeCount - 1);
    }

    public void increaseCommentCount() {
        this.commentCount = this.commentCount + 1;
    }

    public void decreaseCommentCount() {
        this.commentCount = Math.max(0, this.commentCount - 1);
    }

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }
}
