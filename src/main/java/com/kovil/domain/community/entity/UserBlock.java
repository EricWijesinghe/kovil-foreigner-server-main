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

// FR-713 — 저장 축은 계정이지만 API는 글·댓글을 지목한다. 익명이 유지되는 이유:
// 차단하는 쪽은 상대가 누군지 끝까지 모른다. 효과는 목록 필터(단방향)뿐이다.
@Getter
@Entity
@SuperBuilder
@Table(name = "user_blocks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "blocker_id", nullable = false, updatable = false)
    private Long blockerId;

    @Column(name = "blocked_user_id", nullable = false, updatable = false)
    private Long blockedUserId;
}
