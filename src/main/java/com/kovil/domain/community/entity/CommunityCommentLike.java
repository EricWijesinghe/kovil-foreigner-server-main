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

@Getter
@Entity
@SuperBuilder
@Table(name = "community_comment_likes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityCommentLike extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "comment_id", nullable = false, updatable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
}
