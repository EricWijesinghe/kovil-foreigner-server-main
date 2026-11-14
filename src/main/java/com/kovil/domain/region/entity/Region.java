package com.kovil.domain.region.entity;

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
@Table(name = "regions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // NULL = 시도(1단), 값 있음 = 시군구(2단). 깊이 2 고정이다.
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 50)
    private String nameKo;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public boolean isRoot() {
        return parentId == null;
    }

    // 부모는 만들 때만 정한다 — 트리를 옮기면 기존 요청의 지역 축이 통째로 흔들린다.
    public void update(String nameKo, String nameEn, Integer sortOrder, Boolean isActive) {
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.sortOrder = sortOrder;
        this.isActive = isActive;
    }

    // /order 재정렬 전용 — 이름·활성·부모는 건드리지 않는다.
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
