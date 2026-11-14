package com.kovil.domain.community.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 좋아요는 이력이 아니다 — 취소는 하드 delete, 멱등의 축은 UNIQUE(post_id, user_id)다.
@Getter
@Entity
@SuperBuilder
@Table(name = "community_post_likes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostLike extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
}
