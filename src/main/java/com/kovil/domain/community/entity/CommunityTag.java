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
@Table(name = "community_tags")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityTag extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 최초 등록자의 표기를 유지한다. 유니크는 정규화 컬럼에 건다.
    @Column(name = "name", nullable = false, updatable = false, length = 30)
    private String name;

    @Column(name = "name_normalized", nullable = false, updatable = false, length = 30)
    private String nameNormalized;

    @Column(name = "usage_count", nullable = false)
    private long usageCount;


}
