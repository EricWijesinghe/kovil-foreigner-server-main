package com.kovil.domain.country.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@Entity
@SuperBuilder
@Table(name = "countries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Country extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 2)
    private String code;

    // 영문명이 정본이다. 번역 행이 없으면 이 값이 그대로 응답에 실린다(FR-807).
    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(name = "flag_emoji", length = 16)
    private String flagEmoji;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public void update(String nameEn, String flagEmoji, Integer sortOrder, Boolean isActive) {
        this.nameEn = nameEn;
        this.flagEmoji = flagEmoji;
        this.sortOrder = sortOrder;
        this.isActive = isActive;
    }

    // /order 재정렬 전용 — 이름·활성은 건드리지 않는다.
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
