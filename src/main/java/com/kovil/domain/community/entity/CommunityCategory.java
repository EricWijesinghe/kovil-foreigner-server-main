package com.kovil.domain.community.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.community.exception.CommunityException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

import static com.kovil.domain.community.exception.CommunityErrorCode.CATEGORY_HAS_POSTS;

@Getter
@Entity
@SuperBuilder
@Table(name = "community_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityCategory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "code", nullable = false, updatable = false, length = 40)
    private String code;

    @Column(name = "is_anonymous", nullable = false)
    private boolean isAnonymous;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    // INV-12 — 익명 → 비익명 전환은 과거 익명 글의 작성자를 소급 노출시키는 사고다.
    // 관리자 CRUD가 생기기 전이라도 불변식은 도메인이 소유한다.
    public void changeAnonymous(boolean target, boolean hasPosts) {
        if (this.isAnonymous == target) {
            return;
        }
        if (hasPosts) {
            throw new CommunityException(CATEGORY_HAS_POSTS);
        }
        this.isAnonymous = target;
    }

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    public void update(Integer sortOrder, Boolean active) {
        this.sortOrder = sortOrder;
        this.active = active;
    }

    // /order 재정렬 전용 — 이름·활성은 건드리지 않는다.
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
