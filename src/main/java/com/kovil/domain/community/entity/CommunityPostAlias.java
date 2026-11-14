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

// 한 글 안에서 한 사람은 항상 같은 번호다. user_id는 서버 내부 전용 — 어떤 응답에도 나가지 않는다.
@Getter
@Entity
@SuperBuilder
@Table(name = "community_post_aliases")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostAlias extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "anonymous_no", nullable = false, updatable = false)
    private Short anonymousNo;
}
